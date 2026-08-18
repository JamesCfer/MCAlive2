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
//     npc_attacked/npc_death)                    -> debounced into the
//                                                    director's scene queue
//     (idle_scene/region_enter/exit are M2 - the
//      plugin doesn't emit them yet)
//   - player_quit                                -> presence tracking only,
//                                                    never wakes anything
//
// Director scenes and actor turns are independent: director turns never
// run concurrently with each other (lib/director-scheduler.mjs), while
// actor turns run immediately, serialized per (npc, player) conversation so
// two rapid interactions with the same NPC by the same player never race.

import fs from "node:fs";
import { loadConfig, DIRECTOR_WAKE_EVENTS } from "./lib/config.mjs";
import { log } from "./lib/logger.mjs";
import { loadLore, watchLore } from "./lib/lore.mjs";
import { UsageTracker } from "./lib/usage-tracker.mjs";
import { RateLimiter } from "./lib/rate-limiter.mjs";
import { BridgeClient } from "./lib/bridge-client.mjs";
import { DirectorScheduler } from "./lib/director-scheduler.mjs";
import { ActorMemory } from "./lib/actor-memory.mjs";
import { runDirectorTurn } from "./lib/director-turn.mjs";
import { runActorTurn } from "./lib/actor-turn.mjs";

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
  const actorMemory = new ActorMemory(config.actorHistoryTurns);

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

  function guardrailBlock(kind) {
    if (isKillSwitchActive()) {
      log.warn(`turn_skipped_kill_switch`, { kind });
      return "kill_switch";
    }
    if (usage.isOverBudget()) {
      log.warn(`turn_skipped_budget_exceeded`, {
        kind,
        tokensUsed: usage.tokens,
        dailyTokenBudget: config.dailyTokenBudget,
      });
      return "budget";
    }
    return null;
  }

  // ---------------- Director scene runner ----------------

  async function runScene(batch) {
    const block = guardrailBlock("director");
    if (block) return;
    if (!rateLimiter.tryAcquire()) {
      log.warn("turn_skipped_rate_limited", {
        kind: "director",
        maxTurnsPerMin: config.maxTurnsPerMin,
        retryInMs: rateLimiter.msUntilSlot(),
      });
      scheduler.pending.unshift(...batch); // re-queue for the next debounce cycle
      return;
    }

    log.info("director_scene_starting", {
      batchSize: batch.length,
      events: batch.map((e) => e.event),
    });

    const result = await runDirectorTurn({ batch, systemPrompt: lore.text, config });
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
    const block = guardrailBlock("actor");
    if (block) return;
    if (!rateLimiter.tryAcquire()) {
      log.warn("turn_skipped_rate_limited", {
        kind: "actor",
        npcId,
        player,
        maxTurnsPerMin: config.maxTurnsPerMin,
        retryInMs: rateLimiter.msUntilSlot(),
      });
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
      const report = extractReport(result.reportText);
      if (report) {
        // Hand the actor's proposed facts/promises/quest offer to the
        // DIRECTOR to validate into the ledger next scene, rather than
        // trusting the (cheap, knowledge-isolated) actor to write the
        // ledger itself.
        scheduler.push("actor_report", { npcId, player, report });
      }
    }
  }

  function extractReport(text) {
    const m = /```report\s*([\s\S]*?)```/.exec(text || "");
    if (!m) return null;
    try {
      return JSON.parse(m[1]);
    } catch {
      return null;
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
    },
  };
}

main().catch((e) => {
  log.error("brain_fatal", { error: String(e && e.stack || e) });
  process.exit(1);
});
