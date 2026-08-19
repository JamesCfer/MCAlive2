#!/usr/bin/env node
// MCAlive2 MCP bridge: connects to the plugin's WebSocket and exposes the
// actuator/ledger/info command set from DESIGN.md ("Plugin subsystems") as
// MCP tools over stdio, for the Claude Agent SDK to use. Pure passthrough -
// every tool here is a 1:1 forward of a plugin bridge command; this file
// never implements game logic itself (pattern reference:
// minecraftalive/mcp/server.mjs).
//
// Deliberately absent (by design, not by a deny-list here): broadcast/
// title/actionbar narration tools and set_time. They simply do not exist
// as plugin bridge commands, so there is nothing to forward.
//
// Env vars:
//   MCALIVE2_URL    ws URL of the plugin bridge (default ws://127.0.0.1:8765)
//   MCALIVE2_TOKEN  auth token matching the MCAlive2 plugin's config

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { buildWorldModel, formatWorldOverview } from "./lib/worldmodel.mjs";
import { appendEntry as chronicleAppend, readFile as chronicleReadFile, rewriteFile as chronicleRewrite } from "./lib/chronicle.mjs";
import { capToolResultText, resolveMaxToolResultChars } from "./lib/tool-result-cap.mjs";

const URL_ = process.env.MCALIVE2_URL || "ws://127.0.0.1:8765";
const TOKEN = process.env.MCALIVE2_TOKEN || "change-me";
// See lib/tool-result-cap.mjs - this is the single biggest lever against
// the token blowout this file's tool results feed straight into an agent
// turn that re-sends its whole context on every subsequent step. Read here
// (not via lib/config.mjs) because this process is spawned standalone by
// the Agent SDK for each director/actor turn - lib/director-turn.mjs and
// lib/actor-turn.mjs explicitly forward BRAIN_MAX_TOOL_RESULT_CHARS into
// this process's env rather than relying on env inheritance through the SDK.
const MAX_TOOL_RESULT_CHARS = resolveMaxToolResultChars(process.env);

// ---------------- WebSocket bridge client ----------------

let ws = null;
let wsReady = null; // promise resolving when authed
const pending = new Map(); // id -> {resolve, reject}
let seq = 0;

function connect() {
  if (wsReady) return wsReady;
  wsReady = new Promise((resolve, reject) => {
    const sock = new WebSocket(URL_);
    const authId = "auth-" + Math.random().toString(36).slice(2);
    let authed = false;

    sock.onopen = () => {
      sock.send(JSON.stringify({ id: authId, cmd: "auth", args: { token: TOKEN } }));
    };
    sock.onmessage = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.event) {
        // The director/actor agent turns never consume pushed events over
        // this connection - that is brain/lib/bridge-client.mjs's job on a
        // separate socket. Ignore events here.
        return;
      }
      if (msg.id === authId) {
        if (msg.ok) { authed = true; ws = sock; resolve(sock); }
        else { reject(new Error("auth failed: " + msg.error)); }
        return;
      }
      const p = pending.get(msg.id);
      if (p) {
        pending.delete(msg.id);
        if (msg.ok) p.resolve(msg.data ?? {});
        else p.reject(new Error(msg.error || "unknown error"));
      }
    };
    sock.onclose = () => {
      ws = null;
      wsReady = null;
      for (const [, p] of pending) p.reject(new Error("connection to MCAlive2 plugin lost"));
      pending.clear();
      if (!authed) reject(new Error(`could not connect to ${URL_} - is the server running with the MCAlive2 plugin?`));
    };
    sock.onerror = () => { /* onclose fires after */ };
  });
  return wsReady;
}

async function call(cmd, args = {}) {
  await connect();
  const id = "r" + (++seq);
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, cmd, args }));
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id);
        reject(new Error("timed out waiting for server reply to " + cmd));
      }
    }, 15000);
  });
}

// ---------------- MCP server ----------------

const server = new McpServer({ name: "mcalive2", version: "0.1.0" });

