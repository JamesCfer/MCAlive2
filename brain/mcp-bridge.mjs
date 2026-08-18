#!/usr/bin/env node
// Estari MCP bridge: connects to the plugin's WebSocket and exposes the
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
//   ESTARI_URL    ws URL of the plugin bridge (default ws://127.0.0.1:8766)
//   ESTARI_TOKEN  auth token matching the Estari plugin's config

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const URL_ = process.env.ESTARI_URL || "ws://127.0.0.1:8766";
const TOKEN = process.env.ESTARI_TOKEN || "change-me";

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
      for (const [, p] of pending) p.reject(new Error("connection to Estari plugin lost"));
      pending.clear();
      if (!authed) reject(new Error(`could not connect to ${URL_} - is the server running with the Estari plugin?`));
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

const server = new McpServer({ name: "estari", version: "0.1.0" });

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

// --- NPCs ---
const scheduleEntry = z.object({
  start: z.number().describe("world time 0-23999 when this activity begins"),
  action: z.enum(["goto_home", "goto_work", "wander", "idle"]),
  radius: z.number().optional().describe("wander radius, default 8"),
});
pt("npc_spawn",
  "Create a living NPC (mannequin default, skin by username) with a name, role, home/work places, and a daily schedule the plugin runs automatically.",
  {
    id: z.string().describe("unique short id, e.g. 'mara-baker'"),
    name: z.string(),
    ...pos,
    entityType: z.string().optional().describe("default MANNEQUIN"),
    skin: z.string().optional().describe("Minecraft username whose skin a MANNEQUIN NPC wears"),
    personality: z.object({
      drive: z.number().min(-3).max(3), warmth: z.number().min(-3).max(3),
      boldness: z.number().min(-3).max(3), composure: z.number().min(-3).max(3),
    }).optional(),
    wants: z.array(z.object({ horizon: z.enum(["short", "long"]), text: z.string() })).optional(),
    home: posObj.optional(),
    work: posObj.optional(),
    schedule: z.array(scheduleEntry).optional(),
    faction: z.string().optional(),
  });
pt("npc_update", "Update an NPC's name, appearance, home, work, schedule, or faction.", {
  id: z.string(),
  name: z.string().optional(),
  entityType: z.string().optional(),
  skin: z.string().optional(),
  personality: z.object({
    drive: z.number().min(-3).max(3), warmth: z.number().min(-3).max(3),
    boldness: z.number().min(-3).max(3), composure: z.number().min(-3).max(3),
  }).optional(),
  wants: z.array(z.object({ horizon: z.enum(["short", "long"]), text: z.string() })).optional(),
  home: posObj.optional(),
  work: posObj.optional(),
  schedule: z.array(scheduleEntry).optional(),
  faction: z.string().optional(),
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

const transport = new StdioServerTransport();
await server.connect(transport);
console.error(`[estari-mcp-bridge] ready, bridging to ${URL_}`);
