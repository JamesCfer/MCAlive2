#!/usr/bin/env node
// Smoke test for brain/. Boots the mock bridge, runs the real service
// (index.mjs) against it with BRAIN_DRY_RUN=1 (no Anthropic call, no API
// key needed), and asserts:
//
//   1. auth against the bridge succeeded
//   2. two rapid director-bound player_chat events (no nearNpcId) are
//      batched into ONE director scene together with the player_join that
//      preceded them
//   3. npc_interact routes to an ACTOR turn, never a director scene
//   4. player_chat WITH nearNpcId also routes to an ACTOR turn
//   5. each actor's dry-run prompt contains ONLY the facts the mock
//      npc_context returned for THAT npc (knowledge isolation) - proven
//      against two NPCs with disjoint fact sets
//   6. each actor turn's allowlist is exactly the three actor tools, and its
//      disallowedTools complement excludes them but includes director-only
//      tools (npc_context, ledger_put, ...)
//   7. a later, well-separated event (npc_death) starts its own director
//      scene, separate from the first batch
//   8. the kill switch (BRAIN_DISABLED_FILE present) blocks both director
//      scenes and actor turns entirely
//   9. the daily usage budget file is written on startup
//   10. reconnect-with-backoff against a refused port keeps the process
//      alive and keeps retrying instead of exiting
//   11. the re-enabled M2 wake events (player_idle_scene, region_enter,
//      region_exit) reach the director's scene queue, batched together
//   12. the director's dry-run prompt is a structured briefing: a SCENE
//      section grouped per player, and a STANDING ORDERS section carrying
//      the adjudication procedure and the actor-report validation rule
//   13. actor-report parsing (pure, no process): a well-formed
//      ```report``` block parses to structured JSON, an absent or
//      malformed block is ignored without crashing
//   14. actor conversation memory persists to conversations.json and
//      reloads correctly across a simulated restart, capped at the
//      most-recently-active pairs
//
// Exits non-zero on any failure.

import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";
import { namespacedTool, MCP_SERVER_NAME, ACTOR_TOOLS, actorDisallowedTools, DIRECTOR_WAKE_EVENTS } from "../lib/config.mjs";
import { parseActorReport } from "../lib/actor-report.mjs";
import { ActorMemory } from "../lib/actor-memory.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BRAIN_DIR = path.resolve(__dirname, "..");

let failures = 0;
function assert(cond, msg) {
  if (cond) {
    console.log(`  ok - ${msg}`);
  } else {
    failures += 1;
    console.error(`  FAIL - ${msg}`);
  }
}

function readJsonLines(chunk, sink) {
  for (const line of chunk.toString("utf8").split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      sink.push(JSON.parse(trimmed));
    } catch {
      if (process.env.SMOKE_VERBOSE) console.error("[raw]", trimmed);
    }
  }
}