function tool(name, description, shape, handler) {
  server.registerTool(name, { description, inputSchema: shape }, async (args) => {
    try {
      const data = await handler(args ?? {});
      const text = capToolResultText(data ?? { ok: true }, { maxChars: MAX_TOOL_RESULT_CHARS });
      return { content: [{ type: "text", text }] };
    } catch (e) {
      // Errors are exempt from the cap (see lib/tool-result-cap.mjs) - the
      // director/actor needs the FULL error text (e.g. gadget_define's
      // javac diagnostics) to iterate.
      return { content: [{ type: "text", text: "ERROR: " + e.message }], isError: true };
    }
  });
}

// passthrough tool: same command name on the plugin side
function pt(name, description, shape) {
  tool(name, description, shape, (args) => call(name, args));
}

const pos = {
  x: z.number(), y: z.number(), z: z.number(),
  world: z.string().optional().describe("world name; defaults to the main world"),
};
const posObj = z.object(pos);

// --- world ---
pt("set_block", "Set a single block.", { ...pos, material: z.string().describe("e.g. stone, oak_planks, chest") });
pt("get_block", "Read the block at a position.", pos);
pt("fill_region", "Fill a cuboid region with a material (capped volume, plugin-side). Set hollow=true for walls-only, and use material=air to clear.", {
  x1: z.number(), y1: z.number(), z1: z.number(),
  x2: z.number(), y2: z.number(), z2: z.number(),
  material: z.string(),
  hollow: z.boolean().optional(),
  world: z.string().optional(),
});
pt("build_blueprint", "Paste a JSON blueprint (list of {dx,dy,dz,material[,data]} relative to an origin) - the unit of AI construction.", {
  ...pos,
  blocks: z.array(z.object({
    dx: z.number(), dy: z.number(), dz: z.number(),
    material: z.string(),
    data: z.string().optional(),
  })),
  settle: z.enum(["surface"]).optional().describe("if \"surface\", the paste is shifted vertically so its lowest layer sits on the median footprint surface (see scan_area) instead of pasting at the literal y given"),
  clearAbove: z.boolean().optional().describe("clear the blueprint's bounding box (to air) before pasting, so structures don't paste into existing terrain/clutter"),
});
pt("sample_terrain", "Grid summary (heightmap, biome, notable features) for a rectangular area - the director's eyes when planning a build.", {
  x1: z.number(), z1: z.number(), x2: z.number(), z2: z.number(), world: z.string().optional(),
});
pt("scan_area", "Build-resolution surface scanner for a footprint - use before placing any structure. Returns {minY,maxY,medianY,flat,columns?}: columns (capped at 4096, per-column material/height detail only for areas <= 256 columns) let you see exactly what's underfoot. Pass yHint near your intended build level - on floating terrain the scanner resolves to the surface NEAREST that y (e.g. a floating island's top, not the ground far below).", {
  x1: z.number(), z1: z.number(), x2: z.number(), z2: z.number(),
  world: z.string().optional(),
  yHint: z.number().optional().describe("y level to search near; the scanner finds the surface nearest this height (important on floating terrain, where there may be multiple candidate surfaces)"),
});
pt("spawn_entity", "Spawn a plain (non-NPC) entity, e.g. ZOMBIE, SHEEP, IRON_GOLEM.", { ...pos, type: z.string() });
pt("remove_entity", "Remove a loaded entity by uuid.", { uuid: z.string() });
pt("spawn_particles", "Particle burst at a position.", { ...pos, particle: z.string(), count: z.number().optional(), spread: z.number().optional() });
pt("play_sound", "Play a sound at a position or to a player.", {
  sound: z.string(), player: z.string().optional(),
  x: z.number().optional(), y: z.number().optional(), z: z.number().optional(), world: z.string().optional(),
  volume: z.number().optional(), pitch: z.number().optional(),
});
pt("set_weather", "Set weather to clear, rain, or thunder.", { weather: z.enum(["clear", "rain", "thunder"]), world: z.string().optional() });
pt("create_explosion", "Trigger a single explosion at a point - an operator-order set-piece tool.", {
  ...pos,
  power: z.number().describe("explosion power, vanilla TNT is roughly 4"),
  fire: z.boolean().optional().describe("whether the explosion sets fire, default false"),
  breakBlocks: z.boolean().optional().describe("whether the explosion breaks blocks, default true"),
});
pt("strike_lightning", "Run a whole timed lightning-strike show over an area - an operator-order set-piece tool. Runs plugin-side over time and returns {sequenceId} immediately; a 'sequence_done' event ({sequenceId, kind, center, count}) is pushed to the director when the show finishes - plan the next phase to trigger on that event rather than sleeping.", {
  x: z.number(), z: z.number(), world: z.string().optional(),
  count: z.number().describe("number of strikes"),
  radiusBlocks: z.number().describe("radius around x,z each strike is randomized within"),
  intervalTicks: z.number().describe("ticks between strikes (20 ticks = 1 second)"),
  explosionPower: z.number().optional().describe("optional explosion power to accompany each strike"),
});
pt("move_region", "Relocate a cuboid of blocks by an offset - an operator-order set-piece tool (e.g. lifting a build, shifting terrain).", {
  x1: z.number(), y1: z.number(), z1: z.number(),
  x2: z.number(), y2: z.number(), z2: z.number(),
  dx: z.number(), dy: z.number(), dz: z.number(),
  clearSource: z.boolean().optional().describe("whether to clear (set to air) the source region after moving, default true"),
  world: z.string().optional(),
});

