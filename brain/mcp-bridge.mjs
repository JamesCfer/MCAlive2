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

const URL_ = process.env.MCALIVE2_URL || "ws://127.0.0.1:8765";
const TOKEN = process.env.MCALIVE2_TOKEN || "change-me";

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
      return { content: [{ type: "text", text: JSON.stringify(data ?? { ok: true }, null, 1) }] };
    } catch (e) {
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
});
pt("sample_terrain", "Grid summary (heightmap, biome, notable features) for a rectangular area - the director's eyes when planning a build.", {
  x1: z.number(), z1: z.number(), x2: z.number(), z2: z.number(), world: z.string().optional(),
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

const transport = new StdioServerTransport();
await server.connect(transport);
console.error(`[mcalive2-mcp-bridge] ready, bridging to ${URL_}`);
