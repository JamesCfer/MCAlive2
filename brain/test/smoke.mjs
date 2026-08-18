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
import { namespacedTool, MCP_SERVER_NAME, ACTOR_TOOLS, actorDisallowedTools, DIRECTOR_WAKE_EVENTS, loadConfig } from "../lib/config.mjs";
import { parseActorReport } from "../lib/actor-report.mjs";
import { ActorMemory } from "../lib/actor-memory.mjs";
import { formatHumanLine, compactArgs } from "../lib/logger.mjs";
import { journalScene } from "../lib/decisions-journal.mjs";
import { SelfUpdater } from "../lib/self-update.mjs";

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

  assert(loadConfig({}).updateCheckSec === 10, "BRAIN_UPDATE_CHECK_SEC defaults to 10 seconds");
  // num()/bool01() read directly off process.env (matching every other
  // config field's existing behavior in this file), not off the `env`
  // param loadConfig was called with - so exercise it the same way section
  // 1g does for BRAIN_DECISIONS_MAX_BYTES.
  process.env.BRAIN_UPDATE_CHECK_SEC = "0";
  assert(loadConfig().updateCheckSec === 0, "BRAIN_UPDATE_CHECK_SEC=0 is honored (disables self-update)");
  delete process.env.BRAIN_UPDATE_CHECK_SEC;

  // --- BRAIN_UPDATE_CHECK_MIN fallback (minutes -> seconds) when the new
  // seconds-based var is unset ---
  process.env.BRAIN_UPDATE_CHECK_MIN = "2";
  assert(loadConfig().updateCheckSec === 120, "BRAIN_UPDATE_CHECK_MIN=2 falls back to 120 seconds when BRAIN_UPDATE_CHECK_SEC is unset");
  delete process.env.BRAIN_UPDATE_CHECK_MIN;

  // --- BRAIN_UPDATE_CHECK_SEC wins when both are set ---
  process.env.BRAIN_UPDATE_CHECK_SEC = "5";
  process.env.BRAIN_UPDATE_CHECK_MIN = "2";
  assert(loadConfig().updateCheckSec === 5, "BRAIN_UPDATE_CHECK_SEC wins over BRAIN_UPDATE_CHECK_MIN when both are set");
  delete process.env.BRAIN_UPDATE_CHECK_SEC;
  delete process.env.BRAIN_UPDATE_CHECK_MIN;

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

  console.log("\n1e. Logger human-readable formatting (pure, no process needed)");
  const toolLine = formatHumanLine("info", "tool_call", {
    role: "director",
    tool: "set_block",
    argsLine: compactArgs({ x: -3, y: 98, z: -60, material: "lightning_rod" }),
  });
  assert(toolLine.includes("⚒ set_block"), "tool-call line renders the tool name behind the ⚒ marker");
  assert(toolLine.includes("x:-3"), "tool-call line renders compact args inline on the same line");
  assert(/^\d{2}:\d{2}:\d{2}\s+INFO/.test(toolLine), "tool-call line is prefixed with an HH:MM:SS INFO header");

  const summaryLine = formatHumanLine("info", "director_scene_summary", {
    sceneNumber: 12,
    summary: "Nothing happened; the village slept.",
  });
  assert(
    summaryLine.includes("✓ scene #12 decided: Nothing happened; the village slept."),
    "scene-summary line renders the scene number and the full decision text"
  );

  const sceneStartLine = formatHumanLine("info", "director_scene_starting", { sceneNumber: 12, batchSize: 2, events: ["player_join", "player_chat"] });
  assert(sceneStartLine.includes("scene #12 starting (2 events)"), "scene-start line renders the scene number and event count");

  const chatLine = formatHumanLine("info", "event_received", { event: "player_chat", data: { player: "AlexCfer", message: "Hello" } });
  assert(chatLine.includes('event player_chat AlexCfer: "Hello"'), "player_chat event renders as a readable narrative line");

  console.log("\n1f. Decisions journal writes a block for a dry-run scene, marked [dry-run]");
  const journalStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-journal-"));
  journalScene({
    config: { stateDir: journalStateDir },
    sceneNumber: 1,
    batch: [{ event: "player_chat", data: { player: "Steve", message: "hi" } }],
    toolCalls: [{ tool: "npc_say", args: { npcId: "mara-baker", text: "Welcome!" } }],
    summary: null,
    dryRun: true,
  });
  const journalFile = path.join(journalStateDir, "decisions.log");
  assert(fs.existsSync(journalFile), "decisions.log was created under state/");
  const journalContent = fs.readFileSync(journalFile, "utf8");
  assert(journalContent.includes("[dry-run]"), "dry-run scene block is marked [dry-run]");
  assert(journalContent.includes("Scene #1"), "journal block is labeled with the scene number");
  assert(journalContent.includes("npc_say"), "journal block lists the tool call the scene made");
  assert(journalContent.includes("Steve"), "journal block names the triggering player");

  console.log("\n1g. Decisions journal rotates at a size threshold");
  const rotateStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-journal-rotate-"));
  process.env.BRAIN_DECISIONS_MAX_BYTES = "300"; // tiny threshold override, test-only
  for (let i = 0; i < 10; i++) {
    journalScene({
      config: { stateDir: rotateStateDir },
      sceneNumber: i,
      batch: [{ event: "npc_death", data: {} }],
      toolCalls: [],
      summary: "the bell tolled for nobody in particular, over and over again",
      dryRun: false,
    });
  }
  delete process.env.BRAIN_DECISIONS_MAX_BYTES;
  assert(fs.existsSync(path.join(rotateStateDir, "decisions.log.1")), "decisions.log rotated to decisions.log.1 once the size threshold was exceeded");
  assert(fs.existsSync(path.join(rotateStateDir, "decisions.log")), "a fresh decisions.log exists after rotation");

  console.log("\n1h. Self-update decision logic (offline, injectable runner - no real git/npm calls)");

  /** Builds a fake `runner(cmd, args, opts)` for SelfUpdater out of an
   * ordered list of {match(cmd,args), result} entries; result is either an
   * {stdout,stderr} object to resolve with, or an Error instance to throw
   * (mirroring what execFile-based failures look like: .stderr attached).
   * Every call is recorded on `runner.calls` so tests can assert on what
   * was (or wasn't) invoked - e.g. "npm install never ran". */
  function fakeRunner(responses) {
    const calls = [];
    const runner = async (cmd, args, opts) => {
      calls.push({ cmd, args, cwd: opts && opts.cwd });
      const hit = responses.find((r) => r.match(cmd, args));
      if (!hit) throw new Error(`fakeRunner: unexpected call ${cmd} ${args.join(" ")}`);
      if (hit.result instanceof Error) throw hit.result;
      return hit.result;
    };
    runner.calls = calls;
    return runner;
  }
  const has = (args, ...needle) => needle.every((n) => args.includes(n));
  const ok = (stdout = "") => ({ stdout, stderr: "" });
  const fail = (stderr) => Object.assign(new Error(stderr), { stderr });

  // --- same hash -> no action, no pull, no restart ---
  {
    const runner = fakeRunner([
      { match: (c, a) => c === "git" && has(a, "rev-parse", "--is-inside-work-tree"), result: ok() },
      { match: (c, a) => c === "git" && has(a, "rev-parse", "HEAD"), result: ok("abc123\n") },
      { match: (c, a) => c === "git" && has(a, "ls-remote"), result: ok("abc123\trefs/heads/main\n") },
    ]);
    let restarted = false;
    const su = new SelfUpdater({ checkIntervalSec: 10, runner, restart: () => { restarted = true; } });
    const result = await su.checkOnce();
    assert(result.action === "up_to_date", `same local/remote HEAD -> up_to_date (got ${result.action})`);
    assert(!restarted, "same hash never triggers a restart");
    assert(!runner.calls.some((c) => c.args.includes("pull")), "same hash never runs git pull");
  }

  // --- different hash, lockfile unchanged -> pull + restart, npm install SKIPPED ---
  {
    const runner = fakeRunner([
      { match: (c, a) => c === "git" && has(a, "rev-parse", "--is-inside-work-tree"), result: ok() },
      { match: (c, a) => c === "git" && has(a, "rev-parse", "HEAD"), result: ok("abc111\n") },
      { match: (c, a) => c === "git" && has(a, "ls-remote"), result: ok("def222\trefs/heads/main\n") },
      { match: (c, a) => c === "git" && has(a, "diff", "--name-only"), result: ok("brain/index.mjs\nbrain/README.md\n") },
      { match: (c, a) => c === "git" && has(a, "pull", "--ff-only"), result: ok() },
      { match: (c) => c === "npm", result: ok() },
    ]);
    let restarted = false;
    const su = new SelfUpdater({ checkIntervalSec: 10, runner, restart: () => { restarted = true; } });
    const result = await su.checkOnce();
    assert(result.action === "restarted", `different HEAD with a clean pull -> restarted (got ${result.action})`);
    assert(restarted, "restart callback was invoked after a successful update");
    assert(runner.calls.some((c) => c.cmd === "git" && c.args.includes("pull")), "git pull --ff-only was run");
    assert(!runner.calls.some((c) => c.cmd === "npm"), "npm install was SKIPPED because package-lock.json did not change");
    assert(result.npmInstalled === false, "checkOnce reports npmInstalled=false when the lock file was unchanged");
  }

  // --- different hash, lockfile changed -> pull + npm install + restart ---
  {
    const runner = fakeRunner([
      { match: (c, a) => c === "git" && has(a, "rev-parse", "--is-inside-work-tree"), result: ok() },
      { match: (c, a) => c === "git" && has(a, "rev-parse", "HEAD"), result: ok("abc111\n") },
      { match: (c, a) => c === "git" && has(a, "ls-remote"), result: ok("def222\trefs/heads/main\n") },
      { match: (c, a) => c === "git" && has(a, "diff", "--name-only"), result: ok("brain/package.json\nbrain/package-lock.json\n") },
      { match: (c, a) => c === "git" && has(a, "pull", "--ff-only"), result: ok() },
      { match: (c) => c === "npm", result: ok() },
    ]);
    let restarted = false;
    const su = new SelfUpdater({ checkIntervalSec: 10, runner, restart: () => { restarted = true; } });
    const result = await su.checkOnce();
    assert(result.action === "restarted", `different HEAD with a lockfile change -> restarted (got ${result.action})`);
    assert(restarted, "restart callback was invoked after a successful update with a lock change");
    const npmCall = runner.calls.find((c) => c.cmd === "npm");
    assert(npmCall, "npm install WAS run because package-lock.json changed in the pulled range");
    assert(npmCall && has(npmCall.args, "install", "--no-audit", "--no-fund"), "npm install was run with --no-audit --no-fund");
    assert(result.npmInstalled === true, "checkOnce reports npmInstalled=true when the lock file changed");
  }

  // --- pull failure (dirty tree / diverged) -> warning, no restart, no broken state ---
  {
    const runner = fakeRunner([
      { match: (c, a) => c === "git" && has(a, "rev-parse", "--is-inside-work-tree"), result: ok() },
      { match: (c, a) => c === "git" && has(a, "rev-parse", "HEAD"), result: ok("abc111\n") },
      { match: (c, a) => c === "git" && has(a, "ls-remote"), result: ok("def222\trefs/heads/main\n") },
      { match: (c, a) => c === "git" && has(a, "diff", "--name-only"), result: ok("brain/index.mjs\n") },
      { match: (c, a) => c === "git" && has(a, "pull", "--ff-only"), result: fail("error: Your local changes would be overwritten by merge") },
    ]);
    let restarted = false;
    const su = new SelfUpdater({ checkIntervalSec: 10, runner, restart: () => { restarted = true; } });
    const result = await su.checkOnce();
    assert(result.action === "pull_failed", `a failed git pull --ff-only -> pull_failed, not thrown (got ${result.action})`);
    assert(!restarted, "restart is never triggered when the pull failed");
    assert(!runner.calls.some((c) => c.cmd === "npm"), "npm install never runs when the pull failed");
  }

  // --- not a git checkout -> disabled cleanly, no further git calls ---
  {
    const runner = fakeRunner([
      { match: (c, a) => c === "git" && has(a, "rev-parse", "--is-inside-work-tree"), result: fail("fatal: not a git repository") },
    ]);
    let restarted = false;
    const su = new SelfUpdater({ checkIntervalSec: 10, runner, restart: () => { restarted = true; } });
    const result = await su.checkOnce();
    assert(result.action === "disabled_not_git", `a non-git brain/.. -> disabled_not_git, not thrown (got ${result.action})`);
    assert(!restarted, "restart is never triggered when brain/.. is not a git checkout");
    assert(runner.calls.length === 1, "non-git dir stops after the single is-inside-work-tree probe, no further git calls");
  }

  // --- checkIntervalSec <= 0 disables the timer entirely (start() never schedules) ---
  {
    const suDisabled = new SelfUpdater({ checkIntervalSec: 0, runner: fakeRunner([]) });
    suDisabled.start();
    assert(suDisabled.timer === null, "checkIntervalSec=0 never starts the periodic timer");
    suDisabled.stop();
  }

  // --- reentrancy guard at a short (10s-analog) interval: a check slower
  // than the tick interval must never overlap with itself, i.e. the
  // setInterval callback firing mid-check is dropped, not queued. Uses a
  // real timer (short interval) with a runner that intentionally outlasts
  // it, and counts concurrent in-flight ls-remote calls. ---
  {
    let concurrentLsRemote = 0;
    let maxConcurrentLsRemote = 0;
    let lsRemoteCalls = 0;
    const slowRunner = async (cmd, args, opts) => {
      if (cmd === "git" && args.includes("rev-parse") && args.includes("--is-inside-work-tree")) {
        return { stdout: "", stderr: "" };
      }
      if (cmd === "git" && args.includes("rev-parse") && args.includes("HEAD")) {
        return { stdout: "abc123\n", stderr: "" };
      }
      if (cmd === "git" && args.includes("ls-remote")) {
        lsRemoteCalls += 1;
        concurrentLsRemote += 1;
        maxConcurrentLsRemote = Math.max(maxConcurrentLsRemote, concurrentLsRemote);
        // Deliberately slower than the timer's own interval below, so a
        // second tick fires while this one is still "in flight".
        await new Promise((resolve) => setTimeout(resolve, 120));
        concurrentLsRemote -= 1;
        return { stdout: "abc123\trefs/heads/main\n", stderr: "" }; // same hash - no pull/restart needed
      }
      throw new Error(`slowRunner: unexpected call ${cmd} ${args.join(" ")}`);
    };
    const suReentrant = new SelfUpdater({ checkIntervalSec: 0.05, runner: slowRunner }); // 50ms ticks
    suReentrant.start();
    await new Promise((resolve) => setTimeout(resolve, 400)); // several 50ms ticks across two ~120ms checks
    suReentrant.stop();
    assert(maxConcurrentLsRemote <= 1, `overlapping ticks never run two checks concurrently (max concurrent ls-remote calls: ${maxConcurrentLsRemote})`);
    assert(lsRemoteCalls >= 2, `a slow check (120ms) still gets picked up again after it finishes, across a 400ms window of 50ms ticks (got ${lsRemoteCalls} checks)`);
    assert(lsRemoteCalls < 8, `overlapping ticks are skipped, not queued - far fewer checks ran than the ~8 ticks that fired in 400ms (got ${lsRemoteCalls})`);
  }

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
    // The default console output is now human-readable prose (see
    // lib/logger.mjs); this smoke test parses JSON lines, so it opts back
    // into the full-JSON mode exactly like an operator running `| jq` would.
    BRAIN_LOG_JSON: "1",
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
    BRAIN_LOG_JSON: "1",
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
    BRAIN_LOG_JSON: "1",
  });

  const sawTwoRetries = await waitFor(
    brain3.logs,
    () => brain3.logs.filter((l) => l.msg === "bridge_reconnect_scheduled").length >= 2,
    3500
  );
  assert(sawTwoRetries, "at least 2 reconnect attempts were logged within 3.5s");
  assert(brain3.child.exitCode === null, "brain process is still running after repeated connect failures");

  brain3.child.kill();

  console.log("\n5. Lore Console: auth, directive add/hot-reload/list, delete, GET off by default");
  const port4 = 8902;
  const consoleToken = "console-test-token";
  const bridge4 = spawnNode([path.join(BRAIN_DIR, "test", "mock-bridge.mjs")], {
    MOCK_BRIDGE_PORT: String(port4),
    MOCK_BRIDGE_TOKEN: "test-token",
  });
  await wait(300);

  const consoleLoreDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-console-lore-"));
  const brain4 = spawnNode([path.join(BRAIN_DIR, "index.mjs")], {
    MCALIVE2_URL: `ws://127.0.0.1:${port4}`,
    MCALIVE2_TOKEN: "test-token",
    BRAIN_DEBOUNCE_MS: "300",
    BRAIN_DRY_RUN: "1",
    BRAIN_ENABLED: "1",
    BRAIN_LORE_REFRESH_MS: "600000",
    BRAIN_LORE_DIR: consoleLoreDir,
    BRAIN_STATE_DIR: fs.mkdtempSync(path.join(os.tmpdir(), "brain-console-state-")),
    BRAIN_LOG_JSON: "1",
    BRAIN_CONSOLE: "1",
    BRAIN_CONSOLE_BIND: "127.0.0.1",
    BRAIN_CONSOLE_PORT: "0", // ephemeral - read the actual bound port back from the console_listening log line
    BRAIN_CONSOLE_TOKEN: consoleToken,
  });

  const authed4 = await waitFor(brain4.logs, (l) => l.msg === "bridge_auth_ok", 5000);
  assert(authed4, "console-test brain authenticated against the bridge");

  const gotListening = await waitFor(brain4.logs, (l) => l.msg === "console_listening", 5000);
  assert(gotListening, "console_listening was logged at startup");
  const listening = brain4.logs.find((l) => l.msg === "console_listening");
  const consolePort = listening && listening.port;
  assert(typeof consolePort === "number" && consolePort > 0, `console_listening logged an actual bound port (got ${consolePort})`);

  const base = `http://127.0.0.1:${consolePort}`;

  const unauthed = await fetch(`${base}/`);
  assert(unauthed.status === 401, `GET / with no token is rejected (got ${unauthed.status})`);
  const unauthedBody = await unauthed.text();
  assert(unauthedBody.includes("?token="), "401 page tells the operator to append ?token=YOUR-TOKEN");

  // Deliberately NOT the same wording as the page's textarea placeholder
  // example, so "page still contains directiveText" is an unambiguous
  // signal about the directives list, not a false positive off the
  // placeholder text.
  const directiveText = "Add a sunken shrine east of the river, guarded by a retired sellsword who remembers the old war.";
  const postRes = await fetch(`${base}/directive?token=${consoleToken}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ text: directiveText }),
  });
  const postJson = await postRes.json();
  assert(postRes.status === 200 && postJson.ok === true, `POST /directive with a valid token succeeds (got ${postRes.status} ${JSON.stringify(postJson)})`);
  assert(typeof postJson.timestamp === "string" && postJson.timestamp.length > 0, "POST /directive returns the new block's timestamp");

  const directivesFile = path.join(consoleLoreDir, "90-operator-directives.md");
  assert(fs.existsSync(directivesFile), "lore/90-operator-directives.md was created");
  const directivesFileText = fs.readFileSync(directivesFile, "utf8");
  assert(directivesFileText.includes("Operator Directives"), "directives file carries its explanatory header on first use");
  assert(directivesFileText.includes(directiveText), "directives file contains the appended directive text");

  const gotHotReload = await waitFor(
    brain4.logs,
    (l) => l.msg === "dry_run_director_turn" && typeof l.systemPrompt === "string" && l.systemPrompt.includes(directiveText),
    5000
  );
  assert(gotHotReload, "a director scene's dry-run system prompt contains the directive text (hot-reload path, no restart needed)");

  const pageRes = await fetch(`${base}/?token=${consoleToken}`);
  const pageHtml = await pageRes.text();
  assert(pageRes.status === 200, `GET / with a valid token succeeds (got ${pageRes.status})`);
  assert(pageHtml.includes(directiveText), "GET / page HTML lists the directive text");
  assert(pageHtml.includes("Send to the world"), "GET / page has the directive textarea/button");

  const decisionsRes = await fetch(`${base}/decisions?token=${consoleToken}`);
  assert(decisionsRes.status === 200, `GET /decisions with a valid token succeeds (got ${decisionsRes.status})`);

  const deleteRes = await fetch(`${base}/directive/delete?token=${consoleToken}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ timestamp: postJson.timestamp }),
  });
  const deleteJson = await deleteRes.json();
  assert(deleteRes.status === 200 && deleteJson.ok === true, `POST /directive/delete removes the block (got ${deleteRes.status} ${JSON.stringify(deleteJson)})`);
  const afterDelete = fs.readFileSync(directivesFile, "utf8");
  assert(!afterDelete.includes(directiveText), "directive text is gone from the file after delete");

  const pageAfterDelete = await fetch(`${base}/?token=${consoleToken}`);
  const pageAfterDeleteHtml = await pageAfterDelete.text();
  assert(!pageAfterDeleteHtml.includes(directiveText), "GET / page no longer lists the deleted directive");

  brain4.child.kill();
  bridge4.child.kill();

  console.log("\n6. Lore Console does not start when BRAIN_CONSOLE=0");
  const port5 = 8903;
  const bridge5 = spawnNode([path.join(BRAIN_DIR, "test", "mock-bridge.mjs")], {
    MOCK_BRIDGE_PORT: String(port5),
    MOCK_BRIDGE_TOKEN: "test-token",
  });
  await wait(300);

  const brain5 = spawnNode([path.join(BRAIN_DIR, "index.mjs")], {
    MCALIVE2_URL: `ws://127.0.0.1:${port5}`,
    MCALIVE2_TOKEN: "test-token",
    BRAIN_DEBOUNCE_MS: "300",
    BRAIN_DRY_RUN: "1",
    BRAIN_ENABLED: "1",
    BRAIN_LORE_REFRESH_MS: "600000",
    BRAIN_STATE_DIR: fs.mkdtempSync(path.join(os.tmpdir(), "brain-console-off-")),
    BRAIN_LOG_JSON: "1",
    BRAIN_CONSOLE: "0",
  });
  const authed5 = await waitFor(brain5.logs, (l) => l.msg === "bridge_auth_ok", 5000);
  assert(authed5, "BRAIN_CONSOLE=0 brain instance still authenticates against the bridge normally");
  await wait(500);
  assert(!brain5.logs.some((l) => l.msg === "console_listening"), "no console_listening log line when BRAIN_CONSOLE=0");

  brain5.child.kill();
  bridge5.child.kill();

  await wait(200);

  console.log(`\n${failures === 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("smoke test crashed:", e);
  process.exit(1);
});