// --- NPCs ---
const scheduleEntry = z.object({
  start: z.number().describe("world time 0-23999 when this activity begins"),
  action: z.enum(["goto_home", "goto_work", "wander", "idle"]),
  radius: z.number().optional().describe("wander radius, default 8"),
});
pt("npc_spawn",
  "Create a living NPC (mannequin default, skin by username) with a name, role, home/work places, and a daily schedule the plugin runs automatically. " +
  "Personality, wants, and faction are narrative data the plugin does not store here - record them with ledger_put on the npcs collection instead.",
  {
    id: z.string().describe("unique short id, e.g. 'mara-baker'"),
    name: z.string(),
    ...pos,
    entityType: z.string().optional().describe("default MANNEQUIN"),
    skin: z.string().optional().describe("Minecraft username whose skin a MANNEQUIN NPC wears"),
    home: posObj.optional(),
    work: posObj.optional(),
    schedule: z.array(scheduleEntry).optional(),
    snap: z.boolean().optional().describe("ground-snap the NPC to the nearest valid standing spot within +-12 of the requested y (default true); pass false to trust the exact coordinates given"),
  });
pt("npc_update",
  "Update an NPC's name, appearance, home, work, or schedule. " +
  "Personality, wants, and faction are narrative data the plugin does not store here - record them with ledger_put on the npcs collection instead.",
  {
  id: z.string(),
  name: z.string().optional(),
  entityType: z.string().optional(),
  skin: z.string().optional(),
  home: posObj.optional(),
  work: posObj.optional(),
  schedule: z.array(scheduleEntry).optional(),
});
pt("npc_remove", "Remove an NPC permanently.", { id: z.string() });
pt("npc_revive", "Bring a dead NPC back to life (dead NPCs never auto-respawn). Use sparingly - death is meant to be meaningful.", {
  id: z.string(),
  x: z.number().optional(), y: z.number().optional(), z: z.number().optional(),
  world: z.string().optional(),
});
pt("npc_say", "Make an NPC speak. This is the ONLY dialogue channel - chat is exclusively for dialogue.", { id: z.string(), text: z.string() });
pt("npc_walk_to", "Walk an NPC to a position (pauses its daily routine for holdSeconds).", {
  id: z.string(), ...pos,
  speed: z.number().optional(),
  holdSeconds: z.number().optional(),
  snap: z.boolean().optional().describe("ground-snap the destination to the nearest valid standing spot within +-12 of the requested y (default true); pass false to trust the exact coordinates given"),
});
pt("npc_look_at", "Turn an NPC to face a position or player.", { id: z.string(), x: z.number().optional(), y: z.number().optional(), z: z.number().optional(), player: z.string().optional() });
pt("npc_equip", "Equip an NPC with an item in a given slot.", { id: z.string(), slot: z.enum(["hand", "offhand", "head", "chest", "legs", "feet"]), material: z.string() });
pt("npc_pose", "Set a small physical pose/animation beat for an NPC (e.g. wave, sit, point).", { id: z.string(), pose: z.string() });
pt("npc_head_check", "Check whether the item a player holds is a dead NPC's head - the revival token. Returns npcId/npcName/dead.", {
  player: z.string(),
  slot: z.number().int().optional().describe("hotbar slot 0-8; defaults to the player's main hand"),
});

