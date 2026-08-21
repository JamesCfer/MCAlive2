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
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import { namespacedTool, MCP_SERVER_NAME, ALL_TOOLS, ACTOR_TOOLS, actorDisallowedTools, DIRECTOR_WAKE_EVENTS, loadConfig } from "../lib/config.mjs";
import { parseActorReport } from "../lib/actor-report.mjs";
import { ActorMemory } from "../lib/actor-memory.mjs";
import { formatHumanLine, compactArgs, log } from "../lib/logger.mjs";
import { journalScene } from "../lib/decisions-journal.mjs";
import { SelfUpdater } from "../lib/self-update.mjs";
import { runDirectorTurn, maxTurnsFor, maxTokensFor, buildSystemPrompt } from "../lib/director-turn.mjs";
import { appendEntry as chronicleAppend, readFile as chronicleReadFile, rewriteFile as chronicleRewrite, digest as chronicleDigest, listSessions, todaySessionDate } from "../lib/chronicle.mjs";
import { runActorTurn } from "../lib/actor-turn.mjs";
import { DirectorScheduler } from "../lib/director-scheduler.mjs";
import { buildWorldModel, formatWorldOverview } from "../lib/worldmodel.mjs";
import { PositionCache } from "../lib/position-cache.mjs";
import { capToolResultText, resolveMaxToolResultChars } from "../lib/tool-result-cap.mjs";

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

/** Generic poll: waits until `predicate()` is truthy or `timeoutMs` elapses.
 * Used by in-process tests (no spawned child, so no log array to scan). */
