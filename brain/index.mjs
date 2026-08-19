#!/usr/bin/env node
// MCAlive2 brain: the director loop + NPC actor routing described in
// DESIGN.md's "Brain" section.
//
// Opens ONE WebSocket connection to the plugin bridge (lib/bridge-client.mjs)
// used both to receive pushed sense events (the wake-up signal) and to issue
// direct request/response commands the routing layer needs before spawning
// an actor turn (npc_context - see lib/actor-turn.mjs for why that can't be
// a tool call made BY the actor itself).
//
// Routing:
//   - npc_interact                              -> ALWAYS an actor turn
//   - player_chat within BRAIN_NPC_CHAT_RANGE
//     of an NPC (event carries data.nearNpcId)   -> an actor turn
//   - player_chat with no nearby NPC, and every
//     other sense event (join/death/explored/
//     npc_attacked/npc_death/player_idle_scene/
//     region_enter/region_exit)                  -> debounced into the
//                                                    director's scene queue
//   - player_quit                                -> presence tracking only,
//                                                    never wakes anything
//   - entity_positions                            -> pure telemetry (pushed
//                                                    ~1s by the auto-
//                                                    installed position-
//                                                    tracker gadget - see
//                                                    installPositionTracker
//                                                    below); feeds
//                                                    lib/position-cache.mjs
//                                                    and NEVER wakes the
//                                                    director
//
// Director scenes and actor turns are independent: director turns never
// run concurrently with each other (lib/director-scheduler.mjs), while
// actor turns run immediately, serialized per (npc, player) conversation so
// two rapid interactions with the same NPC by the same player never race.
//
// Self-update (lib/self-update.mjs) runs alongside all of the above: on a
// timer it checks origin/main, and if new code has landed it pulls, npm
// installs if needed, waits for the idle point below, then exits 75 for
// run-forever.cmd to restart into.

import fs from "node:fs";
import path from "node:path";
import { loadConfig, DIRECTOR_WAKE_EVENTS, BRAIN_ROOT } from "./lib/config.mjs";
import { log, nextSceneNumber } from "./lib/logger.mjs";
import { journalSkip } from "./lib/decisions-journal.mjs";
import { loadLore, watchLore } from "./lib/lore.mjs";
import { startConsoleServer, queuedOrders, setOrderStatus } from "./lib/console-server.mjs";
import { buildWorldModel } from "./lib/worldmodel.mjs";
import { positionCache } from "./lib/position-cache.mjs";
import { UsageTracker } from "./lib/usage-tracker.mjs";
import { RateLimiter } from "./lib/rate-limiter.mjs";
import { BridgeClient } from "./lib/bridge-client.mjs";
import { DirectorScheduler } from "./lib/director-scheduler.mjs";
import { ActorMemory } from "./lib/actor-memory.mjs";
import { runDirectorTurn } from "./lib/director-turn.mjs";
import { runActorTurn } from "./lib/actor-turn.mjs";
import { parseActorReport } from "./lib/actor-report.mjs";
import { SelfUpdater } from "./lib/self-update.mjs";