// --- players ---
pt("list_players", "Online players with location, health, hunger, level, held item.", {});
pt("give_item", "Give a player an item, optionally with a custom name and lore.", {
  player: z.string(), material: z.string(), amount: z.number().optional(),
  displayName: z.string().optional().describe("MiniMessage formatting allowed"),
  lore: z.string().optional(),
});
pt("apply_effect", "Apply a potion effect to a player.", {
  player: z.string(), effect: z.string(), seconds: z.number().optional(), amplifier: z.number().optional(),
});

// --- info ---
pt("get_server_info", "Server version, worlds with time/weather, and online player count.", {});

// --- ledger ---
pt("ledger_put", "Create or update a ledger record in a typed collection (npcs, factions, places, quests, facts, promises, players).", {
  collection: z.enum(["npcs", "factions", "places", "quests", "facts", "promises", "players"]),
  record: z.record(z.string(), z.any()),
});
pt("ledger_get", "Read one ledger record by id.", {
  collection: z.enum(["npcs", "factions", "places", "quests", "facts", "promises", "players"]),
  id: z.string(),
});
pt("ledger_query", "Query a ledger collection with a subset-match filter.", {
  collection: z.enum(["npcs", "factions", "places", "quests", "facts", "promises", "players"]),
  filter: z.record(z.string(), z.any()).optional(),
});
pt("ledger_delete", "Delete a ledger record by id.", {
  collection: z.enum(["npcs", "factions", "places", "quests", "facts", "promises", "players"]),
  id: z.string(),
});
pt("npc_context", "The knowledge-isolation tool: returns an NPC's own sheet plus ONLY the facts whose knownBy includes that NPC, their faction, or \"all\" - enforced plugin-side so an actor can never metagame.", {
  npcId: z.string(),
});

// --- chronicle ---
// Brain-local, file-backed tools (NOT bridge passthroughs - the plugin
// never sees these): the director's D&D-style campaign notebook under
// brain/chronicle/ (see lib/chronicle.mjs). The division of labor: the
// LEDGER is machine state (structured records tools query), the CHRONICLE
// is prose canon - world-bible.md holds lasting truths/places/legends,
// arcs.md holds active storylines with a status (brewing/active/resolved),
// and sessions/YYYY-MM-DD.md is the permanent dated journal of what
// actually happened.
tool("chronicle_append",
  "Append one timestamped, headed prose entry to the campaign chronicle (your D&D-style notes - the ledger holds machine state, the chronicle holds the STORY as written words). " +
  "file \"session\" appends to today's dated journal (what happened this scene - end every consequential scene with one of these); " +
  "\"world-bible\" records a LASTING truth - a place, a legend, a permanent change to the world; " +
  "\"arcs\" adds to the active storylines (give each arc a status: brewing/active/resolved).", {
  file: z.enum(["world-bible", "arcs", "session"]),
  heading: z.string().describe("short entry title, e.g. 'The Dry Well' or 'Steve strikes a bargain'"),
  text: z.string().describe("the prose entry - written for your future self, who will read it with no other memory of this scene"),
}, (args) => {
  chronicleAppend(args.file, args.heading, args.text);
  return { ok: true, file: args.file };
});
tool("chronicle_read",
  "Read a chronicle file's full text: \"world-bible\", \"arcs\", \"session\" (today's journal), or \"session:YYYY-MM-DD\" (a past day's journal). " +
  "Pass tail to read only the last N characters of a long file. Your system prompt already carries a compact digest - use this when you need the full prose.", {
  file: z.string().describe("world-bible | arcs | session | session:YYYY-MM-DD"),
  tail: z.number().optional().describe("return only the last N characters"),
}, (args) => {
  const text = chronicleReadFile(args.file, { tail: args.tail });
  return { file: args.file, chars: text.length, text: text || "(empty)" };
});
tool("chronicle_rewrite",
  "REPLACE the ENTIRE contents of world-bible or arcs - for periodic compaction ONLY (merging duplicate entries, dropping resolved arcs, tightening prose). " +
  "This destroys the file's previous text, so read it first and carry every still-true fact into the rewrite. " +
  "Session journals can NEVER be rewritten - they are the permanent record; this tool refuses them.", {
  file: z.enum(["world-bible", "arcs"]),
  fullText: z.string().describe("the complete new contents of the file"),
}, (args) => {
  chronicleRewrite(args.file, args.fullText);
  return { ok: true, file: args.file, chars: args.fullText.length };
});