function spawnNode(args, env) {
  const child = spawn(process.execPath, args, {
    cwd: BRAIN_DIR,
    env: { ...process.env, ...env },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const logs = [];
  child.stdout.on("data", (d) => readJsonLines(d, logs));
  child.stderr.on("data", (d) => readJsonLines(d, logs));
  return { child, logs };
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(logs, predicate, timeoutMs) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (logs.some(predicate)) return true;
    await wait(50);
  }
  return false;
}

async function main() {
  console.log("1. Pure config checks (no process needed)");
  const disallowed = actorDisallowedTools(MCP_SERVER_NAME);
  for (const t of ACTOR_TOOLS) {
    assert(!disallowed.includes(namespacedTool(t, MCP_SERVER_NAME)), `actor tool ${t} is NOT in its own disallow list`);
  }
  assert(disallowed.includes(namespacedTool("npc_context", MCP_SERVER_NAME)), "npc_context (knowledge isolation bypass risk) is denied to actors");
  assert(disallowed.includes(namespacedTool("ledger_put", MCP_SERVER_NAME)), "ledger_put is denied to actors");
  assert(disallowed.includes(namespacedTool("set_block", MCP_SERVER_NAME)), "set_block is denied to actors");

  assert(DIRECTOR_WAKE_EVENTS.has("player_idle_scene"), "player_idle_scene is a director wake event (M2 re-enabled)");
  assert(DIRECTOR_WAKE_EVENTS.has("region_enter"), "region_enter is a director wake event (M2 re-enabled)");
  assert(DIRECTOR_WAKE_EVENTS.has("region_exit"), "region_exit is a director wake event (M2 re-enabled)");

  console.log("\n1b. Actor report parsing (pure, no process needed)");
  const wellFormed = parseActorReport(
    'Hello there!\n```report\n{"facts": [{"text": "The well ran dry.", "knownBy": ["mara-baker"]}], "promise": {"toWhom": "Steve", "text": "flour by dawn"}, "questOffered": null, "mood": "worried"}\n```'
  );
  assert(wellFormed && Array.isArray(wellFormed.facts) && wellFormed.facts[0].text === "The well ran dry.", "well-formed report block parses to structured JSON");
  assert(wellFormed && wellFormed.facts[0].knownBy[0] === "mara-baker", "parsed report preserves knownBy on facts");
  assert(wellFormed && wellFormed.promise.toWhom === "Steve", "parsed report preserves the promise object");
  assert(wellFormed && wellFormed.mood === "worried", "parsed report preserves mood");

  assert(parseActorReport("just dialogue, no report block") === null, "absent report block parses to null without crashing");
  assert(parseActorReport(undefined) === null, "undefined actor text parses to null without crashing");
  const malformed = parseActorReport('Hi!\n```report\n{not valid json at all\n```');
  assert(malformed === null, "malformed report block (bad JSON) is ignored, not thrown");
  const nonObject = parseActorReport('Hi!\n```report\n["just", "an", "array"]\n```');
  assert(nonObject === null, "a report block that parses to a non-object is ignored");

  console.log("\n1c. Actor memory persistence across a simulated restart");
  const memStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-conv-"));
  const memStatePath = path.join(memStateDir, "conversations.json");
  const mem1 = new ActorMemory(20, { statePath: memStatePath, saveDebounceMs: 50 }).load();
  mem1.record("mara-baker", "Steve", "player", "hello there");
  mem1.record("mara-baker", "Steve", "npc", "welcome to the bakery");
  mem1.saveSync();
  assert(fs.existsSync(memStatePath), "conversations.json was written to disk");

  const mem2 = new ActorMemory(20, { statePath: memStatePath, saveDebounceMs: 50 }).load();
  const reloaded = mem2.transcript("mara-baker", "Steve");
  assert(reloaded.recent.length === 2, `reloaded transcript has both turns after a simulated restart (got ${reloaded.recent.length})`);
  assert(reloaded.recent[0].text === "hello there", "reloaded transcript preserves turn order/content across restart");

  console.log("\n1d. Actor memory caps at the most-recently-active pairs");
  const mem3 = new ActorMemory(20, { statePath: path.join(memStateDir, "capped.json"), maxPairs: 3, saveDebounceMs: 50 });
  for (let i = 0; i < 5; i++) {
    mem3.record(`npc-${i}`, "Steve", "player", `msg ${i}`);
  }
  assert(mem3.sessions.size === 3, `actor memory caps at maxPairs (got ${mem3.sessions.size})`);
  assert(mem3.sessions.has("npc-4::Steve") && mem3.sessions.has("npc-3::Steve") && mem3.sessions.has("npc-2::Steve"), "cap evicts the least-recently-active pairs first, keeping the most recent ones");

  console.log("\n2. Director debounce, actor routing, knowledge isolation");
  const port1 = 8899;
  const bridge1 = spawnNode([path.join(BRAIN_DIR, "test", "mock-bridge.mjs")], {
    MOCK_BRIDGE_PORT: String(port1),
    MOCK_BRIDGE_TOKEN: "test-token",
  });
  await wait(300);

  const stateDir1 = fs.mkdtempSync(path.join(os.tmpdir(), "brain-usage-"));
  const brain1 = spawnNode([path.join(BRAIN_DIR, "index.mjs")], {
    MCALIVE2_URL: `ws://127.0.0.1:${port1}`,
    MCALIVE2_TOKEN: "test-token",
    BRAIN_DEBOUNCE_MS: "300",
    BRAIN_DRY_RUN: "1",
    BRAIN_ENABLED: "1",
    BRAIN_LORE_REFRESH_MS: "600000",
    BRAIN_STATE_DIR: stateDir1,
  });

  const authed = await waitFor(brain1.logs, (l) => l.msg === "bridge_auth_ok", 5000);
  assert(authed, "brain authenticated against the bridge");

  // --- director debounce ---
  const gotFirstScene = await waitFor(brain1.logs, (l) => l.msg === "director_scene_starting", 5000);
  assert(gotFirstScene, "first debounced director scene started");
  const sceneStarts = () => brain1.logs.filter((l) => l.msg === "director_scene_starting");
  const first = sceneStarts()[0];
  if (first) {
    assert(first.batchSize === 3, `first scene batched player_join + both player_chat events (got batchSize=${first.batchSize})`);
    const chatCount = first.events.filter((e) => e === "player_chat").length;
    assert(chatCount === 2, `first scene contains both rapid player_chat messages as ONE scene (got ${chatCount})`);
    assert(first.events.includes("player_join"), "first scene also includes the player_join");
    assert(!first.events.includes("npc_interact"), "npc_interact never lands in a director scene");
  }

  // --- actor routing ---
  const gotMaraActor = await waitFor(brain1.logs, (l) => l.msg === "dry_run_actor_turn" && l.npcId === "mara-baker", 5000);
  assert(gotMaraActor, "npc_interact for mara-baker produced an ACTOR turn (dry run)");
  const maraTurn = brain1.logs.find((l) => l.msg === "dry_run_actor_turn" && l.npcId === "mara-baker");
  if (maraTurn) {
    assert(maraTurn.trigger === "npc_interact", "mara's actor turn was triggered by npc_interact");
  }

  const gotKessActor = await waitFor(brain1.logs, (l) => l.msg === "dry_run_actor_turn" && l.npcId === "kess-smith", 5000);
  assert(gotKessActor, "player_chat with nearNpcId=kess-smith produced an ACTOR turn (dry run)");
  const kessTurn = brain1.logs.find((l) => l.msg === "dry_run_actor_turn" && l.npcId === "kess-smith");
  if (kessTurn) {
    assert(kessTurn.trigger === "player_chat", "kess's actor turn was triggered by player_chat");
  }

  // --- knowledge isolation ---
  if (maraTurn) {
    assert(maraTurn.prompt.includes("Mara has run the bakery"), "mara's prompt includes mara's own fact");
    assert(!maraTurn.prompt.includes("strange ore vein"), "mara's prompt does NOT leak kess's fact");
  }
  if (kessTurn) {
    assert(kessTurn.prompt.includes("strange ore vein"), "kess's prompt includes kess's own fact");
    assert(!kessTurn.prompt.includes("run the bakery"), "kess's prompt does NOT leak mara's fact");
  }

  // --- actor allowlist ---
  const expectedAllowed = ACTOR_TOOLS.map((t) => namespacedTool(t, MCP_SERVER_NAME)).sort();
  for (const turn of [maraTurn, kessTurn]) {
    if (!turn) continue;
    const got = [...turn.allowedTools].sort();
    assert(JSON.stringify(got) === JSON.stringify(expectedAllowed), `actor allowedTools is exactly the 3 actor tools for ${turn.npcId} (got ${JSON.stringify(got)})`);
    assert(!turn.disallowedTools.includes(namespacedTool("npc_say", MCP_SERVER_NAME)), `npc_say is not disallowed for ${turn.npcId}`);
    assert(turn.disallowedTools.includes(namespacedTool("ledger_put", MCP_SERVER_NAME)), `ledger_put is disallowed for ${turn.npcId}`);
  }

  // mock bridge only ever saw npc_context calls for these two npcs (never a
  // ledger/world command, since BRAIN_DRY_RUN=1 never spawns the real MCP
  // tool server that would issue those).
  const npcContextCalls = bridge1.logs.filter((l) => l.mock === "command_received" && l.cmd === "npc_context");
  assert(npcContextCalls.some((c) => c.args.npcId === "mara-baker"), "mock bridge received npc_context(mara-baker)");
  assert(npcContextCalls.some((c) => c.args.npcId === "kess-smith"), "mock bridge received npc_context(kess-smith)");

  // --- separate director scene for the later, well-separated event ---
  const gotSecondScene = await waitFor(brain1.logs, () => sceneStarts().length >= 2, 3000);
  assert(gotSecondScene, "a second, separate director scene started for the later npc_death event");
  const second = sceneStarts()[1];
  if (second) {
    assert(second.events.includes("npc_death"), "second scene is for the npc_death event");
    assert(second.batchSize === 1, `second scene was not merged with the first (batchSize=${second.batchSize})`);
  }

  // --- re-enabled M2 wake events (player_idle_scene/region_enter/region_exit) ---
  const gotThirdScene = await waitFor(brain1.logs, () => sceneStarts().length >= 3, 3000);
  assert(gotThirdScene, "a third director scene started for the region_enter/region_exit/player_idle_scene events");
  const third = sceneStarts()[2];
  if (third) {
    assert(third.events.includes("region_enter"), "third scene includes region_enter");
    assert(third.events.includes("region_exit"), "third scene includes region_exit");
    assert(third.events.includes("player_idle_scene"), "third scene includes player_idle_scene");
    assert(third.batchSize === 3, `third scene batched all three re-enabled M2 events together (got batchSize=${third.batchSize})`);
  }

  // --- director briefing structure: SCENE grouping + adjudication procedure ---
  const directorDryRun = brain1.logs.find((l) => l.msg === "dry_run_director_turn");
  assert(directorDryRun, "director dry-run prompt was logged");
  if (directorDryRun) {
    assert(directorDryRun.prompt.includes("STANDING ORDERS"), "director prompt has a STANDING ORDERS section");
    assert(directorDryRun.prompt.includes("SCENE"), "director prompt has a SCENE section");
    assert(directorDryRun.prompt.includes("ADJUDICATION PROCEDURE"), "director prompt has an explicit adjudication procedure section");
    assert(directorDryRun.prompt.includes("Is it plausible in fiction given ledger facts"), "director prompt includes the adjudication procedure steps");
    assert(directorDryRun.prompt.includes("Silence and inaction remain first-class choices"), "director prompt states silence/inaction as first-class choices");
    assert(directorDryRun.prompt.includes("REQUIRED FINAL SUMMARY"), "director prompt requires a final summary paragraph");
    assert(directorDryRun.prompt.includes("Actors propose, the director disposes"), "director prompt instructs validating actor reports before ledger writes");
    assert(directorDryRun.prompt.includes("Player: Steve"), "director prompt groups the scene per player");
  }

  brain1.child.kill();
  bridge1.child.kill();

  // --- usage budget file written ---
  const usageFile = path.join(stateDir1, "usage.json");
  assert(fs.existsSync(usageFile), "usage.json budget file was written to BRAIN_STATE_DIR");

  console.log("\n3. Kill switch blocks director scenes AND actor turns");
  const port2 = 8900;
  const bridge2 = spawnNode([path.join(BRAIN_DIR, "test", "mock-bridge.mjs")], {
    MOCK_BRIDGE_PORT: String(port2),
    MOCK_BRIDGE_TOKEN: "test-token",
  });
  await wait(300);

  const disabledFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "brain-disabled-")), "DISABLED");
  fs.writeFileSync(disabledFile, "");

  const brain2 = spawnNode([path.join(BRAIN_DIR, "index.mjs")], {
    MCALIVE2_URL: `ws://127.0.0.1:${port2}`,
    MCALIVE2_TOKEN: "test-token",
    BRAIN_DEBOUNCE_MS: "300",
    BRAIN_DRY_RUN: "1",
    BRAIN_ENABLED: "1",
    BRAIN_DISABLED_FILE: disabledFile,
    BRAIN_LORE_REFRESH_MS: "600000",
    BRAIN_STATE_DIR: fs.mkdtempSync(path.join(os.tmpdir(), "brain-usage-")),
  });

  const authed2 = await waitFor(brain2.logs, (l) => l.msg === "bridge_auth_ok", 5000);
  assert(authed2, "second brain instance authenticated against the bridge");

  const sawDirectorSkip = await waitFor(brain2.logs, (l) => l.msg === "turn_skipped_kill_switch" && l.kind === "director", 3000);
  assert(sawDirectorSkip, "kill switch skip was logged for a director scene");
  const sawActorSkip = await waitFor(brain2.logs, (l) => l.msg === "turn_skipped_kill_switch" && l.kind === "actor", 3000);
  assert(sawActorSkip, "kill switch skip was logged for an actor turn (npc_interact)");
  assert(!brain2.logs.some((l) => l.msg === "director_scene_starting"), "no director scene ran while the kill switch file exists");
  assert(!brain2.logs.some((l) => l.msg === "dry_run_actor_turn"), "no actor turn ran while the kill switch file exists");

  brain2.child.kill();
  bridge2.child.kill();

  console.log("\n4. Connect failure keeps retrying instead of exiting");
  const deadPort = 8901; // nothing listening here
  const brain3 = spawnNode([path.join(BRAIN_DIR, "index.mjs")], {
    MCALIVE2_URL: `ws://127.0.0.1:${deadPort}`,
    MCALIVE2_TOKEN: "test-token",
    BRAIN_DEBOUNCE_MS: "300",
    BRAIN_DRY_RUN: "1",
    BRAIN_ENABLED: "1",
    BRAIN_LORE_REFRESH_MS: "600000",
    BRAIN_STATE_DIR: fs.mkdtempSync(path.join(os.tmpdir(), "brain-usage-")),
  });

  const sawTwoRetries = await waitFor(
    brain3.logs,
    () => brain3.logs.filter((l) => l.msg === "bridge_reconnect_scheduled").length >= 2,
    3500
  );
  assert(sawTwoRetries, "at least 2 reconnect attempts were logged within 3.5s");
  assert(brain3.child.exitCode === null, "brain process is still running after repeated connect failures");

  brain3.child.kill();

  await wait(200);

  console.log(`\n${failures === 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("smoke test crashed:", e);
  process.exit(1);
});
