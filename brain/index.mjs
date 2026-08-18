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
//
// Director scenes and actor turns are independent: director turns never
// run concurrently with each other (lib/director-scheduler.mjs), while
// actor turns run immediately, serialized per (npc, player) conversation so
// two rapid interactions with the same NPC by the same player never race.

import fs from "node:fs";
import path from "node:path";
import { loadConfig, DIRECTOR_WAKE_EVENTS } from "./lib/config.mjs";
import { log, nextSceneNumber } from "./lib/logger.mjs";
import { journalSkip } from "./lib/decisions-journal.mjs";
import { loadLore, watchLore } from "./lib/lore.mjs";
import { UsageTracker } from "./lib/usage-tracker.mjs";
import { RateLimiter } from "./lib/rate-limiter.mjs";
import { BridgeClient } from "./lib/bridge-client.mjs";
import { DirectorScheduler } from "./lib/director-scheduler.mjs";
import { ActorMemory } from "./lib/actor-memory.mjs";
import { runDirectorTurn } from "./lib/director-turn.mjs";
import { runActorTurn } from "./lib/actor-turn.mjs";
import { parseActorReport } from "./lib/actor-report.mjs";

export async function main(env = process.env) {
  const config = loadConfig(env);

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

  const onlinePlayers = new Set();
  const actorQueues = new Map(); // `${npcId}::${player}` -> Promise chain

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
  }

  const scheduler = new DirectorScheduler({ debounceMs: config.debounceMs, runScene });

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
      .then(() => runActor(args))
      .catch((e) => log.error("actor_turn_failed", { npcId: args.npcId, player: args.player, error: String(e && e.stack || e) }));
    actorQueues.set(key, next);
    return next;
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

  return {
    config,
    usage,
    rateLimiter,
    scheduler,
    actorMemory,
    bridge,
    onlinePlayers,
    stop() {
      bridge.stop();
      loreWatch.stop();
      actorMemory.saveSync();
    },
  };
}

main().catch((e) => {
  log.error("brain_fatal", { error: String(e && e.stack || e) });
  process.exit(1);
});