// --- world model ---
// Aggregates ledger_query(places/npcs) + scan_area + a few budgeted
// get_block probes (brain/lib/worldmodel.mjs) into a compact text digest -
// the text-only director's "what exists and what's wrong right now" view.
// Not a passthrough (unlike every other tool above): it composes several
// existing bridge commands brain-side rather than forwarding one 1:1.
// Registered directly (not via the tool()/pt() helpers above) because its
// result is a plain compact TEXT digest for the director to read, not a
// JSON blob - tool() always JSON.stringifies its handler's return value.
server.registerTool("world_overview", {
  description: "Build a snapshot of the live world (places, NPCs, terrain) from the ledger and a coarse terrain scan, and return a compact text digest: counts, every place with its coords/size/builtBy, an NPC alive/dead summary with flagged NPCs called out, an optional top-down ASCII map (detail:\"full\"), and a PROBLEMS section (worst first) flagging floating/buried structures and floating/buried NPCs. Call this after building or whenever something looks wrong, fix what it reports, then call it again to confirm.",
  inputSchema: {
    area: z.object({ x1: z.number(), z1: z.number(), x2: z.number(), z2: z.number() }).optional()
      .describe("explicit terrain-scan footprint; defaults to the bounding box of all recorded places (or a default footprint around world origin if there are none yet)"),
    detail: z.enum(["summary", "full"]).optional().describe("\"full\" adds a top-down ASCII map; default \"summary\" omits it for a shorter digest"),
  },
}, async (args) => {
  try {
    // This tool runs in the per-query MCP process, which does NOT receive the
    // brain's pushed entity_positions telemetry. Pull a live snapshot on demand
    // from the position-tracker gadget so the director sees NPCs where their
    // bodies actually are, not just their ledger-home coords. Degrade silently
    // to ledger-home if the gadget isn't available (server not gadget-capable).
    const npcPositions = await liveNpcPositions();
    const model = await buildWorldModel(call, {
      now: new Date().toISOString(),
      area: (args ?? {}).area,
      npcPositions,
    });
    // formatWorldOverview() is designed to already stay comfortably under
    // the cap on its own (see lib/worldmodel.mjs's LIST_CAP/FREE_TEXT_MAX/
    // MAP_MAX_WIDTH/MAP_MAX_HEIGHT) - this is a defense-in-depth safety net,
    // not the primary compaction mechanism.
    const text = capToolResultText(formatWorldOverview(model, { detail: (args ?? {}).detail }), { maxChars: MAX_TOOL_RESULT_CHARS });
    return { content: [{ type: "text", text }] };
  } catch (e) {
    return { content: [{ type: "text", text: "ERROR: " + e.message }], isError: true };
  }
});

// Fetch a live NPC-position map {id -> {world,x,y,z,at}} from the position-tracker
// gadget's snapshot mode. Returns {} on any failure so the world model falls back
// to ledger-home positions exactly as before.
async function liveNpcPositions() {
  try {
    const snap = await call("gadget:position-tracker", { snapshot: true });
    const out = {};
    const now = Date.now();
    for (const n of (snap && snap.npcs) || []) {
      if (n && n.id) out[n.id] = { world: n.world, x: n.x, y: n.y, z: n.z, at: now };
    }
    return out;
  } catch {
    return {};
  }
}