export async function main(env = process.env) {
  const config = loadConfig(env);
  positionCache.staleSec = config.positionStaleSec;

  log.info("brain_starting", {
    directorModel: config.directorModel,
    actorModel: config.actorModel,
    dryRun: config.dryRun,
    enabled: config.enabled,
    debounceMs: config.debounceMs,
    dailyTokenBudget: config.dailyTokenBudget,
    maxTurnsPerMin: config.maxTurnsPerMin,
    mcalive2Url: config.mcalive2Url,
  });

  const usage = await new UsageTracker(config.stateDir, config.dailyTokenBudget).load();
  const rateLimiter = new RateLimiter(config.maxTurnsPerMin, 60000);
  const actorMemory = new ActorMemory(config.actorHistoryTurns, {
    statePath: path.join(config.stateDir, "conversations.json"),
  }).load();

  const lore = { text: await loadLore(config.loreDir) };
  const loreWatch = watchLore(config.loreDir, config.loreRefreshMs, (text) => {
    lore.text = text;
    log.info("lore_reloaded", { bytes: text.length });
  });

  // Lore Console: operator-facing HTML page for typing free-text lore
  // directives without touching files by hand (lib/console-server.mjs).
  // Reuses loreWatch's own `tick` - the exact function lore.mjs's timer
  // calls - so a directive lands in `lore.text` immediately instead of
  // waiting up to BRAIN_LORE_REFRESH_MS.
  //
  // submitOrder is the "order the world" half of the console: unlike a
  // directive (permanent taste, folded into lore), an order is a one-shot
  // command the director must execute NOW. It never touches lore/ - it
  // pushes an "operator_order" scene event onto the SAME debounced director
  // scheduler that pushed bridge sense events use (scheduler.push below),
  // so the very next scene carries it as an event to act on, not as style.
  function submitOrder(text, orderTimestamp) {
    scheduler.push("operator_order", { text, orderTimestamp, at: new Date().toISOString() });
  }

  // getWorldModel is a closure over `bridge` (assigned further below, once
  // the brain's own bridge connection is created) rather than a direct
  // reference - fine, since it's only ever CALLED from a console HTTP
  // request handled long after this synchronous setup finishes. Backs GET
  // /worldmodel (console-server.mjs), which the /map 3D viewer polls.
  function getWorldModel() {
    return buildWorldModel((cmd, args) => bridge.call(cmd, args), {
      now: new Date().toISOString(),
      // Live positions (pushed "entity_positions" events, cached by
      // lib/position-cache.mjs - see installPositionTracker below) are
      // available in THIS process, unlike mcp-bridge.mjs's world_overview
      // tool, which runs in a separate short-lived process that never sees
      // pushed events - see position-cache.mjs's module comment.
      npcPositions: (id) => positionCache.npcPosition(id, Date.now()),
      worldScanMaxCells: config.worldScanMaxCells,
    });
  }

  let consoleServer = null;
  if (config.consoleEnabled) {
    consoleServer = await startConsoleServer(config, { reloadLore: loreWatch.tick, submitOrder, getWorldModel });
    log.info("console_listening", { bind: config.consoleBind, port: consoleServer.port });
  }

  const onlinePlayers = new Set();
  const actorQueues = new Map(); // `${npcId}::${player}` -> Promise chain
  let activeActorTurns = 0; // in-flight runActor() calls, for the idle check below

  /** Resolves once no director scene is mid-flight AND no actor turn is
   * in-flight - the "safe to restart" point self-update.mjs waits for
   * before exiting, so a running update never kills a turn mid-flight.
   * Neither DirectorScheduler nor the actor queue previously exposed an
   * idle signal of their own, so this polls the two flags directly rather
   * than threading a new event through both - self-update only calls this
   * right before an infrequent restart, so polling cost is a non-issue. */
  function waitUntilIdle(pollMs = 200) {
    return new Promise((resolve) => {
      const check = () => {
        if (!scheduler.sceneRunning && activeActorTurns === 0) {
          resolve();
          return;
        }
        const t = setTimeout(check, pollMs);
        if (typeof t.unref === "function") t.unref();
      };
      check();
    });
  }

  function isKillSwitchActive() {
    if (!config.enabled) return true;
    try {
      return fs.existsSync(config.disabledFile);
    } catch {
      return false;
    }
  }

  /** @param {"director"|"actor"} kind
   * @param {{sceneNumber?: number, batch?: Array, npcId?: string, player?: string, trigger?: string}} context
   * Both console log fields and the decisions journal need to say WHICH
   * scene/actor-turn a guardrail blocked - context carries the identifying
   * bits for whichever kind this is (a scene number + batch for the
   * director, npc/player/trigger for an actor). */
  function guardrailBlock(kind, context = {}) {
    if (isKillSwitchActive()) {
      log.warn(`turn_skipped_kill_switch`, { kind, ...idFields(context) });
      journalSkip({ config, kind, reason: "kill_switch", ...context });
      return "kill_switch";
    }
    if (usage.isOverBudget()) {
      log.warn(`turn_skipped_budget_exceeded`, {
        kind,
        tokensUsed: usage.tokens,
        dailyTokenBudget: config.dailyTokenBudget,
        ...idFields(context),
      });
      journalSkip({ config, kind, reason: "budget_exceeded", ...context });
      return "budget";
    }
    return null;
  }

  function idFields(context) {
    const { sceneNumber, npcId, player } = context;
    const out = {};
    if (sceneNumber !== undefined) out.sceneNumber = sceneNumber;
    if (npcId !== undefined) out.npcId = npcId;
    if (player !== undefined) out.player = player;
    return out;
  }

  // ---------------- Director scene runner ----------------

  async function runScene(batch) {
    const sceneNumber = nextSceneNumber();
    const block = guardrailBlock("director", { sceneNumber, batch });
    if (block) return;
    if (!rateLimiter.tryAcquire()) {
      log.warn("turn_skipped_rate_limited", {
        kind: "director",
        sceneNumber,
        maxTurnsPerMin: config.maxTurnsPerMin,
        retryInMs: rateLimiter.msUntilSlot(),
      });
      journalSkip({ config, kind: "director", reason: "rate_limited", sceneNumber, batch });
      scheduler.pending.unshift(...batch); // re-queue for the next debounce cycle
      return;
    }

    log.info("director_scene_starting", {
      sceneNumber,
      batchSize: batch.length,
      events: batch.map((e) => e.event),
    });

    const result = await runDirectorTurn({ batch, systemPrompt: lore.text, config, sceneNumber });
    if (result.totalTokens > 0) await usage.addTokens(result.totalTokens);

    // Orders (Lore Console "order the world" - console-server.mjs) carry
    // their orders.json timestamp on the scene event (see submitOrder
    // above); once their scene finishes, flip that same entry to "done" so
    // it's never replayed - unless the scene timed out (lib/timed-
    // query.mjs), in which case it reverts to "queued" so the next restart
    // (or the boot-time replay below) retries it rather than losing it.
    for (const e of batch) {
      if (e.event === "operator_order" && e.data && e.data.orderTimestamp) {
        const newStatus = result.timedOut ? "queued" : "done";
        setOrderStatus(config, e.data.orderTimestamp, newStatus);
        log.info("order_status_updated", { timestamp: e.data.orderTimestamp, status: newStatus });
      }
    }
  }

  const scheduler = new DirectorScheduler({ debounceMs: config.debounceMs, runScene });

  // Orders persisted to state/orders.json survive a restart on disk, but the
  // scheduler push that would have carried a still-"queued" one into a scene
  // does not - it only ever lived in memory. Re-queue every queued order
  // here, oldest first, so a process that stopped (crash, self-update, a
  // timed-out scene) resumes exactly where its operator left it instead of
  // silently dropping the order.
  for (const order of queuedOrders(config)) {
    scheduler.push("operator_order", { text: order.text, orderTimestamp: order.timestamp, at: new Date().toISOString() });
    log.info("order_requeued_on_boot", {
      timestamp: order.timestamp,
      text: order.text.length > 120 ? order.text.slice(0, 117) + "..." : order.text,
    });
  }

  // ---------------- Actor turn runner ----------------

  async function fetchNpcContext(npcId) {
    // {npc, facts} - the plugin filters facts to knownBy includes
    // [npcId | npcId's faction | "all"] before ever handing them back.
    const data = await bridge.call("npc_context", { npcId }, config.npcContextTimeoutMs);
    return { npc: data.npc, facts: data.facts || [] };
  }

  async function runActor({ npcId, player, trigger, message }) {
    const block = guardrailBlock("actor", { npcId, player, trigger });
    if (block) return;
    if (!rateLimiter.tryAcquire()) {
      log.warn("turn_skipped_rate_limited", {
        kind: "actor",
        npcId,
        player,
        maxTurnsPerMin: config.maxTurnsPerMin,
        retryInMs: rateLimiter.msUntilSlot(),
      });
      journalSkip({ config, kind: "actor", reason: "rate_limited", npcId, player, trigger });
      return;
    }

    let ctx;
    try {
      ctx = await fetchNpcContext(npcId);
    } catch (e) {
      log.error("npc_context_fetch_failed", { npcId, error: String(e && e.message || e) });
      return;
    }
    if (!ctx.npc) {
      log.warn("actor_turn_skipped_unknown_npc", { npcId });
      return;
    }

    if (trigger === "player_chat" && message) actorMemory.record(npcId, player, "player", message);
    const transcript = actorMemory.transcript(npcId, player);

    log.info("actor_turn_starting", { npcId, player, trigger });
    const result = await runActorTurn({
      npc: ctx.npc,
      facts: ctx.facts,
      player,
      trigger,
      message,
      transcript,
      config,
    });
    if (result.totalTokens > 0) await usage.addTokens(result.totalTokens);

    if (!result.dryRun && result.reportText) {
      actorMemory.record(npcId, player, "npc", result.reportText);
      // Absent report = fine (parseActorReport returns null); malformed
      // report = logged by parseActorReport itself and ignored - either
      // way a broken actor reply never crashes the brain.
      const report = parseActorReport(result.reportText);
      if (report) {
        // Hand the actor's proposed facts/promise/quest offer to the
        // DIRECTOR (as structured JSON, not raw text) to validate into the
        // ledger next scene, rather than trusting the (cheap,
        // knowledge-isolated) actor to write the ledger itself.
        scheduler.push("actor_report", { npcId, player, report });
      }
    }
  }

  /** Serialize actor turns per (npc, player) conversation so two rapid
   * interactions never race, without blocking other NPCs' conversations. */
  function queueActor(args) {
    const key = `${args.npcId}::${args.player}`;
    const prior = actorQueues.get(key) || Promise.resolve();
    const next = prior
      .catch(() => {})
      .then(() => {
        activeActorTurns += 1;
        return runActor(args).finally(() => {
          activeActorTurns -= 1;
        });
      })
      .catch((e) => log.error("actor_turn_failed", { npcId: args.npcId, player: args.player, error: String(e && e.stack || e) }));
    actorQueues.set(key, next);
    return next;
  }

  // ---------------- Live position tracking (boot auto-install) ----------------

  /** Injects+starts brain/gadgets/position-tracker.java on the running
   * server via gadget_define/gadget_run (see README "Gadgets") so it
   * streams "entity_positions" bridge events on a timer, feeding
   * lib/position-cache.mjs. bridge.call() itself waits for auth (see
   * BridgeClient#_waitUntilReady), so this can safely be kicked off right
   * after bridge.start() rather than needing its own "authed" callback.
   *
   * Idempotent/safe to call again on every boot: gadget_define overwrites
   * the same id's source, and the gadget's own run() cancels its previous
   * timer task (tracked in a JVM system property) before starting a new
   * one - see the gadget source's own comment.
   *
   * Never throws: any failure (server not yet on a gadget-capable plugin
   * version, gadgets disabled, compile error, connection down) is caught
   * and logged as a WARN, and the brain carries on exactly as it did before
   * this feature existed - lib/worldmodel.mjs falls back to ledger home
   * positions when the cache has nothing for an NPC. */
  async function installPositionTracker() {
    if (!config.positionTrackingEnabled) {
      log.info("position_tracking_disabled", { reason: "BRAIN_POSITION_TRACKING=0" });
      return;
    }
    try {
      const gadgetPath = path.join(BRAIN_ROOT, "gadgets", "position-tracker.java");
      const source = fs.readFileSync(gadgetPath, "utf8");
      await bridge.call(
        "gadget_define",
        { id: "position-tracker", source, description: "System: stream live NPC/player positions" },
        config.npcContextTimeoutMs
      );
      await bridge.call(
        "gadget_run",
        { id: "position-tracker", args: { intervalTicks: config.positionIntervalTicks } },
        config.npcContextTimeoutMs
      );
      log.info("position_tracking_installed", { intervalTicks: config.positionIntervalTicks });
    } catch (e) {
      const reason = String((e && e.message) || e);
      log.warn("position_tracking_unavailable", {
        message: `live position tracking unavailable: ${reason}; world model will fall back to ledger home positions`,
      });
    }
  }

  /** Injects brain/gadgets/world-scan.java on the running server via
   * gadget_define (see README "Gadgets") so lib/worldmodel.mjs can call
   * "gadget:world-scan" on demand to survey the WHOLE currently-loaded
   * world (every loaded chunk) instead of just the bounding box of
   * recorded places - see lib/worldmodel.mjs's terrain acquisition.
   *
   * Unlike installPositionTracker, this never gadget_runs anything - the
   * gadget is a synchronous survey called on demand per world-model build,
   * not a timer-driven pusher. Idempotent/safe on every boot: gadget_define
   * overwrites the same id's source.
   *
   * Never throws: any failure (server not yet on a gadget-capable plugin
   * version, gadgets disabled, compile error, connection down) is caught
   * and logged as a WARN, and lib/worldmodel.mjs falls back to its
   * pre-existing scan_area-based terrain acquisition exactly as before this
   * feature existed. */
  async function installWorldScan() {
    if (!config.worldScanEnabled) {
      log.info("world_scan_disabled", { reason: "BRAIN_WORLD_SCAN=0" });
      return;
    }
    try {
      const gadgetPath = path.join(BRAIN_ROOT, "gadgets", "world-scan.java");
      const source = fs.readFileSync(gadgetPath, "utf8");
      await bridge.call(
        "gadget_define",
        { id: "world-scan", source, description: "System: survey the whole loaded world into a coarse heightmap for the world model/map" },
        config.npcContextTimeoutMs
      );
      log.info("world_scan_installed", {});
    } catch (e) {
      const reason = String((e && e.message) || e);
      log.warn("world_scan_unavailable", {
        message: `world-scan gadget unavailable: ${reason}; world model will fall back to scan_area over recorded places`,
      });
    }
  }

  /**
   * Install the update-restart watcher: it applies staged plugin updates by
   * restarting the server, but ONLY when scripts/run-server.cmd's sentinel proves
   * a restart loop is supervising this launch - so an unsupervised server can
   * never be shut down and left stranded.
   */
  async function installUpdateRestart() {
    if (!config.serverAutoRestartEnabled) {
      log.info("server_autorestart_disabled", { reason: "BRAIN_SERVER_AUTORESTART=0" });
      return;
    }
    try {
      const gadgetPath = path.join(BRAIN_ROOT, "gadgets", "update-restart.java");
      const source = fs.readFileSync(gadgetPath, "utf8");
      await bridge.call(
        "gadget_define",
        { id: "update-restart", source, description: "System: restart the server to apply staged updates" },
        config.npcContextTimeoutMs
      );
      const res = await bridge.call(
        "gadget_run",
        {
          id: "update-restart",
          args: {
            checkSeconds: config.serverRestartCheckSec,
            graceSeconds: config.serverRestartGraceSec,
          },
        },
        config.npcContextTimeoutMs
      );
      log.info("server_autorestart_installed", {
        checkSeconds: config.serverRestartCheckSec,
        graceSeconds: config.serverRestartGraceSec,
        sentinelPresent: !!(res && res.sentinelPresent),
      });
    } catch (e) {
      const reason = String((e && e.message) || e);
      log.warn("server_autorestart_unavailable", {
        message: `server auto-restart unavailable: ${reason}; staged plugin updates will apply on your next manual restart`,
      });
    }
  }

  // ---------------- Event routing ----------------

  const bridge = new BridgeClient({
    url: config.mcalive2Url,
    token: config.mcalive2Token,
    baseMs: config.reconnectBaseMs,
    maxMs: config.reconnectMaxMs,
    onEvent(event, data) {
      log.info("event_received", { event, data });

      if (event === "player_join" && data && data.player) onlinePlayers.add(data.player);
      if (event === "player_quit") {
        if (data && data.player) onlinePlayers.delete(data.player);
        return; // presence tracking only, never wakes anything
      }

      if (event === "entity_positions") {
        // Pure telemetry pushed ~1s by gadgets/position-tracker.java - feed
        // the cache and stop. Deliberately NOT routed to the director
        // scheduler: it is not a wake event (see config.mjs's
        // DIRECTOR_WAKE_EVENTS, which omits "entity_positions" on purpose),
        // just a background position refresh lib/worldmodel.mjs reads from.
        positionCache.updateFromEvent(data, Date.now());
        return;
      }

      if (event === "npc_interact" && data && data.npcId && data.player) {
        queueActor({ npcId: data.npcId, player: data.player, trigger: "npc_interact" });
        return;
      }

      if (event === "player_chat" && data && data.nearNpcId && data.player) {
        // Within BRAIN_NPC_CHAT_RANGE of an NPC - the plugin decided
        // proximity and stamped nearNpcId on the event; route to that
        // NPC's actor instead of the director.
        queueActor({ npcId: data.nearNpcId, player: data.player, trigger: "player_chat", message: data.message });
        return;
      }

      // Everything else feeds the director's debounced scene queue, if it's
      // a sense event the director cares about at all.
      if (DIRECTOR_WAKE_EVENTS.has(event)) scheduler.push(event, data);
    },
  });
  bridge.start();
  installPositionTracker(); // fire-and-forget: never throws, see its own comment
  installUpdateRestart(); // fire-and-forget: never throws, sentinel-gated
  installWorldScan(); // fire-and-forget: never throws, see its own comment

  const stop = () => {
    bridge.stop();
    loreWatch.stop();
    actorMemory.saveSync();
    if (consoleServer) return consoleServer.stop();
  };

  // Self-update: check origin/main on a timer (BRAIN_UPDATE_CHECK_SEC,
  // default 10s, 0=disabled; BRAIN_UPDATE_CHECK_MIN still honored as a
  // fallback, converted to seconds); when it pulls new code it waits for the
  // director/actor idle point above, runs the same stop() path a manual
  // shutdown would, then exits 75 - run-forever.cmd (brain/run-forever.cmd)
  // interprets exit 75 as "restart me immediately", any other nonzero exit
  // as a crash (restart after a short delay), and 0 as a deliberate stop.
  const selfUpdater = new SelfUpdater({
    checkIntervalSec: config.updateCheckSec,
    waitForIdle: waitUntilIdle,
    idleWaitCapSec: config.updateIdleWaitCapSec,
    restart: () => {
      stop();
      process.exit(75);
    },
  }).start();

  return {
    config,
    usage,
    rateLimiter,
    scheduler,
    actorMemory,
    bridge,
    onlinePlayers,
    selfUpdater,
    stop,
  };
}

main().catch((e) => {
  log.error("brain_fatal", { error: String(e && e.stack || e) });
  process.exit(1);
});