async function waitForCondition(predicate, timeoutMs) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (predicate()) return true;
    await wait(20);
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
  assert(DIRECTOR_WAKE_EVENTS.has("operator_order"), "operator_order is a director wake event (Lore Console orders)");
  assert(DIRECTOR_WAKE_EVENTS.has("sequence_done"), "sequence_done is a director wake event (plugin-side timed sequence finished)");

  console.log("\n1a². Operator-order world tools: present in ALL_TOOLS, absent from ACTOR_TOOLS");
  for (const t of ["create_explosion", "strike_lightning", "move_region"]) {
    assert(ALL_TOOLS.includes(t), `${t} is in ALL_TOOLS`);
    assert(!ACTOR_TOOLS.includes(t), `${t} is NOT in ACTOR_TOOLS (director-only by omission)`);
    assert(actorDisallowedTools(MCP_SERVER_NAME).includes(namespacedTool(t, MCP_SERVER_NAME)), `${t} is in the actor disallow-list`);
  }

  console.log("\n1a².5. Formula + NPC job tools: present in ALL_TOOLS, absent from ACTOR_TOOLS");
  for (const t of ["formula_define", "formula_run", "formula_list", "formula_get", "formula_delete", "npc_assign_job", "npc_job_cancel"]) {
    assert(ALL_TOOLS.includes(t), `${t} is in ALL_TOOLS`);
    assert(!ACTOR_TOOLS.includes(t), `${t} is NOT in ACTOR_TOOLS (director-only by omission)`);
    assert(actorDisallowedTools(MCP_SERVER_NAME).includes(namespacedTool(t, MCP_SERVER_NAME)), `${t} is in the actor disallow-list`);
  }
  assert(DIRECTOR_WAKE_EVENTS.has("formula_error"), "formula_error is a director wake event");
  assert(DIRECTOR_WAKE_EVENTS.has("npc_job_done"), "npc_job_done is a director wake event");
  assert(DIRECTOR_WAKE_EVENTS.has("npc_job_blocked"), "npc_job_blocked is a director wake event");

  console.log("\n1a².52. Behavior-program tools: present in ALL_TOOLS, absent from ACTOR_TOOLS, registered in mcp-bridge.mjs");
  const BEHAVIOR_TOOLS = ["behavior_create", "behavior_update", "behavior_pause", "behavior_resume",
    "behavior_delete", "behavior_status", "blueprint_register", "blueprint_delete"];
  for (const t of BEHAVIOR_TOOLS) {
    assert(ALL_TOOLS.includes(t), `${t} is in ALL_TOOLS`);
    assert(!ACTOR_TOOLS.includes(t), `${t} is NOT in ACTOR_TOOLS (director-only by omission)`);
    assert(actorDisallowedTools(MCP_SERVER_NAME).includes(namespacedTool(t, MCP_SERVER_NAME)), `${t} is in the actor disallow-list`);
  }
  assert(DIRECTOR_WAKE_EVENTS.has("behavior_done"), "behavior_done is a director wake event");
  assert(DIRECTOR_WAKE_EVENTS.has("behavior_blocked"), "behavior_blocked is a director wake event");
  {
    const bridgeSrcForBehavior = fs.readFileSync(path.join(BRAIN_DIR, "mcp-bridge.mjs"), "utf8");
    for (const t of BEHAVIOR_TOOLS) {
      assert(new RegExp(`pt\\("${t}"`).test(bridgeSrcForBehavior), `mcp-bridge.mjs registers a ${t} tool`);
    }
    const behaviorCreateSrc = bridgeSrcForBehavior.slice(bridgeSrcForBehavior.indexOf('pt("behavior_create"'));
    assert(/npcIds: z\.array\(z\.string\(\)\)/.test(behaviorCreateSrc), "behavior_create schema accepts an npcIds crew");
    assert(/steps: z\.array\(behaviorStep\)/.test(behaviorCreateSrc), "behavior_create schema accepts a steps list");
    for (const stepType of ["gather", "build", "deposit", "withdraw", "work_station", "goto", "wait", "follow"]) {
      assert(new RegExp(`z\\.literal\\("${stepType}"\\)`).test(bridgeSrcForBehavior), `behaviorStep union declares the ${stepType} step type`);
    }
    assert(/blocks: z\.array\(z\.object\(\{\s*\n\s*dx: z\.number\(\), dy: z\.number\(\), dz: z\.number\(\)/.test(bridgeSrcForBehavior), "blueprint_register schema takes {dx,dy,dz,material[,data]} blocks");
  }

  console.log("\n1a².55. Gadget tools: present in ALL_TOOLS, absent from ACTOR_TOOLS, schema declared in mcp-bridge.mjs");
  for (const t of ["gadget_define", "gadget_run", "gadget_list", "gadget_get", "gadget_delete"]) {
    assert(ALL_TOOLS.includes(t), `${t} is in ALL_TOOLS`);
    assert(!ACTOR_TOOLS.includes(t), `${t} is NOT in ACTOR_TOOLS (director-only by omission)`);
    assert(actorDisallowedTools(MCP_SERVER_NAME).includes(namespacedTool(t, MCP_SERVER_NAME)), `${t} is in the actor disallow-list`);
  }
  {
    const bridgeSrcForGadgets = fs.readFileSync(path.join(BRAIN_DIR, "mcp-bridge.mjs"), "utf8");
    assert(/pt\("gadget_define"/.test(bridgeSrcForGadgets), "mcp-bridge.mjs registers a gadget_define tool");
    const gadgetDefineSrc = bridgeSrcForGadgets.slice(bridgeSrcForGadgets.indexOf('pt("gadget_define"'), bridgeSrcForGadgets.indexOf('pt("gadget_run"'));
    assert(/id: z\.string\(\)/.test(gadgetDefineSrc), "gadget_define schema accepts id");
    assert(/source: z\.string\(\)/.test(gadgetDefineSrc), "gadget_define schema accepts source");
    assert(/description: z\.string\(\)/.test(gadgetDefineSrc), "gadget_define schema accepts description");
  }

  console.log("\n1a².6. Placement-safety tools/args: scan_area in ALL_TOOLS (director-only), build_blueprint/npc_spawn/npc_walk_to schemas carry the new args");
  assert(ALL_TOOLS.includes("scan_area"), "scan_area is in ALL_TOOLS");
  assert(!ACTOR_TOOLS.includes("scan_area"), "scan_area is NOT in ACTOR_TOOLS (director-only by omission)");
  assert(actorDisallowedTools(MCP_SERVER_NAME).includes(namespacedTool("scan_area", MCP_SERVER_NAME)), "scan_area is in the actor disallow-list");

  // mcp-bridge.mjs registers its tool schemas at module load time via a
  // top-level `await server.connect(transport)` over stdio, so importing it
  // directly here would hang the test process waiting for a client. Assert
  // against the source text instead - it is a thin, hand-written zod schema
  // file, so a substring check on the shape declarations is a faithful proxy
  // for "the schema accepts this field".
  const bridgeSrc = fs.readFileSync(path.join(BRAIN_DIR, "mcp-bridge.mjs"), "utf8");
  assert(/pt\("scan_area"/.test(bridgeSrc), "mcp-bridge.mjs registers a scan_area tool");
  assert(/x1: z\.number\(\), z1: z\.number\(\), x2: z\.number\(\), z2: z\.number\(\),\s*\n\s*world: z\.string\(\)\.optional\(\),\s*\n\s*yHint: z\.number\(\)\.optional\(\)/.test(bridgeSrc), "scan_area schema accepts x1/z1/x2/z2/world/yHint");
  const buildBlueprintSrc = bridgeSrc.slice(bridgeSrc.indexOf('pt("build_blueprint"'), bridgeSrc.indexOf('pt("sample_terrain"'));
  assert(/settle: z\.enum\(\["surface"\]\)\.optional\(\)/.test(buildBlueprintSrc), "build_blueprint schema accepts settle:\"surface\"");
  assert(/clearAbove: z\.boolean\(\)\.optional\(\)/.test(buildBlueprintSrc), "build_blueprint schema accepts clearAbove");
  const npcSpawnSrc = bridgeSrc.slice(bridgeSrc.indexOf('pt("npc_spawn"'), bridgeSrc.indexOf('pt("npc_update"'));
  assert(/snap: z\.boolean\(\)\.optional\(\)/.test(npcSpawnSrc), "npc_spawn schema accepts snap");
  const npcWalkToSrc = bridgeSrc.slice(bridgeSrc.indexOf('pt("npc_walk_to"'), bridgeSrc.indexOf('pt("npc_look_at"'));
  assert(/snap: z\.boolean\(\)\.optional\(\)/.test(npcWalkToSrc), "npc_walk_to schema accepts snap");

  console.log("\n1a³. Order scenes get the higher BRAIN_ORDER_MAX_STEPS turn ceiling");
  const cfgDefault = loadConfig({});
  assert(cfgDefault.orderMaxSteps === 80, `BRAIN_ORDER_MAX_STEPS defaults to 80 (got ${cfgDefault.orderMaxSteps})`);
  const normalBatch = [{ event: "player_join", data: {}, at: new Date().toISOString() }];
  const orderBatch = [{ event: "operator_order", data: { text: "strike spawn with lightning" }, at: new Date().toISOString() }];
  assert(maxTurnsFor(normalBatch, cfgDefault) === cfgDefault.maxDirectorSteps, "a normal scene's maxTurns is the regular maxDirectorSteps");
  assert(maxTurnsFor(orderBatch, cfgDefault) === cfgDefault.orderMaxSteps, "an operator_order scene's maxTurns is the higher orderMaxSteps");
  process.env.BRAIN_ORDER_MAX_STEPS = "33";
  assert(loadConfig().orderMaxSteps === 33, "BRAIN_ORDER_MAX_STEPS is honored when set");
  delete process.env.BRAIN_ORDER_MAX_STEPS;

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

  console.log("\n1a⁴. World model: schema present, director-only tool registration");
  assert(ALL_TOOLS.includes("world_overview"), "world_overview is in ALL_TOOLS");
  assert(!ACTOR_TOOLS.includes("world_overview"), "world_overview is NOT in ACTOR_TOOLS (director-only by omission)");
  assert(actorDisallowedTools(MCP_SERVER_NAME).includes(namespacedTool("world_overview", MCP_SERVER_NAME)), "world_overview is in the actor disallow-list");
  {
    const bridgeSrcForWorldModel = fs.readFileSync(path.join(BRAIN_DIR, "mcp-bridge.mjs"), "utf8");
    assert(/registerTool\("world_overview"/.test(bridgeSrcForWorldModel), "mcp-bridge.mjs registers a world_overview tool");
    assert(/from "\.\/lib\/worldmodel\.mjs"/.test(bridgeSrcForWorldModel), "mcp-bridge.mjs imports lib/worldmodel.mjs");
  }
  {
    const directorTurnSrc = fs.readFileSync(path.join(BRAIN_DIR, "lib", "director-turn.mjs"), "utf8");
    assert(/world_overview/.test(directorTurnSrc) && /PROBLEMS/.test(directorTurnSrc), "director briefing (BUILDING DISCIPLINE) tells the director to call world_overview and check the PROBLEMS it reports");
  }

  console.log("\n1a⁵. World model: buildWorldModel() against a fake bridge call() produces the pinned shape, with a floating-place diagnostic");
  {
    // Canned data standing in for the plugin bridge: one place planted 10
    // blocks above the scanned ground (an "ai"-built structure -> should
    // trip the "floating" error diagnostic), one grounded place, one NPC
    // with a ledger `home` (no live-position command exists) and one dead
    // NPC with no home at all (-> "no-position" warning).
    const fakeCall = async (cmd, args) => {
      if (cmd === "ledger_query" && args.collection === "places") {
        return {
          records: [
            { id: "sky-tower", name: "Sky Tower", kind: "tower", origin: { x: 10, y: 80, z: 10 }, bounds: { x1: 8, y1: 80, z1: 8, x2: 12, y2: 90, z2: 12 }, builtBy: "ai" },
            { id: "old-well", name: "Old Well", kind: "well", origin: { x: -5, y: 64, z: -5 }, builtBy: "world" },
          ],
        };
      }
      if (cmd === "ledger_query" && args.collection === "npcs") {
        return {
          records: [
            { id: "mara-baker", name: "Mara the Baker", home: { x: -5, y: 65, z: -5 }, alive: true },
            { id: "ghost-npc", name: "Ghostly NPC", alive: true }, // no home -> no-position
            { id: "sella", name: "Sella", home: { x: 0, y: 64, z: 0 }, alive: false }, // dead
          ],
        };
      }
      if (cmd === "scan_area") {
        // Mirrors the real TerrainSampler.scanArea: <=256 requested columns
        // get every column back inline, larger areas get a fixed 8x8=64
        // downsampled grid instead - exercising the SAME branch
        // buildWorldModel's terrainFromScan() has to handle for a real,
        // multi-place bounding box (which comfortably exceeds 256 columns).
        // Flat ground at y=64 everywhere: the tower's bounds-bottom (y=80)
        // sits 16 blocks above it - well past the 3-block floating cap.
        const requestedCols = (args.x2 - args.x1 + 1) * (args.z2 - args.z1 + 1);
        const points = [];
        if (requestedCols <= 256) {
          for (let x = args.x1; x <= args.x2; x++) {
            for (let z = args.z1; z <= args.z2; z++) points.push({ x, z, y: 64, material: "grass_block" });
          }
          return { minY: 64, maxY: 64, medianY: 64, flat: true, columns: points };
        }
        for (let gx = 0; gx < 8; gx++) {
          const x = args.x1 + Math.round((args.x2 - args.x1) * gx / 7);
          for (let gz = 0; gz < 8; gz++) {
            const z = args.z1 + Math.round((args.z2 - args.z1) * gz / 7);
            points.push({ x, z, y: 64, material: "grass_block" });
          }
        }
        return { minY: 64, maxY: 64, medianY: 64, flat: true, grid: points };
      }
      if (cmd === "get_block") {
        // Mara stands right on top of solid ground at her home y=65 (her
        // feet at y=65 are air, the block below at y=64 is grass) - never
        // flagged off-ground.
        if (args.y <= 64 && args.y >= 62) return { material: "grass_block", blockData: "minecraft:grass_block" };
        return { material: "air", blockData: "minecraft:air" };
      }
      throw new Error(`fakeCall: unexpected command ${cmd}`);
    };

    const model = await buildWorldModel(fakeCall, { now: "2026-01-01T00:00:00.000Z" });

    // --- pinned top-level shape ---
    assert(model.generatedAt === "2026-01-01T00:00:00.000Z", "generatedAt is exactly the injected `now` (buildWorldModel never calls Date itself)");
    assert(model.bounds && ["minX", "minZ", "maxX", "maxZ", "minY", "maxY"].every((k) => typeof model.bounds[k] === "number"), "bounds has all 6 numeric fields");
    assert(Array.isArray(model.places) && Array.isArray(model.npcs) && Array.isArray(model.diagnostics), "places/npcs/diagnostics are arrays");
    assert(model.terrain && Array.isArray(model.terrain.heights) && typeof model.terrain.cols === "number" && typeof model.terrain.rows === "number", "terrain has heights/cols/rows");
    assert(model.terrain.cols * model.terrain.rows <= 1024, `terrain grid stays within the ~1024-cell coarse cap (got ${model.terrain.cols}x${model.terrain.rows})`);

    const place = model.places.find((p) => p.id === "sky-tower");
    assert(place && place.name === "Sky Tower" && place.builtBy === "ai", "sky-tower place record maps id/name/builtBy through");
    assert(place && place.bounds && place.bounds.x1 === 8, "sky-tower place record carries its bounds through");

    // --- floating-place diagnostic ---
    const floatingDiag = model.diagnostics.find((d) => d.kind === "floating");
    assert(floatingDiag, "a 'floating' diagnostic was produced for the ai-built place planted 16 blocks above the scanned ground");
    assert(floatingDiag && floatingDiag.severity === "error", "the floating diagnostic is severity 'error'");
    assert(floatingDiag && floatingDiag.subject === "sky-tower", "the floating diagnostic names sky-tower as its subject");
    assert(floatingDiag && place.flags.includes("floating"), "the floating place record itself carries a 'floating' flag");
    assert(!model.diagnostics.some((d) => d.kind === "floating" && d.subject === "old-well"), "the grounded (non-ai) old-well place does NOT get a floating diagnostic");

    // --- NPC fallback position + no-position diagnostic ---
    const mara = model.npcs.find((n) => n.id === "mara-baker");
    assert(mara && mara.pos && mara.pos.x === -5 && mara.pos.y === 65, "mara's position falls back to her ledger `home` coordinate");
    assert(mara && mara.flags.some((f) => /no live-position/.test(f)), "mara's fallback position is flagged as not being a live read");
    assert(!model.diagnostics.some((d) => d.kind === "off-ground" && d.subject === "mara-baker"), "mara (standing on solid ground per the fake get_block) is NOT flagged off-ground");

    const ghost = model.npcs.find((n) => n.id === "ghost-npc");
    assert(ghost && ghost.pos === null, "ghost-npc (alive, no ledger home) has a null position");
    const noPosDiag = model.diagnostics.find((d) => d.kind === "no-position" && d.subject === "ghost-npc");
    assert(noPosDiag && noPosDiag.severity === "warn", "ghost-npc produced a 'no-position' warning diagnostic");

    const sella = model.npcs.find((n) => n.id === "sella");
    assert(sella && sella.alive === false, "sella is recorded as dead");
    const deadDiag = model.diagnostics.find((d) => d.kind === "dead" && d.subject === "sella");
    assert(deadDiag && deadDiag.severity === "info", "sella (dead) produced an 'info'-severity 'dead' diagnostic");

    // --- text digest (formatWorldOverview) ---
    const digest = formatWorldOverview(model, { detail: "full" });
    assert(digest.includes("PROBLEMS"), "the text digest has a PROBLEMS section");
    assert(digest.includes("floating") && digest.includes("sky-tower") || digest.includes("Sky Tower"), "the text digest's PROBLEMS section mentions the floating sky-tower entry");
    assert(digest.includes("Sky Tower") && digest.includes("Old Well"), "the text digest lists both places by name");
    assert(/\d+ place\(s\)/.test(digest) && /alive/.test(digest) && /dead/.test(digest), "the text digest's header line has counts including alive/dead NPCs");
    const summaryDigest = formatWorldOverview(model, { detail: "summary" });
    assert(!/^[A-Z+.]{5,}$/m.test(summaryDigest.split("PROBLEMS")[0]), "detail:\"summary\" omits the ASCII map's raw grid lines");
  }

  console.log("\n1a⁶. Live position tracking: lib/position-cache.mjs stores/serves fresh vs. stale positions with an injected clock");
  {
    const cache = new PositionCache({ staleSec: 30 });

    assert(cache.npcPosition("mara-baker") === null, "an id never seen returns null, not a stale/empty object");
    assert(cache.playerPosition("Steve") === null, "same for a never-seen player name");

    const t0 = 1_000_000;
    cache.updateFromEvent(
      {
        at: t0,
        npcs: [{ id: "mara-baker", world: "world", x: 500, y: 75, z: 2 }],
        players: [{ name: "Steve", world: "world", x: 480, y: 75, z: 5 }],
      },
      t0
    );

    const fresh = cache.npcPosition("mara-baker", t0 + 5000); // 5s later - well under the 30s staleSec
    assert(fresh && fresh.x === 500 && fresh.y === 75 && fresh.z === 2, "npcPosition returns the cached x/y/z");
    assert(fresh && fresh.world === "world", "npcPosition carries the world through");
    assert(fresh && fresh.stale === false, "a position read 5s after its update, under a 30s staleSec, is NOT marked stale");

    const stalePos = cache.npcPosition("mara-baker", t0 + 31_000); // 31s later - past staleSec
    assert(stalePos && stalePos.stale === true, "the SAME position read 31s later (past staleSec=30) IS marked stale");
    assert(stalePos && stalePos.x === 500, "a stale position is still returned with its last-known coordinates, not hidden");

    const freshPlayer = cache.playerPosition("Steve", t0 + 1000);
    assert(freshPlayer && freshPlayer.x === 480 && freshPlayer.stale === false, "playerPosition mirrors npcPosition's fresh behavior");

    // A second event for the SAME npc id overwrites, doesn't accumulate.
    cache.updateFromEvent({ at: t0 + 10_000, npcs: [{ id: "mara-baker", world: "world", x: 501, y: 75, z: 3 }], players: [] }, t0 + 10_000);
    const moved = cache.npcPosition("mara-baker", t0 + 10_000);
    assert(moved && moved.x === 501 && moved.z === 3, "a later event for the same npc id overwrites its cached position rather than adding a second entry");

    // Malformed/partial events never throw.
    cache.updateFromEvent(null, t0);
    cache.updateFromEvent({}, t0);
    cache.updateFromEvent({ npcs: "not an array", players: null }, t0);
    assert(true, "updateFromEvent tolerates null/empty/malformed payloads without throwing");

    const snapshot = cache.all(t0 + 10_000);
    assert(snapshot.npcs["mara-baker"] && snapshot.players["Steve"], "all() snapshots every cached npc and player");
  }

  console.log("\n1a⁷. Live position tracking: worldmodel.mjs prefers a live position over ledger home, and flags staleness");
  {
    const npcRecordsCall = async (cmd, args) => {
      if (cmd === "ledger_query" && args.collection === "places") return { records: [] };
      if (cmd === "ledger_query" && args.collection === "npcs") {
        return {
          records: [
            { id: "mara-baker", name: "Mara the Baker", home: { x: -5, y: 65, z: -5 }, alive: true },
            { id: "kess-smith", name: "Kess the Smith", home: { x: 505, y: 75, z: -3 }, alive: true },
          ],
        };
      }
      if (cmd === "scan_area") return { minY: 60, maxY: 60, medianY: 60, flat: true, columns: [{ x: 0, z: 0, y: 60, material: "grass_block" }] };
      if (cmd === "get_block") return { material: "grass_block", blockData: "minecraft:grass_block" };
      throw new Error(`unexpected command ${cmd}`);
    };

    // mara has a FRESH live position (should win over her ledger home);
    // kess has no cached entry at all (should fall back to ledger home,
    // same as before this feature).
    const livePositions = {
      "mara-baker": { world: "world", x: 42, y: 70, z: 42, stale: false },
    };
    const modelWithLive = await buildWorldModel(npcRecordsCall, { now: "2026-01-01T00:00:00.000Z", npcPositions: livePositions });

    const maraLive = modelWithLive.npcs.find((n) => n.id === "mara-baker");
    assert(maraLive && maraLive.pos.x === 42 && maraLive.pos.y === 70 && maraLive.pos.z === 42, "mara's pos comes from the live-position getter, not her ledger home (-5,65,-5)");
    assert(maraLive && !maraLive.flags.some((f) => /ledger-home/.test(f)), "a fresh live position DROPS the position-from-ledger-home flag");
    assert(maraLive && !maraLive.flags.includes("position-stale"), "a fresh live position does not carry the position-stale flag");

    const kessFallback = modelWithLive.npcs.find((n) => n.id === "kess-smith");
    assert(kessFallback && kessFallback.pos.x === 505 && kessFallback.pos.z === -3, "kess (no cached live position) still falls back to her ledger home");
    assert(kessFallback && kessFallback.flags.some((f) => /ledger-home/.test(f)), "kess's fallback position is flagged as ledger-home, exactly as before this feature");

    // Now mara's cached entry is STALE.
    const staleLivePositions = {
      "mara-baker": { world: "world", x: 42, y: 70, z: 42, stale: true },
    };
    const modelWithStale = await buildWorldModel(npcRecordsCall, { now: "2026-01-01T00:00:00.000Z", npcPositions: staleLivePositions });
    const maraStale = modelWithStale.npcs.find((n) => n.id === "mara-baker");
    assert(maraStale && maraStale.pos.x === 42, "a stale live position is still USED (not silently dropped back to ledger home)");
    assert(maraStale && maraStale.flags.includes("position-stale"), "a stale live position adds the position-stale flag");
    assert(maraStale && !maraStale.flags.some((f) => /ledger-home/.test(f)), "a stale-but-present live position still does not fall back to (or get flagged as) ledger-home");

    // opts.npcPositions also accepts a getter function or a Map, per the
    // JSDoc - not just a plain object.
    const modelWithGetter = await buildWorldModel(npcRecordsCall, {
      now: "2026-01-01T00:00:00.000Z",
      npcPositions: (id) => (id === "mara-baker" ? { x: 7, y: 8, z: 9 } : null),
    });
    assert(modelWithGetter.npcs.find((n) => n.id === "mara-baker").pos.x === 7, "opts.npcPositions accepts a getter FUNCTION, not just a plain object");

    const modelWithMap = await buildWorldModel(npcRecordsCall, {
      now: "2026-01-01T00:00:00.000Z",
      npcPositions: new Map([["mara-baker", { x: 11, y: 12, z: 13 }]]),
    });
    assert(modelWithMap.npcs.find((n) => n.id === "mara-baker").pos.x === 11, "opts.npcPositions also accepts a Map");

    // No opts.npcPositions at all -> today's ledger-home-only behavior, unchanged.
    const modelNoOpt = await buildWorldModel(npcRecordsCall, { now: "2026-01-01T00:00:00.000Z" });
    assert(modelNoOpt.npcs.find((n) => n.id === "mara-baker").pos.x === -5, "omitting opts.npcPositions entirely keeps the old ledger-home-only behavior");
  }

  console.log("\n1a⁸. entity_positions is pure telemetry: NOT a director wake event");
  assert(!DIRECTOR_WAKE_EVENTS.has("entity_positions"), "\"entity_positions\" is absent from DIRECTOR_WAKE_EVENTS - it must never debounce into a director scene");

  console.log("\n1a⁹. World model: gadget:world-scan is preferred for terrain, and populates spawn/players/loadedChunks/notes");
  {
    // Fake bridge: a real place + NPC (so places/NPC diagnostics still run
    // against the gadget-sourced terrain), plus a gadget:world-scan payload
    // shaped exactly like brain/gadgets/world-scan.java's documented return
    // - heights[ix][iz] indexed by x then z, with one null column standing
    // in for an unloaded chunk. Flat ground at y=64 everywhere loaded.
    const worldScanCall = async (cmd, args) => {
      if (cmd === "ledger_query" && args.collection === "places") {
        return { records: [{ id: "old-well", name: "Old Well", kind: "well", origin: { x: 4, y: 64, z: 4 }, builtBy: "world" }] };
      }
      if (cmd === "ledger_query" && args.collection === "npcs") {
        return { records: [{ id: "mara-baker", name: "Mara the Baker", home: { x: 4, y: 65, z: 4 }, alive: true }] };
      }
      if (cmd === "gadget:world-scan") {
        assert(args.maxCells === 4096, "buildWorldModel calls gadget:world-scan with the default maxCells (4096) when opts.worldScanMaxCells is not set");
        // 3 columns (x: 0,4,8) x 3 rows (z: 0,4,8), step 4. Middle column's
        // middle cell (x=4,z=4) is null - an unloaded chunk, right where
        // Mara stands and Old Well sits.
        return {
          ok: true, world: "world", step: 4, origin: { x: 0, z: 0 }, cols: 3, rows: 3, sampledColumns: 8,
          heights: [
            [64, 64, 64],
            [64, null, 64],
            [64, 64, 64],
          ],
          surface: null, loadedChunks: 12,
          bounds: { x1: 0, z1: 0, x2: 8, z2: 8 },
          spawn: { x: 0, y: 65, z: 0 },
          players: [{ name: "Steve", x: 2.5, y: 65, z: 2.5 }],
        };
      }
      if (cmd === "get_block") return { material: "grass_block", blockData: "minecraft:grass_block" };
      throw new Error(`worldScanCall: unexpected command ${cmd}`);
    };

    const model = await buildWorldModel(worldScanCall, { now: "2026-01-01T00:00:00.000Z" });

    assert(model.terrain && model.terrain.heights.length === 3, "terrain came from the gadget (3x3 grid), not a scan_area fallback");
    assert(model.terrain.rows === 3 && model.terrain.cols === 3, "gadget cols(x-count)/rows(z-count) map onto the model's rows(x)/cols(z) fields");
    assert(model.spawn && model.spawn.x === 0 && model.spawn.y === 65 && model.spawn.z === 0, "model.spawn comes from the gadget's spawn field");
    assert(Array.isArray(model.players) && model.players.length === 1 && model.players[0].name === "Steve", "model.players comes from the gadget's players field");
    assert(model.loadedChunks === 12, "model.loadedChunks comes from the gadget's loadedChunks field");
    assert(Array.isArray(model.notes) && model.notes.length === 0, "no fallback note is recorded when the gadget succeeds");
    assert(model.bounds.minX === 0 && model.bounds.maxX === 8, "model.bounds x-range comes from the gadget's bounds");
    // Old Well (x:4,z:4) sits exactly on the null cell - nearestGroundHeight
    // must return null there (unknown), never a false floating/buried flag.
    assert(!model.places.find((p) => p.id === "old-well").flags.length, "a place sitting over a null (unloaded-chunk) terrain cell gets no floating/buried flag");
    assert(!model.diagnostics.some((d) => (d.kind === "floating" || d.kind === "buried") && d.subject === "old-well"), "no floating/buried diagnostic was produced for old-well (terrain unknown there)");

    const digest = formatWorldOverview(model, { detail: "full" });
    assert(digest.includes("Extent:") && digest.includes("12 loaded chunk"), "the text digest's header mentions the world extent and loaded chunk count");
    assert(digest.includes("Spawn:"), "the text digest's header mentions the spawn coordinate");
    assert(digest.includes("Steve"), "the text digest's header mentions the online player");

    assert(model.terrain.materials === null, "a world-scan payload with surface:null exposes terrain.materials === null");
  }

  console.log("\n1a⁹⁵. World model: a gadget:world-scan payload WITH a surface array exposes terrain.materials, same [ix][iz] indexing as heights");
  {
    const surfaceScanCall = async (cmd, args) => {
      if (cmd === "ledger_query") return { records: [] };
      if (cmd === "gadget:world-scan") {
        return {
          ok: true, world: "world", step: 4, origin: { x: 0, z: 0 }, cols: 3, rows: 3, sampledColumns: 9,
          heights: [
            [64, 64, 64],
            [64, 70, 64],
            [64, 64, 64],
          ],
          surface: [
            ["grass_block", "grass_block", "sand"],
            ["grass_block", "stone", "sand"],
            ["grass_block", "grass_block", "sand"],
          ],
          loadedChunks: 9,
          bounds: { x1: 0, z1: 0, x2: 8, z2: 8 },
          spawn: null,
          players: [],
        };
      }
      throw new Error(`surfaceScanCall: unexpected command ${cmd}`);
    };

    const model = await buildWorldModel(surfaceScanCall, { now: "2026-01-01T00:00:00.000Z" });
    assert(Array.isArray(model.terrain.materials), "terrain.materials is an array when the gadget's surface field is present");
    assert(model.terrain.materials[1][1] === "stone", "terrain.materials uses the same [ix][iz] indexing as terrain.heights");
    assert(model.terrain.materials[0][2] === "sand", "terrain.materials preserves per-cell material names");
  }

  console.log("\n1a¹⁰. World model: a gadget:world-scan failure falls back to scan_area exactly as before, and records a note");
  {
    const fallbackCall = async (cmd, args) => {
      if (cmd === "ledger_query" && args.collection === "places") return { records: [] };
      if (cmd === "ledger_query" && args.collection === "npcs") return { records: [] };
      if (cmd === "gadget:world-scan") throw new Error("no such gadget: world-scan (older server)");
      if (cmd === "scan_area") {
        const points = [];
        for (let x = args.x1; x <= args.x2; x++) {
          for (let z = args.z1; z <= args.z2; z++) points.push({ x, z, y: 64, material: "grass_block" });
        }
        return { minY: 64, maxY: 64, medianY: 64, flat: true, columns: points };
      }
      throw new Error(`fallbackCall: unexpected command ${cmd}`);
    };

    const model = await buildWorldModel(fallbackCall, { now: "2026-01-01T00:00:00.000Z" });
    assert(model.terrain && model.terrain.heights.length, "terrain is still populated via the scan_area fallback when the gadget call throws");
    assert(model.spawn === null, "model.spawn is null when the gadget is unavailable");
    assert(Array.isArray(model.players) && model.players.length === 0, "model.players is an empty array when the gadget is unavailable");
    assert(Array.isArray(model.notes) && model.notes.length === 1, "exactly one fallback note is recorded when the gadget call throws");
    assert(/gadget:world-scan unavailable/.test(model.notes[0]), "the fallback note names gadget:world-scan as the reason for falling back");
    assert(!model.diagnostics.some((d) => d.kind === "terrain-scan-failed"), "buildWorldModel never crashes/errors out when the gadget is unavailable - it degrades silently to scan_area");
    assert(model.terrain.materials === null, "the scan_area fallback path (no surface data at all) exposes terrain.materials === null");
  }

  console.log("\n1a¹¹. 3D map page: renders SPAWN + player markers");
  {
    const consoleServerSrc = fs.readFileSync(path.join(BRAIN_DIR, "lib", "console-server.mjs"), "utf8");
    assert(/function buildSpawnPrimitives/.test(consoleServerSrc), "console-server.mjs defines buildSpawnPrimitives()");
    assert(/function buildPlayerPrimitives/.test(consoleServerSrc), "console-server.mjs defines buildPlayerPrimitives()");
    assert(/worldmodel\.spawn/.test(consoleServerSrc), "the map page reads worldmodel.spawn");
    assert(/worldmodel\.players/.test(consoleServerSrc), "the map page reads worldmodel.players");
    assert(/buildSpawnPrimitives\(primitives,\s*sideFacing\)/.test(consoleServerSrc) && /buildPlayerPrimitives\(primitives,\s*sideFacing\)/.test(consoleServerSrc), "draw() invokes both new primitive builders (now with the shared sideFacing selection)");
    assert(/'SPAWN'/.test(consoleServerSrc), "the spawn marker is labelled SPAWN");
    assert(/loadedChunks/.test(consoleServerSrc), "the map header surfaces loadedChunks");
  }

  console.log("\n1a¹². True cubic voxel map: world-scan gadget voxel mode + console /voxels route + map renderer, director path untouched");
  {
    // --- the gadget stays a valid single-class GadgetContract with the new voxel mode ---
    const gadgetSrc = fs.readFileSync(path.join(BRAIN_DIR, "gadgets", "world-scan.java"), "utf8");
    assert(/public class WorldScan implements GadgetContract/.test(gadgetSrc), "world-scan.java is still a single public class implementing GadgetContract");
    assert(/import dev\.celestia\.mcalive2\.gadget\.GadgetContract;/.test(gadgetSrc), "world-scan.java imports the GadgetContract interface (class discovery is by interface)");
    const gadgetImports = gadgetSrc.match(/^import .+;$/gm) || [];
    assert(gadgetImports.every((i) => /import (com\.google\.gson|dev\.celestia\.mcalive2\.gadget|org\.bukkit)\./.test(i)), `world-scan.java's imports stay Bukkit + gson + gadget contract only (got: ${gadgetImports.join(", ")})`);
    assert(/bool\(args, "voxels", false\)/.test(gadgetSrc), "world-scan.java gates the voxel mode on args.voxels");
    assert(/voxelScan\(/.test(gadgetSrc), "world-scan.java defines the voxelScan() mode");
    assert(/"maxBlocks", 20000/.test(gadgetSrc), "voxel mode budgets emitted blocks (maxBlocks, default 20000)");
    assert(/"maxChunks", 12/.test(gadgetSrc), "voxel mode budgets per-call chunks (maxChunks) so one call never stalls the main thread");
    assert(/"cursor", 0/.test(gadgetSrc) && /nextCursor/.test(gadgetSrc), "voxel mode pages via cursor/nextCursor");
    assert(/SHELL = 3/.test(gadgetSrc), "the voxel shell is Chebyshev distance 3 from air");
    assert(/palette/.test(gadgetSrc) && /runJson\(lx, lz, runStart, runLen, runPi\)/.test(gadgetSrc), "voxels are palette-indexed and vertical-run-length encoded (never one JSON object per block)");
    assert(/getChunkSnapshot\(\)/.test(gadgetSrc), "voxel mode reads block data via ChunkSnapshot (no per-block getBlockAt storm)");
    assert(/isChunkLoaded\(cx [-+] 1, cz\)/.test(gadgetSrc), "voxel mode pads the air mask from loaded edge-neighbor chunks (no seam holes), never force-loading");
    // --- console server: /voxels route wired to the gadget through the bridge ---
    const consoleServerSrc = fs.readFileSync(path.join(BRAIN_DIR, "lib", "console-server.mjs"), "utf8");
    assert(/url\.pathname === "\/voxels"/.test(consoleServerSrc), "console-server.mjs serves GET /voxels");
    assert(/getVoxels/.test(consoleServerSrc), "console-server.mjs takes a getVoxels dep");
    assert(/VOXEL_CACHE_MS = 10_000/.test(consoleServerSrc), "GET /voxels responses are cached ~10s server-side");
    const indexSrc = fs.readFileSync(path.join(BRAIN_DIR, "index.mjs"), "utf8");
    assert(/"gadget:world-scan", \{ voxels: true/.test(indexSrc), "index.mjs's getVoxels calls gadget:world-scan with voxels:true");
    assert(/getVoxels,/.test(indexSrc), "index.mjs passes getVoxels to startConsoleServer");
    // --- map page: true-voxel renderer with face culling, heightmap fallback kept ---
    assert(/function buildVoxelPrimitives\(/.test(consoleServerSrc), "map page defines buildVoxelPrimitives()");
    assert(/function pushCubeFaces\(/.test(consoleServerSrc), "map page defines pushCubeFaces() (per-face cube drawing for culling)");
    assert(/function fetchVoxels\(/.test(consoleServerSrc), "map page defines fetchVoxels()");
    assert(/'\/voxels\?cursor=' \+ cursor/.test(consoleServerSrc), "map page tiles /voxels requests by cursor");
    assert(/VOXEL_REFRESH_MS = 10000/.test(consoleServerSrc), "static voxels are re-fetched at most every ~10s");
    assert(/function greedyMeshVoxels\(/.test(consoleServerSrc), "map page defines greedyMeshVoxels() - same-material voxels merge into larger boxes at fetch time");
    assert(/function mergeAlongAxis\(/.test(consoleServerSrc), "map page defines mergeAlongAxis() - the runs -> strips -> slabs merge helper");
    assert(/greedyMeshVoxels\(list, set, isRamp\)/.test(consoleServerSrc), "fetchVoxels() meshes at fetch time (draw() only walks the box list)");
    assert(/function faceOpen\(set,/.test(consoleServerSrc) && /b\.topOpen = faceOpen\(/.test(consoleServerSrc), "voxel renderer culls box faces fully covered by neighboring returned voxels, precomputed at mesh time against the per-voxel set");
    assert(/!isRamp\[v\.m\]/.test(consoleServerSrc), "ramp-fallback (unknown-colour) materials never merge vertically, keeping per-face height-ramp colours exact");
    assert(/voxels\.boxCount \+ ' boxes\)'/.test(consoleServerSrc), "the map status strip shows the merged box count next to the voxel count");
    assert(/if \(voxels && voxels\.count\) buildVoxelPrimitives/.test(consoleServerSrc), "draw() prefers voxel terrain and keeps buildTerrainPrimitives as the fallback");
    assert(/function buildTerrainPrimitives\(/.test(consoleServerSrc), "the heightmap terrain renderer is still present as the fallback");
    // --- headless sanity run of the mesher itself (the block between the
    // BEGIN/END markers is pure by contract - extract and evaluate it) ---
    {
      const meshMatch = consoleServerSrc.match(/\/\/ --- BEGIN pure greedy mesher[\s\S]*?\/\/ --- END pure greedy mesher ---/);
      assert(!!meshMatch, "the greedy mesher lives between its BEGIN/END extraction markers");
      const meshCtx = vm.createContext({});
      vm.runInContext(meshMatch[0], meshCtx);
      // Synthetic world: a 4x4x4 cube of material 0 plus one voxel of odd
      // material 1 flush against its +X face at (4,0,0).
      const list = [];
      const set = new Set();
      const addV = (x, y, z, m) => { list.push({ x, y, z, m }); set.add(x + "," + y + "," + z); };
      for (let x = 0; x < 4; x++) for (let y = 0; y < 4; y++) for (let z = 0; z < 4; z++) addV(x, y, z, 0);
      addV(4, 0, 0, 1);
      const boxes = vm.runInContext("greedyMeshVoxels", meshCtx)(list, set, [false, false]);
      assert(boxes.length === 2, `4x4x4 same-material cube + 1 odd-material voxel meshes to exactly 2 boxes (got ${boxes.length}) - a ${((1 - boxes.length / list.length) * 100).toFixed(0)}% primitive reduction`);
      const volume = boxes.reduce((s, b) => s + b.dx * b.dy * b.dz, 0);
      assert(volume === list.length, `meshed volume is conserved: sum(dx*dy*dz)=${volume} === ${list.length} voxels (nothing lost or duplicated)`);
      const big = boxes.find((b) => b.dx * b.dy * b.dz === 64);
      const odd = boxes.find((b) => b.m === 1);
      assert(big && big.m === 0 && odd && odd.dx === 1 && odd.dy === 1 && odd.dz === 1, "different palette indices never merge (the odd-material voxel stays its own 1x1x1 box)");
      assert(big.topOpen === true && big.xOpen2 === true && odd.xOpen1 === false, "precomputed face flags: the cube's top/+X faces are (partially) exposed, the odd voxel's -X face is fully covered and culled");
      // Ramp-fallback materials (isRamp true) must not merge vertically -
      // their colour is the per-voxel-Y height ramp.
      const rampList = [{ x: 9, y: 0, z: 9, m: 0 }, { x: 9, y: 1, z: 9, m: 0 }, { x: 9, y: 2, z: 9, m: 0 }];
      const rampSet = new Set(rampList.map((v) => v.x + "," + v.y + "," + v.z));
      const rampBoxes = vm.runInContext("greedyMeshVoxels", meshCtx)(rampList, rampSet, [true]);
      assert(rampBoxes.length === 3 && rampBoxes.every((b) => b.dy === 1), "ramp-coloured materials stay one box per Y level (height-ramp per-face colours unchanged)");
    }
    // --- the director's world-model path is untouched by voxels (token cost) ---
    const worldmodelSrc = fs.readFileSync(path.join(BRAIN_DIR, "lib", "worldmodel.mjs"), "utf8");
    assert(!/voxels\s*:\s*true/.test(worldmodelSrc), "lib/worldmodel.mjs (director world_overview path) never invokes the gadget's voxel mode");
    assert(!/nextCursor|palette/.test(worldmodelSrc), "lib/worldmodel.mjs has no voxel payload handling (the compact director digest stays untouched)");
  }

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

  console.log("\n1i. Self-update idle-wait cap forces a restart even with a turn presumed permanently in flight");
  {
    const runner = fakeRunner([
      { match: (c, a) => c === "git" && has(a, "rev-parse", "--is-inside-work-tree"), result: ok() },
      { match: (c, a) => c === "git" && has(a, "rev-parse", "HEAD"), result: ok("abc111\n") },
      { match: (c, a) => c === "git" && has(a, "ls-remote"), result: ok("def222\trefs/heads/main\n") },
      { match: (c, a) => c === "git" && has(a, "diff", "--name-only"), result: ok("brain/index.mjs\n") },
      { match: (c, a) => c === "git" && has(a, "pull", "--ff-only"), result: ok() },
      { match: (c) => c === "npm", result: ok() },
    ]);
    let restarted = false;
    const su = new SelfUpdater({
      checkIntervalSec: 10,
      runner,
      waitForIdle: () => new Promise(() => {}), // never resolves - simulates a permanently wedged turn
      idleWaitCapSec: 0.1, // 100ms cap, test-only
      restart: () => {
        restarted = true;
      },
    });
    const startedAt = Date.now();
    const result = await su.checkOnce();
    const elapsedMs = Date.now() - startedAt;
    assert(result.action === "restarted", `idle-wait cap still results in a restart (got ${result.action})`);
    assert(restarted, "restart callback was invoked despite waitForIdle() never resolving");
    assert(result.idleCapped === true, "checkOnce reports idleCapped=true when the cap fired before idle was reached");
    assert(elapsedMs < 2000, `restart happened near the idle-wait cap (~100ms), not after waiting forever (took ${elapsedMs}ms)`);
  }

  console.log("\n1j. Turn timeout: a wedged director turn times out, is journaled, and the scheduler unblocks to run the NEXT batch");
  {
    // A fake queryFn shaped like the real SDK's query(): called with
    // {prompt, options}, returns an async generator of SDKMessage-shaped
    // objects. This one never yields and never returns - simulating a
    // genuinely wedged streamed query() call.
    function hangingQueryFn() {
      return (async function* () {
        await new Promise(() => {}); // never resolves
      })();
    }
    function instantOkQueryFn() {
      return (async function* () {
        yield { type: "result", subtype: "success", result: "handled the second batch fine", usage: { input_tokens: 5, output_tokens: 5 } };
      })();
    }

    const timeoutStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-timeout-"));
    const timeoutConfig = { ...loadConfig({}), dryRun: false, turnTimeoutSec: 0.2, stateDir: timeoutStateDir };

    let sceneRuns = 0;
    const scheduler = new DirectorScheduler({
      debounceMs: 10,
      runScene: async (batch) => {
        sceneRuns += 1;
        const sceneNumber = sceneRuns;
        const queryFn = sceneNumber === 1 ? hangingQueryFn : instantOkQueryFn;
        return runDirectorTurn({ batch, systemPrompt: "", config: timeoutConfig, sceneNumber, queryFn });
      },
    });

    scheduler.push("player_join", { player: "Steve" });
    // Debounce (10ms) + timeout (200ms) + a little slack for abort/cleanup.
    const firstDone = await waitForCondition(() => scheduler.scenesStarted === 1 && !scheduler.sceneRunning, 2000);
    assert(firstDone, "the wedged first scene eventually finished (timed out) and the scheduler flag cleared");
    assert(!scheduler.sceneRunning, "sceneRunning is false after the timeout - the scheduler is unblocked, not stuck forever");

    const journalAfterFirst = fs.readFileSync(path.join(timeoutStateDir, "decisions.log"), "utf8");
    assert(journalAfterFirst.includes("Decision: timed out after"), "the timed-out scene is journaled as \"Decision: timed out after Ns - aborted\"");
    assert(/timed out after \d+s - aborted/.test(journalAfterFirst), "the journaled timeout decision names the elapsed seconds");

    // Push a second, independent batch - proves the scheduler moved on
    // rather than staying wedged behind the first (hung) scene forever.
    scheduler.push("npc_death", {});
    const secondDone = await waitForCondition(() => scheduler.scenesStarted === 2, 2000);
    assert(secondDone, "the scheduler processed the NEXT batch after the timed-out scene, proving it is not permanently blocked");
  }

  console.log("\n1k. Turn timeout: a wedged actor turn also times out cleanly (never throws, timedOut is reported)");
  {
    function hangingQueryFn() {
      return (async function* () {
        await new Promise(() => {});
      })();
    }
    const actorTimeoutStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-actor-timeout-"));
    const actorTimeoutConfig = { ...loadConfig({}), dryRun: false, turnTimeoutSec: 0.15, stateDir: actorTimeoutStateDir, maxActorSteps: 6 };
    const result = await runActorTurn({
      npc: { id: "mara-baker", name: "Mara" },
      facts: [],
      player: "Steve",
      trigger: "npc_interact",
      transcript: { summary: "", recent: [] },
      config: actorTimeoutConfig,
      queryFn: hangingQueryFn,
    });
    assert(result.timedOut === true, "a wedged actor turn reports timedOut=true instead of hanging the caller");
    assert(result.dryRun === false, "a timed-out actor turn is not mistaken for a dry run");
    assert(result.reportText === null, "a timed-out actor turn's reportText is null (never fed back as something the NPC said)");
    const actorJournal = fs.readFileSync(path.join(actorTimeoutStateDir, "decisions.log"), "utf8");
    assert(actorJournal.includes("Decision: timed out after"), "the timed-out actor turn is journaled the same way as a timed-out scene");
  }

  console.log("\n1l. Tool-result cap: large array results are truncated with an omitted-count marker and stay under the cap; isError results are NEVER truncated");
  {
    const bigRecords = Array.from({ length: 300 }, (_, i) => ({
      id: `npc-${i}`,
      name: `NPC ${i}`,
      role: "a fairly verbose role description repeated to pad out the size of this record for the test",
    }));
    const bigResult = { records: bigRecords };
    const capped = capToolResultText(bigResult, { maxChars: 3000 });
    assert(capped.length <= 3000, `a 300-record result is truncated to fit under the 3000-char cap (got ${capped.length})`);
    assert(/more item\(s\) omitted/.test(capped), "the truncated result carries a clear omitted-count marker");
    const parsedCapped = JSON.parse(capped);
    assert(Array.isArray(parsedCapped.records) && parsedCapped.records.length > 0 && parsedCapped.records.length < 300, "the truncated result keeps a real, non-empty PREFIX of the original records (not zero, not all)");
    assert(parsedCapped.records[0].id === "npc-0", "the truncated result's kept records are the FIRST ones, not a random subset");

    const smallResult = { records: [{ id: "a" }, { id: "b" }] };
    const uncapped = capToolResultText(smallResult, { maxChars: 3000 });
    assert(JSON.parse(uncapped).records.length === 2, "a result already under the cap passes through with nothing omitted");

    // isError results must NEVER be truncated, however large - the
    // director/actor needs the FULL compiler/validation error text to
    // iterate (e.g. gadget_define's javac diagnostics).
    const bigErrorText = "ERROR: " + "javac: cannot find symbol\n".repeat(500);
    assert(bigErrorText.length > 3000, "sanity: the synthetic error text is itself bigger than the cap");
    const errorResult = capToolResultText(bigErrorText, { maxChars: 3000, isError: true });
    assert(errorResult === bigErrorText, "isError:true bypasses truncation entirely, however large the text");

    // A top-level array (not wrapped in an object) is also truncated.
    const bigArray = Array.from({ length: 500 }, (_, i) => ({ x: i, y: 64, z: i, material: "grass_block" }));
    const cappedArray = capToolResultText(bigArray, { maxChars: 3000 });
    assert(cappedArray.length <= 3000, `a bare 500-entry array result is truncated to fit under the cap (got ${cappedArray.length})`);
    assert(/more item\(s\) omitted/.test(cappedArray), "the truncated bare-array result also carries the omitted-count marker");

    // Default cap resolution reads BRAIN_MAX_TOOL_RESULT_CHARS, falling
    // back to 3000.
    assert(resolveMaxToolResultChars({}) === 3000, "resolveMaxToolResultChars defaults to 3000 with no env var set");
    assert(resolveMaxToolResultChars({ BRAIN_MAX_TOOL_RESULT_CHARS: "500" }) === 500, "resolveMaxToolResultChars honors BRAIN_MAX_TOOL_RESULT_CHARS");
  }

  console.log("\n1m. world_overview compaction: a large synthetic model (200 places/200 npcs/200 diagnostics, 64x64 terrain) stays comfortably under the tool-result cap, and the ASCII map is at most 24 wide");
  {
    const mkPlaces = (n) => Array.from({ length: n }, (_, i) => ({
      id: `place-${i}`,
      name: `The Grand Hall of a Very Long Settlement Name Number ${i} That Keeps Going`,
      kind: "settlement",
      origin: { x: i, y: 64, z: i },
      bounds: { x1: i, y1: 64, z1: i, x2: i + 5, y2: 70, z2: i + 5 },
      builtBy: "ai",
      flags: i % 3 === 0 ? ["floating"] : [],
    }));
    const mkNpcs = (n) => Array.from({ length: n }, (_, i) => ({
      id: `npc-${i}`,
      name: `NPC Number ${i}`,
      role: "a fairly verbose role description describing this character's backstory and motivations at some length",
      pos: { x: i, y: 64, z: i },
      alive: i % 5 !== 0,
      flags: i % 2 === 0 ? ["off-ground"] : [],
    }));
    const mkDiagnostics = (n) => Array.from({ length: n }, (_, i) => ({
      severity: i % 3 === 0 ? "error" : "warn",
      kind: "floating",
      subject: `place-${i}`,
      message: `place-${i} sits well above the scanned ground and this diagnostic message runs on for a while to simulate real text`,
      at: { x: i, y: 90, z: i },
    }));
    const mkTerrain = (size) => {
      const heights = [];
      for (let x = 0; x < size; x++) {
        const row = [];
        for (let z = 0; z < size; z++) row.push(64 + ((x + z) % 10));
        heights.push(row);
      }
      return { origin: { x: 0, z: 0 }, step: 1, cols: size, rows: size, heights, materials: null };
    };

    const bigModel = {
      generatedAt: "2026-01-01T00:00:00.000Z",
      bounds: { minX: 0, minZ: 0, maxX: 200, maxZ: 200, minY: 60, maxY: 100 },
      places: mkPlaces(200),
      npcs: mkNpcs(200),
      terrain: mkTerrain(64),
      diagnostics: mkDiagnostics(200),
      spawn: { x: 0, y: 64, z: 0 },
      players: [],
      notes: [],
      loadedChunks: 500,
    };

    const fullDigest = formatWorldOverview(bigModel, { detail: "full" });
    const cfgForCap = loadConfig({});
    assert(fullDigest.length < cfgForCap.maxToolResultChars, `formatWorldOverview's detail:"full" digest for 200 places/200 npcs/200 diagnostics stays comfortably under the ${cfgForCap.maxToolResultChars}-char cap (got ${fullDigest.length})`);
    assert(fullDigest.length < cfgForCap.maxToolResultChars * 0.95, `the digest has real margin, not just barely under the cap (got ${fullDigest.length} vs. cap ${cfgForCap.maxToolResultChars})`);

    const mapLines = fullDigest.split("\n").filter((l) => /^[.+A-Z]+$/.test(l));
    assert(mapLines.length > 0, "the full-detail digest includes ASCII map rows");
    assert(mapLines.every((l) => l.length <= 24), `every ASCII map row is at most 24 characters wide (widths: ${mapLines.map((l) => l.length).join(",")})`);
    assert(mapLines.length <= 24, `the ASCII map is at most 24 rows tall (got ${mapLines.length})`);

    assert(/more place\(s\) omitted/.test(fullDigest), "the PLACES section notes omitted places rather than listing all 200");
    assert(/more flagged NPC\(s\) omitted/.test(fullDigest), "the flagged-NPCs section notes omitted NPCs rather than listing all of them");
    assert(/more problem\(s\) omitted/.test(fullDigest), "the PROBLEMS section notes omitted diagnostics rather than listing all 200");

    // Also passes through the mcp-bridge.mjs-side safety-net cap unchanged
    // (it's already under budget, so capToolResultText is a no-op here).
    const throughCap = capToolResultText(fullDigest, { maxChars: cfgForCap.maxToolResultChars });
    assert(throughCap === fullDigest, "a digest that's already under the cap passes through capToolResultText unchanged");
  }

  console.log("\n1n. maxTokensFor(): the order ceiling for operator_order scenes, the normal per-turn ceiling otherwise");
  {
    const cfgTokens = loadConfig({});
    assert(cfgTokens.maxTokensPerTurn === 120000, `BRAIN_MAX_TOKENS_PER_TURN defaults to 120000 (got ${cfgTokens.maxTokensPerTurn})`);
    assert(cfgTokens.maxTokensPerOrder === 600000, `BRAIN_MAX_TOKENS_PER_ORDER defaults to 600000 (got ${cfgTokens.maxTokensPerOrder})`);
    const normalBatchTok = [{ event: "player_join", data: {}, at: new Date().toISOString() }];
    const orderBatchTok = [{ event: "operator_order", data: { text: "build a lighthouse" }, at: new Date().toISOString() }];
    assert(maxTokensFor(normalBatchTok, cfgTokens) === cfgTokens.maxTokensPerTurn, "a normal scene's token ceiling is the regular maxTokensPerTurn");
    assert(maxTokensFor(orderBatchTok, cfgTokens) === cfgTokens.maxTokensPerOrder, "an operator_order scene's token ceiling is the higher maxTokensPerOrder");
    process.env.BRAIN_MAX_TOKENS_PER_TURN = "4242";
    assert(loadConfig().maxTokensPerTurn === 4242, "BRAIN_MAX_TOKENS_PER_TURN is honored when set");
    delete process.env.BRAIN_MAX_TOKENS_PER_TURN;
  }

  console.log("\n1o. Per-turn token ceiling: a fake streamed director turn whose accumulated tool-result text exceeds the ceiling is ABORTED mid-turn and journaled - the scheduler is not blocked, and an order scene reverts to \"queued\" exactly like a timeout");
  {
    // A fake queryFn shaped like the real SDK's query(): streams one big
    // tool_result-carrying "user" message per step, forever, simulating
    // exactly the production incident (world_overview detail:"full" called
    // repeatedly, each huge result re-sent on every subsequent step). It
    // never naturally completes - only the token ceiling should stop it
    // (BRAIN_TURN_TIMEOUT_SEC is set generously high so a timeout can't be
    // the thing that actually stops it, isolating the ceiling behavior).
    function bigToolResultQueryFn() {
      let step = 0;
      const bigText = "x".repeat(2000); // ~500 estimated tokens per step (2000/4)
      return (async function* () {
        // eslint-disable-next-line no-constant-condition
        while (true) {
          step += 1;
          yield {
            type: "assistant",
            message: { content: [{ type: "tool_use", name: "mcp__mcalive2__world_overview", input: { detail: "full" } }] },
          };
          yield {
            type: "user",
            message: { content: [{ type: "tool_result", content: bigText }] },
          };
          if (step > 1000) return; // safety valve - should never be reached
        }
      })();
    }

    const tokenCeilingStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-token-ceiling-"));
    const tokenCeilingConfig = {
      ...loadConfig({}),
      dryRun: false,
      turnTimeoutSec: 30, // generous - the ceiling, not the clock, must be what stops this
      maxTokensPerTurn: 1000, // low ceiling so a handful of ~500-token steps trips it fast
      maxTokensPerOrder: 1000,
      stateDir: tokenCeilingStateDir,
    };

    let sceneRuns2 = 0;
    const scheduler2 = new DirectorScheduler({
      debounceMs: 10,
      runScene: async (batch) => {
        sceneRuns2 += 1;
        const sceneNumber = sceneRuns2;
        return runDirectorTurn({ batch, systemPrompt: "", config: tokenCeilingConfig, sceneNumber, queryFn: bigToolResultQueryFn });
      },
    });

    const orderTimestamp = "2026-01-01T00:00:00.000Z";
    scheduler2.push("operator_order", { text: "build a whole city", orderTimestamp, at: new Date().toISOString() });
    const ceilingDone = await waitForCondition(() => scheduler2.scenesStarted === 1 && !scheduler2.sceneRunning, 3000);
    assert(ceilingDone, "the runaway-tool-result scene eventually finished (hit the token ceiling) and the scheduler flag cleared");
    assert(!scheduler2.sceneRunning, "sceneRunning is false after the token-ceiling abort - the scheduler is unblocked, not stuck forever");

    const ceilingJournal = fs.readFileSync(path.join(tokenCeilingStateDir, "decisions.log"), "utf8");
    assert(/Decision: hit the per-turn token ceiling \(\d+\) - aborted/.test(ceilingJournal), "the aborted scene is journaled as \"Decision: hit the per-turn token ceiling (N) - aborted\"");

    // Push a second, independent batch - proves the scheduler moved on
    // rather than staying blocked behind the aborted scene forever.
    scheduler2.push("npc_death", {});
    const secondCeilingDone = await waitForCondition(() => scheduler2.scenesStarted === 2, 2000);
    assert(secondCeilingDone, "the scheduler processed the NEXT batch after the token-ceiling abort, proving it is not permanently blocked");
  }

  console.log("\n1p. Director prompt carries the remaining daily budget and this turn's token ceiling");
  {
    const budgetPromptConfig = { ...loadConfig({}), dryRun: true, maxTokensPerTurn: 77777 };
    let dryRunLog = null;
    const origInfo = log.info;
    log.info = (msg, data) => {
      if (msg === "dry_run_director_turn") dryRunLog = data;
      origInfo(msg, data);
    };
    try {
      await runDirectorTurn({
        batch: [{ event: "player_join", data: { player: "Steve" }, at: new Date().toISOString() }],
        systemPrompt: "",
        config: budgetPromptConfig,
        sceneNumber: 999,
        remainingBudget: 12345,
      });
    } finally {
      log.info = origInfo;
    }
    assert(dryRunLog && dryRunLog.prompt.includes("BUDGET:"), "the director prompt has a BUDGET line");
    assert(dryRunLog && dryRunLog.prompt.includes("12,345"), "the BUDGET line names the remaining daily token budget passed in");
    assert(dryRunLog && dryRunLog.prompt.includes("77,777"), "the BUDGET line names this turn's hard token ceiling");
    assert(dryRunLog && /economical/.test(dryRunLog.prompt), "the BUDGET line instructs the director to be economical");
  }

  console.log("\n1q. Chronicle: append/read roundtrip, session dating, rewrite guard, digest budget, director-only tools");
  {
    const chronDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-chronicle-"));
    process.env.CHRONICLE_DIR = chronDir; // chronicle.mjs resolves its root per call, so tests can redirect it
    try {
      assert(chronicleDigest() === "", "digest of an empty chronicle is '' (director prompt skips the section entirely)");
      assert(listSessions().length === 0, "listSessions() on an empty chronicle is an empty array");

      // --- append/read roundtrip on world-bible ---
      chronicleAppend("world-bible", "The Broken Bell", "An old bronze bell hangs cracked in the square.\n\nSecond paragraph with deeper detail the digest must skip.");
      const wb = chronicleReadFile("world-bible");
      assert(wb.includes("## The Broken Bell") && wb.includes("old bronze bell"), "world-bible append/read roundtrips heading and text");
      assert(/## The Broken Bell — \d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/.test(wb), "appended heading carries an ISO timestamp");
      assert(chronicleReadFile("world-bible", { tail: 10 }) === wb.slice(-10), "readFile's tail option returns exactly the last N chars");

      // --- session file dating (UTC, created with a header) ---
      const today = todaySessionDate();
      chronicleAppend("session", "Steve arrives", "Steve walked into the square at dawn and rang the bell.");
      assert(fs.existsSync(path.join(chronDir, "sessions", `${today}.md`)), "a session append creates sessions/YYYY-MM-DD.md for today's UTC date");
      const sess = chronicleReadFile("session");
      assert(sess.startsWith(`# Session ${today}`), "a fresh session file starts with its '# Session YYYY-MM-DD' header");
      assert(chronicleReadFile(`session:${today}`) === sess, "session:YYYY-MM-DD addressing reads the same file as \"session\"");
      assert(listSessions().length === 1 && listSessions()[0] === today, "listSessions() lists today's journal");

      // --- rewrite guard: sessions are the permanent record ---
      let sessionRewriteThrew = false;
      try {
        chronicleRewrite("session", "history, revised");
      } catch {
        sessionRewriteThrew = true;
      }
      assert(sessionRewriteThrew, "rewriteFile(\"session\") throws - session journals can never be rewritten");
      let datedRewriteThrew = false;
      try {
        chronicleRewrite(`session:${today}`, "history, revised");
      } catch {
        datedRewriteThrew = true;
      }
      assert(datedRewriteThrew, "rewriteFile(\"session:YYYY-MM-DD\") throws too");
      assert(chronicleReadFile("session") === sess, "the refused rewrites left the session journal untouched");

      // --- rewrite allowed for arcs (compaction) ---
      chronicleRewrite("arcs", "## The Dry Well — active\n\nThe village well ran dry; Mara suspects the bell.");
      assert(chronicleReadFile("arcs").startsWith("## The Dry Well"), "rewriteFile replaces arcs.md wholesale");

      // --- digest: bible skim + arcs verbatim + session tail ---
      const dig = chronicleDigest();
      assert(dig.includes("WORLD BIBLE") && dig.includes("The Broken Bell") && dig.includes("old bronze bell"), "digest carries world-bible headings with their first paragraph");
      assert(!dig.includes("deeper detail the digest must skip"), "digest skips paragraphs after the first under each world-bible heading");
      assert(dig.includes("ARCS:") && dig.includes("The Dry Well"), "digest carries arcs.md verbatim");
      assert(dig.includes(`SESSION ${today}`) && dig.includes("rang the bell"), "digest carries the tail of the most recent session journal");

      // --- digest respects a hard character budget with a marker ---
      const tiny = chronicleDigest(200);
      assert(tiny.length <= 200, `digest(200) stays within its budget (got ${tiny.length})`);
      assert(/truncated/.test(tiny), "an over-budget digest carries a truncation marker");

      // --- system-prompt injection (lib/director-turn.mjs) ---
      assert(buildSystemPrompt("THE LORE", () => "") === "THE LORE", "an empty digest leaves the director system prompt byte-identical to the lore");
      const withChronicle = buildSystemPrompt("THE LORE", () => "canon here");
      assert(withChronicle.includes("CHRONICLE (campaign canon)") && withChronicle.includes("canon here") && withChronicle.startsWith("THE LORE"), "a non-empty digest is appended as its own labeled CHRONICLE section after the lore");
      assert(buildSystemPrompt("THE LORE", () => { throw new Error("disk gone"); }) === "THE LORE", "a digest failure never blocks the turn - the prompt falls back to the bare lore");
    } finally {
      delete process.env.CHRONICLE_DIR;
    }

    // --- director-only tool registration ---
    for (const t of ["chronicle_append", "chronicle_read", "chronicle_rewrite"]) {
      assert(ALL_TOOLS.includes(t), `${t} is in ALL_TOOLS`);
      assert(!ACTOR_TOOLS.includes(t), `${t} is NOT in ACTOR_TOOLS (director-only by omission)`);
      assert(actorDisallowedTools(MCP_SERVER_NAME).includes(namespacedTool(t, MCP_SERVER_NAME)), `${t} is in the actor disallow-list`);
    }
    const bridgeSrcForChronicle = fs.readFileSync(path.join(BRAIN_DIR, "mcp-bridge.mjs"), "utf8");
    assert(/tool\("chronicle_append"/.test(bridgeSrcForChronicle), "mcp-bridge.mjs registers chronicle_append brain-locally (via tool(), not a pt() passthrough)");
    assert(/tool\("chronicle_read"/.test(bridgeSrcForChronicle), "mcp-bridge.mjs registers chronicle_read brain-locally");
    assert(/tool\("chronicle_rewrite"/.test(bridgeSrcForChronicle), "mcp-bridge.mjs registers chronicle_rewrite brain-locally");
    assert(/from "\.\/lib\/chronicle\.mjs"/.test(bridgeSrcForChronicle), "mcp-bridge.mjs imports lib/chronicle.mjs");
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

  // --- live position tracking: entity_positions reaches the handler but never a scene ---
  const gotPositionEvent = await waitFor(brain1.logs, (l) => l.msg === "event_received" && l.event === "entity_positions", 5000);
  assert(gotPositionEvent, "brain received the pushed entity_positions event over its bridge connection");
  assert(!sceneStarts().some((s) => s.events.includes("entity_positions")), "entity_positions never appears inside a director scene's batched events (it is telemetry-only, not a wake event)");

  // --- boot auto-install of the position-tracker gadget ---
  const gotPositionInstalled = await waitFor(brain1.logs, (l) => l.msg === "position_tracking_installed", 5000);
  assert(gotPositionInstalled, "boot auto-install called gadget_define + gadget_run for position-tracker against the mock bridge (which answers ok:true to both)");
  const gadgetDefineCalls = bridge1.logs.filter((l) => l.mock === "command_received" && l.cmd === "gadget_define" && l.args && l.args.id === "position-tracker");
  assert(gadgetDefineCalls.length >= 1, "mock bridge received gadget_define for id=\"position-tracker\" on boot");
  const gadgetRunCalls = bridge1.logs.filter((l) => l.mock === "command_received" && l.cmd === "gadget_run" && l.args && l.args.id === "position-tracker");
  assert(gadgetRunCalls.length >= 1, "mock bridge received gadget_run for id=\"position-tracker\" on boot");

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
    assert(directorDryRun.prompt.includes("FORMULAS"), "director prompt has a FORMULAS section");
    assert(directorDryRun.prompt.includes("COMPOSE it as a formula"), "director prompt instructs composing formulas instead of wishing for tools");
    assert(directorDryRun.prompt.includes("JOBS"), "director prompt has a JOBS section");
    assert(directorDryRun.prompt.includes("npc_assign_job"), "director prompt's JOBS section mentions npc_assign_job");
    assert(directorDryRun.prompt.includes("BUILDING DISCIPLINE"), "director prompt has a BUILDING DISCIPLINE section");
    assert(directorDryRun.prompt.includes("scan_area the footprint"), "BUILDING DISCIPLINE section instructs scanning the footprint before building");
    assert(directorDryRun.prompt.includes('settle:"surface"'), "BUILDING DISCIPLINE section mentions settle:\"surface\"");
    assert(directorDryRun.prompt.includes("clearAbove:true"), "BUILDING DISCIPLINE section mentions clearAbove:true");
    assert(directorDryRun.prompt.includes("floors are where you thought they were"), "BUILDING DISCIPLINE section text is proper English (no stray non-English word)");
    assert(directorDryRun.prompt.includes("CAPABILITY LADDER"), "director prompt has a CAPABILITY LADDER section");
    assert(directorDryRun.prompt.includes("GADGET"), "director prompt's capability ladder mentions writing a GADGET");
    assert(directorDryRun.prompt.includes("gadget_define"), "director prompt's capability ladder mentions gadget_define");
  }

  // --- lore load: bestiary text reaches the director's system prompt ---
  if (directorDryRun) {
    assert(typeof directorDryRun.systemPrompt === "string" && directorDryRun.systemPrompt.includes("monsters are story, never weather"), "director system prompt (lore) includes the bestiary file's core line");
    assert(directorDryRun.systemPrompt.includes("Zombies and skeletons do not arise on their own"), "director system prompt includes the bestiary's necromancer canon");
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
  const consoleStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-console-state-"));
  const brain4 = spawnNode([path.join(BRAIN_DIR, "index.mjs")], {
    MCALIVE2_URL: `ws://127.0.0.1:${port4}`,
    MCALIVE2_TOKEN: "test-token",
    BRAIN_DEBOUNCE_MS: "300",
    BRAIN_DRY_RUN: "1",
    BRAIN_ENABLED: "1",
    BRAIN_LORE_REFRESH_MS: "600000",
    BRAIN_LORE_DIR: consoleLoreDir,
    BRAIN_STATE_DIR: consoleStateDir,
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

  console.log("\n5b. Lore Console orders: POST /order persists + starts a director scene with the OPERATOR ORDERS briefing and the higher turn ceiling");
  const orderText = "Strike spawn with lightning 100 times, then build a floating village nearby.";
  const orderPostRes = await fetch(`${base}/order?token=${consoleToken}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ text: orderText }),
  });
  const orderPostJson = await orderPostRes.json();
  assert(orderPostRes.status === 200 && orderPostJson.ok === true, `POST /order with a valid token succeeds (got ${orderPostRes.status} ${JSON.stringify(orderPostJson)})`);
  assert(typeof orderPostJson.timestamp === "string" && orderPostJson.timestamp.length > 0, "POST /order returns the new order's timestamp");

  // orders.json lives under this brain instance's BRAIN_STATE_DIR.
  const ordersJsonPath = path.join(consoleStateDir, "orders.json");
  assert(fs.existsSync(ordersJsonPath), "state/orders.json was created");
  const ordersJson = JSON.parse(fs.readFileSync(ordersJsonPath, "utf8"));
  assert(Array.isArray(ordersJson) && ordersJson.some((o) => o.text === orderText), "orders.json contains the posted order text");
  const savedOrder = ordersJson.find((o) => o.text === orderText);
  assert(savedOrder && savedOrder.status === "queued", "the saved order has status 'queued'");
  assert(savedOrder && typeof savedOrder.timestamp === "string", "the saved order carries a timestamp");

  const gotOrderScene = await waitFor(
    brain4.logs,
    (l) => l.msg === "dry_run_director_turn" && typeof l.prompt === "string" && l.prompt.includes(orderText),
    5000
  );
  assert(gotOrderScene, "a director scene's dry-run prompt contains the order text");
  const orderSceneLog = brain4.logs.find((l) => l.msg === "dry_run_director_turn" && typeof l.prompt === "string" && l.prompt.includes(orderText));
  if (orderSceneLog) {
    assert(orderSceneLog.prompt.includes("OPERATOR ORDERS"), "the order scene's dry-run prompt contains the OPERATOR ORDERS briefing section");
    assert(orderSceneLog.prompt.includes("- operator_order:"), "the order scene's dry-run prompt lists the operator_order event in its SCENE section");
    assert(orderSceneLog.maxTurns === 80, `the order scene uses the higher BRAIN_ORDER_MAX_STEPS ceiling (default 80, got ${orderSceneLog.maxTurns})`);
  }
  // A non-order scene (e.g. the earlier directive-triggered ones) should use
  // the regular, lower maxDirectorSteps ceiling, proving the higher ceiling
  // is scene-specific, not global. Note the STANDING ORDERS briefing text
  // itself always mentions "operator_order" (in its OPERATOR ORDERS
  // section) regardless of scene content, so detect an order scene by the
  // SCENE section's actual event line ("- operator_order:"), not by a bare
  // substring match against the whole prompt.
  const nonOrderSceneLog = brain4.logs.find((l) => l.msg === "dry_run_director_turn" && typeof l.prompt === "string" && !l.prompt.includes("- operator_order:"));
  assert(nonOrderSceneLog, "at least one non-order director scene was logged (control case for the maxTurns comparison)");
  if (nonOrderSceneLog) {
    assert(nonOrderSceneLog.maxTurns === 12, `a non-order scene uses the regular maxDirectorSteps ceiling (default 12, got ${nonOrderSceneLog.maxTurns})`);
  }

  const pageWithOrder = await fetch(`${base}/?token=${consoleToken}`);
  const pageWithOrderHtml = await pageWithOrder.text();
  assert(pageWithOrderHtml.includes(orderText), "GET / page lists the recent order text");
  assert(pageWithOrderHtml.includes("Order the world"), "GET / page has the order textarea/button");
  assert(pageWithOrderHtml.includes(savedOrder.timestamp), "GET / page lists the order's timestamp");

  console.log("\n5c. 3D world map page (GET /map): auth-gated like every other console route, self-contained canvas viewer");
  const mapUnauthed = await fetch(`${base}/map`);
  assert(mapUnauthed.status === 401, `GET /map with no token is rejected (got ${mapUnauthed.status})`);

  const mapRes = await fetch(`${base}/map?token=${consoleToken}`);
  assert(mapRes.status === 200, `GET /map with a valid token succeeds (got ${mapRes.status})`);
  const mapHtml = await mapRes.text();
  assert(mapHtml.includes("<canvas"), "GET /map page renders a <canvas> element");
  assert(mapHtml.includes("fetch('/worldmodel')"), "GET /map page's inline script fetches /worldmodel");
  assert(mapHtml.includes('id="problem-list"') && mapHtml.includes("Problems (worst first)"), "GET /map page has the problems panel markup");
  assert(pageWithOrderHtml.includes('href="/map"'), "GET / page links to /map");
  assert(mapHtml.includes('href="/"'), "GET /map page links back to the Lore Console (/)");

  console.log("\n5c¹. 3D map page: terrain renders as cubes/voxels, coloured by block type via a material lookup");
  assert(/function pushCube\(/.test(mapHtml), "GET /map page defines pushCube() - the shared cube-face builder");
  assert(/function computeSideFacing\(/.test(mapHtml), "GET /map page defines computeSideFacing() - picks the 2 camera-facing side faces from yaw");
  assert(/MATERIAL_COLORS/.test(mapHtml), "GET /map page defines a MATERIAL_COLORS lookup");
  assert(/grass_block/.test(mapHtml) && /deepslate/.test(mapHtml) && /oak_log|_log\$/.test(mapHtml) && /snow_block/.test(mapHtml), "the material lookup covers common Minecraft blocks (grass_block, deepslate, logs, snow_block)");
  assert(/function colorForMaterial\(/.test(mapHtml), "GET /map page defines colorForMaterial() with an unknown-material fallback");
  assert(/function heightRampColor\(/.test(mapHtml), "GET /map page keeps the height-based grey/green ramp as the fallback for unknown/absent materials");
  assert(/function buildEntityCubeStack\(/.test(mapHtml), "GET /map page renders NPCs/players/spawn as cube stacks, not pillar markers");

  console.log("\n5c². GET /voxels: token-authed, proxies gadget:world-scan's voxel mode through the bridge, and the map page consumes it");
  assert(mapHtml.includes("/voxels?cursor="), "GET /map page's inline script tiles fetches of /voxels by cursor");
  assert(/function buildVoxelPrimitives\(/.test(mapHtml), "GET /map page defines buildVoxelPrimitives() - the true-voxel renderer");
  assert(/function greedyMeshVoxels\(/.test(mapHtml), "GET /map page defines greedyMeshVoxels() - voxels merge into boxes before drawing");
  assert(mapHtml.includes("voxels.boxCount + ' boxes)'"), "GET /map status strip reports the merged box count");
  const voxelsUnauthed = await fetch(`${base}/voxels`);
  assert(voxelsUnauthed.status === 401, `GET /voxels with no token is rejected (got ${voxelsUnauthed.status})`);
  const voxelsRes = await fetch(`${base}/voxels?cursor=0&token=${consoleToken}`);
  assert(voxelsRes.status === 200, `GET /voxels with a valid token succeeds (got ${voxelsRes.status})`);
  const voxelsJson = await voxelsRes.json();
  assert(voxelsJson && typeof voxelsJson === "object", "GET /voxels returns a JSON object (mock bridge answers ok:true data:{} to the gadget call)");
  const gotVoxelGadgetCall = await waitFor(bridge4.logs, (l) => l.mock === "command_received" && l.cmd === "gadget:world-scan" && l.args && l.args.voxels === true, 3000);
  assert(gotVoxelGadgetCall, "the /voxels request reached the bridge as gadget:world-scan with voxels:true");
  const voxelGadgetCalls = () => bridge4.logs.filter((l) => l.mock === "command_received" && l.cmd === "gadget:world-scan" && l.args && l.args.voxels === true);
  assert(voxelGadgetCalls().every((l) => l.args.cursor === 0), "the requested cursor was forwarded to the gadget call");
  // Regression: Number(null) is 0, so a bare request (no x1/z1/x2/z2 params)
  // must NOT synthesize area {0,0,0,0} - that asks the gadget for just chunk
  // 0,0 and the live map rendered an empty world because of it.
  assert(voxelGadgetCalls().every((l) => l.args.area === undefined), "a /voxels request without x1/z1/x2/z2 params sends NO area to the gadget (Number(null)===0 regression)");
  const voxelCallsBefore = voxelGadgetCalls().length;
  const voxelsRes2 = await fetch(`${base}/voxels?cursor=0&token=${consoleToken}`);
  assert(voxelsRes2.status === 200, "a second immediate GET /voxels succeeds");
  await new Promise((r) => setTimeout(r, 300)); // let any (unexpected) gadget call reach the mock's stderr log
  assert(voxelGadgetCalls().length === voxelCallsBefore, "a second immediate GET /voxels for the same page hits the 10s server-side cache instead of re-calling the gadget");

  console.log("\n5d. GET /worldmodel: token-authed like every other console route, returns the raw world-model JSON");
  const worldmodelUnauthed = await fetch(`${base}/worldmodel`);
  assert(worldmodelUnauthed.status === 401, `GET /worldmodel with no token is rejected (got ${worldmodelUnauthed.status})`);

  const worldmodelRes = await fetch(`${base}/worldmodel?token=${consoleToken}`);
  assert(worldmodelRes.status === 200, `GET /worldmodel with a valid token succeeds (got ${worldmodelRes.status})`);
  const worldmodelJson = await worldmodelRes.json();
  assert(typeof worldmodelJson.generatedAt === "string", "GET /worldmodel JSON has a generatedAt string");
  assert(Array.isArray(worldmodelJson.places) && Array.isArray(worldmodelJson.npcs) && Array.isArray(worldmodelJson.diagnostics), "GET /worldmodel JSON has places/npcs/diagnostics arrays (mock bridge has no ledger data, so all empty)");
  assert(worldmodelJson.bounds && typeof worldmodelJson.bounds.minX === "number", "GET /worldmodel JSON has a numeric bounds object");

  // --- 5s cache: a second immediate request reuses the same generatedAt
  // rather than rebuilding (proves the cache is actually short-circuiting,
  // not just "the world happens to not have changed") ---
  const worldmodelRes2 = await fetch(`${base}/worldmodel?token=${consoleToken}`);
  const worldmodelJson2 = await worldmodelRes2.json();
  assert(worldmodelJson2.generatedAt === worldmodelJson.generatedAt, "a second immediate GET /worldmodel reuses the cached model (same generatedAt) instead of rebuilding");

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

  console.log("\n7. Orders survive restarts: a pre-seeded queued order in state/orders.json is pushed into the scheduler on boot, reaches a director scene prompt, and flips to \"done\"");
  const port6 = 8904;
  const bridge6 = spawnNode([path.join(BRAIN_DIR, "test", "mock-bridge.mjs")], {
    MOCK_BRIDGE_PORT: String(port6),
    MOCK_BRIDGE_TOKEN: "test-token",
  });
  await wait(300);

  const orderStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-orders-boot-"));
  const seededTimestamp = new Date(Date.now() - 60000).toISOString();
  const seededOrderText = "Boot-replayed order: raise a watchtower at spawn.";
  const alreadyDoneTimestamp = new Date(Date.now() - 30000).toISOString();
  fs.writeFileSync(
    path.join(orderStateDir, "orders.json"),
    JSON.stringify(
      [
        { timestamp: alreadyDoneTimestamp, text: "an order finished before this restart", status: "done" },
        { timestamp: seededTimestamp, text: seededOrderText, status: "queued" },
      ],
      null,
      1
    )
  );

  const brain6 = spawnNode([path.join(BRAIN_DIR, "index.mjs")], {
    MCALIVE2_URL: `ws://127.0.0.1:${port6}`,
    MCALIVE2_TOKEN: "test-token",
    BRAIN_DEBOUNCE_MS: "300",
    BRAIN_DRY_RUN: "1",
    BRAIN_ENABLED: "1",
    BRAIN_LORE_REFRESH_MS: "600000",
    BRAIN_STATE_DIR: orderStateDir,
    BRAIN_LOG_JSON: "1",
    BRAIN_CONSOLE: "0",
  });

  const authed6 = await waitFor(brain6.logs, (l) => l.msg === "bridge_auth_ok", 5000);
  assert(authed6, "orders-boot-replay brain instance authenticated against the bridge");

  const gotRequeueLog = await waitFor(
    brain6.logs,
    (l) => l.msg === "order_requeued_on_boot" && l.timestamp === seededTimestamp,
    5000
  );
  assert(gotRequeueLog, "the pre-seeded queued order was requeued on boot (order_requeued_on_boot logged)");

  const gotOrderSceneOnBoot = await waitFor(
    brain6.logs,
    (l) => l.msg === "dry_run_director_turn" && typeof l.prompt === "string" && l.prompt.includes(seededOrderText),
    5000
  );
  assert(gotOrderSceneOnBoot, "the boot-replayed order's text reached a director scene prompt");

  const gotStatusUpdate = await waitFor(
    brain6.logs,
    (l) => l.msg === "order_status_updated" && l.timestamp === seededTimestamp && l.status === "done",
    5000
  );
  assert(gotStatusUpdate, "order_status_updated logged the boot-replayed order flipping to \"done\" once its scene completed");

  const ordersAfterBoot = JSON.parse(fs.readFileSync(path.join(orderStateDir, "orders.json"), "utf8"));
  const seededAfter = ordersAfterBoot.find((o) => o.timestamp === seededTimestamp);
  assert(seededAfter && seededAfter.status === "done", `boot-replayed order's status in orders.json is "done" after its scene completes (got ${seededAfter && seededAfter.status})`);
  const alreadyDoneAfter = ordersAfterBoot.find((o) => o.timestamp === alreadyDoneTimestamp);
  assert(alreadyDoneAfter && alreadyDoneAfter.status === "done", "an order already marked done before boot is left untouched (never requeued)");
  assert(
    !brain6.logs.some((l) => l.msg === "order_requeued_on_boot" && l.timestamp === alreadyDoneTimestamp),
    "the already-done order was never requeued on boot (only status \"queued\" entries are replayed)"
  );

  brain6.child.kill();
  bridge6.child.kill();

  console.log("\n8. Order status transitions queued -> done for a freshly-submitted (non-boot) order too");
  const port7 = 8905;
  const bridge7 = spawnNode([path.join(BRAIN_DIR, "test", "mock-bridge.mjs")], {
    MOCK_BRIDGE_PORT: String(port7),
    MOCK_BRIDGE_TOKEN: "test-token",
  });
  await wait(300);

  const freshOrderStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-orders-fresh-"));
  const freshConsoleToken = "fresh-order-token";
  const brain7 = spawnNode([path.join(BRAIN_DIR, "index.mjs")], {
    MCALIVE2_URL: `ws://127.0.0.1:${port7}`,
    MCALIVE2_TOKEN: "test-token",
    BRAIN_DEBOUNCE_MS: "300",
    BRAIN_DRY_RUN: "1",
    BRAIN_ENABLED: "1",
    BRAIN_LORE_REFRESH_MS: "600000",
    BRAIN_STATE_DIR: freshOrderStateDir,
    BRAIN_LOG_JSON: "1",
    BRAIN_CONSOLE: "1",
    BRAIN_CONSOLE_BIND: "127.0.0.1",
    BRAIN_CONSOLE_PORT: "0",
    BRAIN_CONSOLE_TOKEN: freshConsoleToken,
  });
  const authed7 = await waitFor(brain7.logs, (l) => l.msg === "bridge_auth_ok", 5000);
  assert(authed7, "fresh-order brain instance authenticated against the bridge");
  const listening7 = await waitFor(brain7.logs, (l) => l.msg === "console_listening", 5000);
  assert(listening7, "console_listening was logged for the fresh-order instance");
  const consolePort7 = brain7.logs.find((l) => l.msg === "console_listening").port;

  const freshOrderText = "Fresh order: build a small dock by the river.";
  const freshOrderRes = await fetch(`http://127.0.0.1:${consolePort7}/order?token=${freshConsoleToken}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ text: freshOrderText }),
  });
  const freshOrderJson = await freshOrderRes.json();
  assert(freshOrderRes.status === 200 && freshOrderJson.ok === true, "POST /order for the fresh order succeeded");

  const ordersJsonPath7 = path.join(freshOrderStateDir, "orders.json");
  const immediatelyAfterPost = JSON.parse(fs.readFileSync(ordersJsonPath7, "utf8"));
  const freshOrderRow = immediatelyAfterPost.find((o) => o.text === freshOrderText);
  assert(freshOrderRow && freshOrderRow.status === "queued", "the freshly-submitted order starts out with status \"queued\"");

  const gotFreshStatusUpdate = await waitFor(
    brain7.logs,
    (l) => l.msg === "order_status_updated" && l.timestamp === freshOrderJson.timestamp && l.status === "done",
    5000
  );
  assert(gotFreshStatusUpdate, "the fresh order's scene completed and flipped its status to \"done\"");
  const ordersAfterFresh = JSON.parse(fs.readFileSync(ordersJsonPath7, "utf8"));
  const freshOrderAfter = ordersAfterFresh.find((o) => o.timestamp === freshOrderJson.timestamp);
  assert(freshOrderAfter && freshOrderAfter.status === "done", `orders.json reflects the queued -> done transition (got ${freshOrderAfter && freshOrderAfter.status})`);

  brain7.child.kill();
  bridge7.child.kill();

  console.log("\n9. Budget exhaustion: loud-once WARN, GET /status, POST /budget/reset, order survives a budget skip, status-strip markup on / and /map");
  const port8 = 8906;
  const bridge8 = spawnNode([path.join(BRAIN_DIR, "test", "mock-bridge.mjs")], {
    MOCK_BRIDGE_PORT: String(port8),
    MOCK_BRIDGE_TOKEN: "test-token",
  });
  await wait(300);

  // Pre-seed usage.json already AT the (tiny) daily budget, so isOverBudget()
  // is true from the very first guardrail check - no need to wait on real
  // token accounting or the real clock. Also pre-seed orders.json with one
  // "queued" order whose event we'll submit via POST /order once the console
  // is up, so its scene gets skipped for budget exactly like the incident.
  const budgetStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "brain-budget-"));
  const todayUtc = new Date().toISOString().slice(0, 10);
  fs.writeFileSync(path.join(budgetStateDir, "usage.json"), JSON.stringify({ date: todayUtc, tokens: 1000 }, null, 1));

  const budgetConsoleToken = "budget-test-token";
  const brain8 = spawnNode([path.join(BRAIN_DIR, "index.mjs")], {
    MCALIVE2_URL: `ws://127.0.0.1:${port8}`,
    MCALIVE2_TOKEN: "test-token",
    BRAIN_DEBOUNCE_MS: "300",
    BRAIN_DRY_RUN: "1",
    BRAIN_ENABLED: "1",
    BRAIN_LORE_REFRESH_MS: "600000",
    BRAIN_STATE_DIR: budgetStateDir,
    BRAIN_LOG_JSON: "1",
    BRAIN_DAILY_TOKEN_BUDGET: "1000", // already exhausted by the pre-seeded usage.json above
    BRAIN_CONSOLE: "1",
    BRAIN_CONSOLE_BIND: "127.0.0.1",
    BRAIN_CONSOLE_PORT: "0",
    BRAIN_CONSOLE_TOKEN: budgetConsoleToken,
  });

  const authed8 = await waitFor(brain8.logs, (l) => l.msg === "bridge_auth_ok", 5000);
  assert(authed8, "budget-test brain authenticated against the bridge");
  const listening8 = await waitFor(brain8.logs, (l) => l.msg === "console_listening", 5000);
  assert(listening8, "console_listening was logged for the budget-test instance");
  const consolePort8 = brain8.logs.find((l) => l.msg === "console_listening").port;
  const base8 = `http://127.0.0.1:${consolePort8}`;

  // Submit an order while the budget is already exhausted - its scene will
  // be skipped for budget, exactly like the production incident's swallowed
  // operator order.
  const budgetOrderText = "Order submitted during a budget-exhausted window.";
  const budgetOrderRes = await fetch(`${base8}/order?token=${budgetConsoleToken}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ text: budgetOrderText }),
  });
  const budgetOrderJson = await budgetOrderRes.json();
  assert(budgetOrderRes.status === 200 && budgetOrderJson.ok === true, "POST /order succeeds even while the budget is exhausted (the order is accepted, just not actable yet)");

  // Give the mock bridge's whole scripted timeline (director scenes +
  // actor turns, several distinct guardrail checks) plus the order's own
  // scene a chance to all get skipped.
  const sawBudgetSkips = await waitFor(
    brain8.logs,
    (l) => brain8.logs.filter((x) => x.msg === "turn_skipped_budget_exceeded" || x.msg === "budget_exhausted").length >= 3,
    5000
  );
  assert(sawBudgetSkips, "multiple budget guardrail skips were logged across the scripted event timeline");

  assert(!brain8.logs.some((l) => l.msg === "director_scene_starting" || l.msg === "dry_run_director_turn"), "no director scene ever ran while the budget was exhausted");
  assert(!brain8.logs.some((l) => l.msg === "dry_run_actor_turn"), "no actor turn ever ran while the budget was exhausted");

  // --- 9a. loud-once WARN: exactly one "budget_exhausted" WARN, but
  // multiple quiet "turn_skipped_budget_exceeded" debug lines for the rest ---
  const loudBudgetWarns = brain8.logs.filter((l) => l.msg === "budget_exhausted" && l.level === "warn");
  assert(loudBudgetWarns.length === 1, `exactly one loud "budget_exhausted" WARN was logged all day, not one per skip (got ${loudBudgetWarns.length})`);
  if (loudBudgetWarns[0]) {
    const w = loudBudgetWarns[0];
    assert(typeof w.message === "string" && /exhausted/i.test(w.message), "the loud WARN's message says the budget is exhausted");
    assert(typeof w.message === "string" && w.message.includes("1000/1000"), "the loud WARN's message states tokens used vs. the limit");
    assert(typeof w.message === "string" && /UTC midnight/i.test(w.message), "the loud WARN's message says the world stays inert until UTC midnight");
    assert(typeof w.message === "string" && w.message.includes("BRAIN_DAILY_TOKEN_BUDGET"), "the loud WARN's message says how to raise the budget (BRAIN_DAILY_TOKEN_BUDGET)");
    assert(typeof w.resetsAt === "string" && w.resetsAt.length > 0, "the loud WARN carries a resetsAt timestamp");
  }
  const quietBudgetSkips = brain8.logs.filter((l) => l.msg === "turn_skipped_budget_exceeded");
  assert(quietBudgetSkips.length >= 2, `subsequent budget skips the same day logged the quiet line instead of another WARN (got ${quietBudgetSkips.length})`);
  // No kill-switch/rate-limit equivalent loud-once treatment was asked for -
  // "turn_skipped_kill_switch" (used elsewhere, e.g. section 3) always stays
  // a WARN every time; nothing in this section should turn that into a
  // once-per-day thing.
  assert(!brain8.logs.some((l) => l.msg === "kill_switch_exhausted"), "no kill-switch equivalent of the loud-once budget message was invented");

  // --- 9b. the operator_order submitted during the exhausted window stays
  // "queued" (never "done") in orders.json ---
  const ordersJsonPath8 = path.join(budgetStateDir, "orders.json");
  const ordersAfterBudgetSkip = JSON.parse(fs.readFileSync(ordersJsonPath8, "utf8"));
  const budgetOrderAfter = ordersAfterBudgetSkip.find((o) => o.timestamp === budgetOrderJson.timestamp);
  assert(budgetOrderAfter && budgetOrderAfter.status === "queued", `an operator_order scene skipped for budget leaves that order "queued" in orders.json (got ${budgetOrderAfter && budgetOrderAfter.status})`);
  assert(!brain8.logs.some((l) => l.msg === "order_status_updated" && l.timestamp === budgetOrderJson.timestamp && l.status === "done"), "the budget-skipped order was never marked \"done\"");
  assert(brain8.logs.some((l) => l.msg === "order_status_updated" && l.timestamp === budgetOrderJson.timestamp && l.status === "queued"), "the budget skip explicitly (re)confirms the order's status as \"queued\"");

  // --- 9c. GET /status: documented shape, token-authed ---
  const statusUnauthed = await fetch(`${base8}/status`);
  assert(statusUnauthed.status === 401, `GET /status with no token is rejected (got ${statusUnauthed.status})`);

  const statusRes = await fetch(`${base8}/status?token=${budgetConsoleToken}`);
  assert(statusRes.status === 200, `GET /status with a valid token succeeds (got ${statusRes.status})`);
  const statusJson = await statusRes.json();
  assert(statusJson.budget && statusJson.budget.used === 1000, `GET /status budget.used reflects the pre-seeded usage (got ${statusJson.budget && statusJson.budget.used})`);
  assert(statusJson.budget && statusJson.budget.limit === 1000, "GET /status budget.limit reflects BRAIN_DAILY_TOKEN_BUDGET");
  assert(statusJson.budget && statusJson.budget.remaining === 0, "GET /status budget.remaining is 0 when exhausted");
  assert(statusJson.budget && statusJson.budget.exhausted === true, "GET /status budget.exhausted is true");
  assert(typeof (statusJson.budget && statusJson.budget.resetsAt) === "string", "GET /status budget.resetsAt is a string");
  assert(statusJson.killSwitch === false, "GET /status killSwitch is false (no kill switch file here)");
  assert(statusJson.rateLimit && statusJson.rateLimit.maxPerMin > 0, "GET /status rateLimit.maxPerMin is present");
  assert(statusJson.scenes && typeof statusJson.scenes.running === "boolean", "GET /status scenes.running is a boolean");
  assert(statusJson.bridge && statusJson.bridge.connected === true, "GET /status bridge.connected is true (bridge is authed)");
  assert(typeof statusJson.uptimeSec === "number" && statusJson.uptimeSec >= 0, "GET /status uptimeSec is a non-negative number");

  // --- 9d. status-strip markup on both / and /map ---
  const pageWithStatus = await fetch(`${base8}/?token=${budgetConsoleToken}`);
  const pageWithStatusHtml = await pageWithStatus.text();
  assert(pageWithStatusHtml.includes('id="status-strip"') && pageWithStatusHtml.includes('id="status-budget"'), "GET / page has the status-strip markup");
  assert(pageWithStatusHtml.includes("fetch('/status')") || pageWithStatusHtml.includes('fetch("/status")'), "GET / page's inline script polls /status");
  assert(pageWithStatusHtml.includes('id="reset-budget-btn"'), "GET / page has a reset-budget button");
  assert(pageWithStatusHtml.includes("/budget/reset"), "GET / page's inline script can POST /budget/reset");

  const mapWithStatus = await fetch(`${base8}/map?token=${budgetConsoleToken}`);
  const mapWithStatusHtml = await mapWithStatus.text();
  assert(mapWithStatusHtml.includes('id="status-strip"') && mapWithStatusHtml.includes('id="status-budget"'), "GET /map page has the status-strip markup");
  assert(mapWithStatusHtml.includes("fetch('/status')") || mapWithStatusHtml.includes('fetch("/status")'), "GET /map page's inline script polls /status");
  assert(mapWithStatusHtml.includes('id="world-inert-banner"'), "GET /map page has the world-inert banner markup");

  // --- 9e. POST /budget/reset: zeroes the counter, persists, logged at WARN ---
  const resetRes = await fetch(`${base8}/budget/reset?token=${budgetConsoleToken}`, { method: "POST" });
  const resetJson = await resetRes.json();
  assert(resetRes.status === 200 && resetJson.ok === true, `POST /budget/reset succeeds (got ${resetRes.status} ${JSON.stringify(resetJson)})`);

  const usageAfterReset = JSON.parse(fs.readFileSync(path.join(budgetStateDir, "usage.json"), "utf8"));
  assert(usageAfterReset.tokens === 0, `usage.json's tokens counter is zeroed after POST /budget/reset (got ${usageAfterReset.tokens})`);

  const gotResetWarn = await waitFor(brain8.logs, (l) => l.msg === "budget_reset" && l.level === "warn", 2000);
  assert(gotResetWarn, "POST /budget/reset is logged at WARN");

  const statusAfterReset = await (await fetch(`${base8}/status?token=${budgetConsoleToken}`)).json();
  assert(statusAfterReset.budget.used === 0, "GET /status reflects the reset counter (used=0)");
  assert(statusAfterReset.budget.exhausted === false, "GET /status reports exhausted=false right after a reset");

  const resetUnauthed = await fetch(`${base8}/budget/reset`, { method: "POST" });
  assert(resetUnauthed.status === 401, `POST /budget/reset with no token is rejected (got ${resetUnauthed.status})`);

  brain8.child.kill();
  bridge8.child.kill();

  await wait(200);

  console.log(`\n${failures === 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("smoke test crashed:", e);
  process.exit(1);
});