// --- formulas ---
// Formulas are runtime-authored, reusable tools: recipes stored plugin-side
// as a sequence of steps over EXISTING bridge commands (never new game
// logic), with ${...} expressions (params, i, rand(a,b), randint(a,b),
// floor) evaluated per step per iteration. Build once ("a lightning storm
// with params for center/radius/count/explosion power"), then run it again
// and again with different arguments instead of re-composing the same
// multi-step spectacle from scratch every time.
const formulaStep = z.object({
  cmd: z.string().describe("bridge command to run for this step, e.g. strike_lightning, spawn_entity, set_block"),
  args: z.record(z.string(), z.any()).describe("args for cmd; string values may contain ${...} expressions (params, i, rand(a,b), randint(a,b), floor)"),
  delayTicks: z.number().optional().describe("ticks to wait before running this step"),
});
pt("formula_define",
  "Define (or redefine) a reusable formula: a named, parameterized recipe over existing bridge commands. " +
  "Use this instead of wishing for a tool that doesn't exist - compose the capability once from primitives you already have " +
  "(a lightning storm, a meteor shower, a fountain, a mob ambush are all formulas), give it clear params, and run it again later with different inputs. " +
  "Check formula_list first; prefer refining an existing formula over duplicating one.", {
  id: z.string().describe("unique short id, e.g. 'lightning-storm'"),
  description: z.string(),
  params: z.array(z.object({
    name: z.string(),
    default: z.any().optional(),
  })).describe("named parameters callers supply via formula_run's args, referenced in steps as ${paramName}"),
  steps: z.array(formulaStep),
  loop: z.object({
    count: z.union([z.number(), z.string()]).describe("number or a ${...} expression, e.g. \"${count}\""),
    intervalTicks: z.union([z.number(), z.string()]).describe("number or a ${...} expression"),
  }).optional().describe("repeat the whole step sequence this many times, spaced intervalTicks apart; ${i} is the 0-based iteration index inside step expressions"),
});
pt("formula_run", "Run a previously defined formula with concrete argument values for its params. Runs plugin-side over time and returns {runId} immediately; a 'sequence_done' event ({sequenceId, kind:\"formula:<id>\", count}) is pushed when it finishes, and a 'formula_error' event ({runId, formulaId, step, error}) is pushed if a step fails - plan the next phase off those events rather than sleeping.", {
  id: z.string(),
  args: z.record(z.string(), z.any()).optional().describe("values for the formula's declared params; params with a default may be omitted"),
});
pt("formula_list", "List all defined formulas (id + description) - your growing spellbook. Check this before defining a new formula.", {});
pt("formula_get", "Read one formula's full definition (params, steps, loop) by id.", { id: z.string() });
pt("formula_delete", "Delete a formula definition by id.", { id: z.string() });

// --- NPC jobs ---
// NPCs can physically work: walk to a real station, withdraw REAL, finite
// items from a real chest, work for a duration, and deposit real outputs -
// making a village genuinely produce and consume rather than just look busy.
const jobItem = z.object({ material: z.string(), count: z.number() });
pt("npc_assign_job",
  "Send an NPC to physically work a job: walk to a station, withdraw real inputs from a real inventory chest (finite - can run out), " +
  "work for workTicks, then deposit real outputs to an output chest, repeating up to `repeats` times. " +
  "Use this to make villages genuinely produce and consume - a smithy that turns ore into tools, a bakery that turns wheat into bread - " +
  "not scenery. React in story when npc_job_blocked reports resources ran out.", {
  npcId: z.string(),
  job: z.object({
    station: posObj.describe("where the NPC stands and works"),
    inputChest: posObj.optional().describe("chest the NPC withdraws inputs from, if this job consumes items"),
    outputChest: posObj.optional().describe("chest the NPC deposits outputs into, if this job produces items"),
    inputs: z.array(jobItem).describe("items consumed per work cycle; empty if the job produces without consuming"),
    outputs: z.array(jobItem).describe("items produced per work cycle; empty if the job consumes without producing"),
    workTicks: z.number().describe("ticks spent working per cycle before outputs are produced"),
    repeats: z.number().describe("number of work cycles to run before the job ends"),
    workSound: z.string().optional().describe("sound to play periodically while working, e.g. block.anvil.use"),
  }),
});
pt("npc_job_cancel", "Cancel an NPC's assigned job, stopping it wherever it currently is in the cycle.", { npcId: z.string() });

// --- behavior programs ---
// Behavior programs are the "author once and walk away" layer above jobs: give
// a CREW of NPCs a goal (gather real wood, build a registered blueprint
// log-by-log, deposit the haul, loop) and the PLUGIN runs it indefinitely with
// zero AI involvement, surviving restarts. Never poll or re-issue a running
// program - a 'behavior_done' event ({programId, name}) wakes you on
// completion and a 'behavior_blocked' event ({programId, name, npcId, step,
// reason}) wakes you when a crew hits a wall the world can't resolve
// (no_resources, missing_inputs, cant_reach, crew_dead, no_blueprint); the
// program pauses itself until you behavior_resume (or _update/_delete) it.
const behaviorItem = z.object({ material: z.string(), count: z.number() });
const behaviorStep = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("gather"),
    material: z.string().describe("block to break, e.g. OAK_LOG - the block is REALLY removed from the world"),
    count: z.number().describe("how many each crew member gathers into its carrying inventory"),
    radius: z.number().describe("search radius in blocks around the anchor"),
    anchor: z.object({ x: z.number(), y: z.number(), z: z.number() }).describe("center of the search area, e.g. the heart of a forest"),
    breakTicks: z.number().optional().describe("ticks to visibly break each block (dig sounds + crack particles), default 60"),
  }),
  z.object({
    type: z.literal("build"),
    blueprintId: z.string().describe("a blueprint previously stored with blueprint_register"),
    supplyChest: z.object({ x: z.number(), y: z.number(), z: z.number() }).optional()
      .describe("chest the crew restocks building materials from (real, finite items); omit to build purely from what they carry"),
    placeTicks: z.number().optional().describe("ticks between placed blocks, default 40 - slower reads as more alive"),
    blocksPerTrip: z.number().optional().describe("materials withdrawn per supply-chest trip, default 8"),
  }),
  z.object({
    type: z.literal("deposit"),
    chest: z.object({ x: z.number(), y: z.number(), z: z.number() }),
    items: z.union([z.literal("all"), z.array(behaviorItem)]).describe('"all" empties the carrying inventory into the chest; or list specific {material,count}s'),
  }),
  z.object({
    type: z.literal("withdraw"),
    chest: z.object({ x: z.number(), y: z.number(), z: z.number() }),
    items: z.array(behaviorItem).describe("all-or-nothing withdrawal into the carrying inventory; short stock blocks the program"),
  }),
  z.object({
    type: z.literal("work_station"),
    station: z.object({ x: z.number(), y: z.number(), z: z.number() }),
    workTicks: z.number().optional().describe("ticks spent working at the station, default 100"),
    sound: z.string().optional().describe("sound played periodically while working, default block.anvil.use"),
  }),
  z.object({
    type: z.literal("goto"),
    x: z.number(), y: z.number(), z: z.number(),
  }),
  z.object({
    type: z.literal("wait"),
    ticks: z.number(),
  }),
  z.object({
    type: z.literal("follow"),
    npcId: z.string().describe("NPC to follow (re-targeted every 2 seconds)"),
    ticks: z.number().describe("how long to follow"),
    distance: z.number().optional().describe("stay within this many blocks, default 3"),
  }),
]);
pt("behavior_create",
  "Author a behavior program ONCE and walk away: a crew of NPCs plus an ordered list of steps the plugin runs entirely on its own - " +
  "no AI in the loop, surviving restarts. gather removes REAL blocks from the world into each NPC's carrying inventory; build places them " +
  "back log-by-log against a blueprint from blueprint_register (visible, slow, alive - use THIS for NPC construction, and reserve " +
  "build_blueprint for instant director set-pieces); deposit/withdraw move real items through real chests. Set loop:true for a standing " +
  "industry (gather -> deposit, forever). Then STAND BACK: wait for behavior_done or behavior_blocked - never poll or re-issue a running " +
  "program; behavior_status is the cheap progress query if a player asks.", {
  id: z.string().describe("unique short id, e.g. 'logging-crew'"),
  name: z.string().describe("human-readable name, e.g. 'Millbrook logging crew'"),
  npcIds: z.array(z.string()).describe("the crew - every NPC works the same steps in parallel, sharing claims so they never double-work"),
  loop: z.boolean().optional().describe("true = wrap back to step 0 forever; false (default) = run once and fire behavior_done"),
  steps: z.array(behaviorStep),
});
pt("behavior_update",
  "Replace a program's steps and/or crew (cursors reset to step 0; what each NPC already carries survives). " +
  "Use this to CHANGE what a crew does - never to nudge a program that is already running fine.", {
  id: z.string(),
  name: z.string().optional(),
  npcIds: z.array(z.string()).optional(),
  loop: z.boolean().optional(),
  steps: z.array(behaviorStep).optional(),
});
pt("behavior_pause", "Pause a behavior program (crew stops mid-step; chunk tickets release). Resume picks up exactly where it left off.", { id: z.string() });
pt("behavior_resume", "Resume a paused program - including one that paused itself via behavior_blocked, after you've fixed what blocked it (restocked the chest, replanted the forest...).", { id: z.string() });
pt("behavior_delete", "Delete a behavior program permanently.", { id: z.string() });
pt("behavior_status",
  "Cheap progress query: per program - id, name, paused, crew size, and each crew member's current step index/type, carrying summary, " +
  "and blueprint progress (\"37/220 placed\") when building. Use this when a player asks how the work is going; do NOT poll it on a timer - " +
  "behavior_done/behavior_blocked events already wake you when something needs you.", {
  id: z.string().optional().describe("one program's status; omit for all programs"),
});
pt("blueprint_register",
  "Store a named blueprint (an origin plus the same {dx,dy,dz,material[,data]} block list build_blueprint takes) for NPC crews to " +
  "build log-by-log via a behavior program's build step. Registering does NOT place anything - the crew does, block by visible block.", {
  id: z.string().describe("unique short id, e.g. 'millbrook-granary'"),
  origin: z.object({ x: z.number(), y: z.number(), z: z.number() }).describe("world position the block offsets are relative to"),
  blocks: z.array(z.object({
    dx: z.number(), dy: z.number(), dz: z.number(),
    material: z.string(),
    data: z.string().optional(),
  })),
});
pt("blueprint_delete", "Delete a registered blueprint by id.", { id: z.string() });

// --- gadgets ---
// Gadgets are the third rung of the capability ladder (tool -> formula ->
// gadget): real Java source, compiled ON the running server and registered
// immediately as bridge command "gadget:<id>" - no plugin release, no
// restart. Reserve them for genuinely new primitives (new physics, new
// senses, algorithms like pathfinding) that cannot be composed from existing
// tools/formulas.
pt("gadget_define",
  "Inject a NEW PRIMITIVE into the running server as Java source - no plugin release, no restart. " +
  "Source is a Java class implementing the plugin's GadgetContract: " +
  "`public com.google.gson.JsonObject run(com.google.gson.JsonObject args, dev.celestia.mcalive2.gadget.GadgetContext ctx)` " +
  "- ctx exposes plugin(), server(), world(String), a dispatcher-invoke(cmd, args) helper to call other bridge commands, and scheduler helpers. " +
  "Compiled on the server the moment you call this; on success it registers immediately as bridge command \"gadget:<id>\" and persists across restarts. " +
  "On a compile failure you get back an ERROR RESULT whose message contains the full javac diagnostics - read them, fix the source, and call gadget_define " +
  "again with the same id to redefine it. Keep gadgets small and single-purpose; check gadget_list before writing a new one - you may already have it.", {
  id: z.string().describe("unique short id, e.g. 'pathfind-flat-route'"),
  source: z.string().describe("full Java source for a class implementing GadgetContract's run(JsonObject, GadgetContext)"),
  description: z.string(),
});
pt("gadget_run", "Run a previously defined gadget with concrete argument values - equivalent to calling bridge command \"gadget:<id>\" directly. Returns whatever the gadget's run() returns.", {
  id: z.string(),
  args: z.record(z.string(), z.any()).optional(),
});
pt("gadget_list", "List all defined gadgets (id + description) - check this before writing a new gadget in case you already have the capability.", {});
pt("gadget_get", "Read one gadget's full definition (id, description, source) by id.", { id: z.string() });
pt("gadget_delete", "Delete a gadget definition by id.", { id: z.string() });

const transport = new StdioServerTransport();
await server.connect(transport);
console.error(`[mcalive2-mcp-bridge] ready, bridging to ${URL_}`);
