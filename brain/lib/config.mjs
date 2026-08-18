// Central place for env-driven configuration and the pure helpers around it,
// so the smoke test can import and assert on them without booting the whole
// service. Mirrors gm/lib/config.mjs's shape but renamed BRAIN_* and with
// the director/actor split DESIGN.md calls for.

import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const BRAIN_ROOT = path.resolve(__dirname, "..");

function num(name, fallback) {
  const v = process.env[name];
  if (v === undefined || v === "") return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function bool01(name, fallback) {
  const v = process.env[name];
  if (v === undefined || v === "") return fallback;
  return v === "1" || v.toLowerCase() === "true";
}

// The name the estari MCP tool server is registered under. Tool names as
// seen by the model are namespaced "mcp__<this>__<toolName>".
export const MCP_SERVER_NAME = "estari";

// The full actuator/ledger/info command set exposed by brain/mcp-bridge.mjs,
// per DESIGN.md "Plugin subsystems" section. This is the DIRECTOR's toolset
// in full - "deliberately absent: broadcast/title/actionbar narration tools
// and set_time", which is enforced plugin-side (those commands simply don't
// exist on the bridge), not by a brain-side deny-list.
export const ALL_TOOLS = [
  // World
  "set_block", "fill_region", "build_blueprint", "spawn_entity", "remove_entity",
  "spawn_particles", "play_sound", "set_weather",
  // NPCs
  "npc_spawn", "npc_update", "npc_remove", "npc_say", "npc_walk_to", "npc_look_at",
  "npc_equip", "npc_pose", "npc_revive",
  // Players
  "give_item", "apply_effect", "list_players",
  // Ledger
  "ledger_put", "ledger_get", "ledger_query", "ledger_delete", "npc_context",
  // Info
  "get_server_info", "get_block", "sample_terrain",
];

// NPC actors may ONLY call these three tools (DESIGN.md "NPC actors").
// Everything else - including npc_context itself, which would let an actor
// metagame past the plugin-enforced knowledge isolation - is denied.
export const ACTOR_TOOLS = ["npc_say", "npc_look_at", "npc_pose"];

// Sense events that feed the director's debounced scene loop. player_quit
// only updates local presence tracking and never wakes anything (matching
// gm/'s lesson: nothing useful for a director to react to in a quiet exit).
export const DIRECTOR_WAKE_EVENTS = new Set([
  "player_join",
  "player_death",
  "npc_attacked",
  "npc_death",
  "player_explored",
  "player_idle_scene",
  "region_enter",
  "region_exit",
  "player_chat", // only when NOT routed to an actor (see index.mjs routing)
]);

export function loadConfig(env = process.env) {
  return {
    estariUrl: env.ESTARI_URL || "ws://127.0.0.1:8766",
    estariToken: env.ESTARI_TOKEN || "change-me",

    debounceMs: num("BRAIN_DEBOUNCE_MS", 2500),
    loreRefreshMs: num("BRAIN_LORE_REFRESH_MS", 600000),
    dailyTokenBudget: num("BRAIN_DAILY_TOKEN_BUDGET", 500000),
    maxTurnsPerMin: num("BRAIN_MAX_TURNS_PER_MIN", 10),

    directorModel: env.BRAIN_DIRECTOR_MODEL || "claude-sonnet-5",
    actorModel: env.BRAIN_ACTOR_MODEL || "claude-haiku-4-5-20251001",

    enabled: bool01("BRAIN_ENABLED", true),
    dryRun: bool01("BRAIN_DRY_RUN", false),

    loreDir: env.BRAIN_LORE_DIR || path.join(BRAIN_ROOT, "lore"),
    stateDir: env.BRAIN_STATE_DIR || path.join(BRAIN_ROOT, "state"),
    disabledFile: env.BRAIN_DISABLED_FILE || path.join(BRAIN_ROOT, "DISABLED"),
    mcpServerPath: env.BRAIN_MCP_SERVER_PATH || path.join(BRAIN_ROOT, "mcp-bridge.mjs"),

    maxDirectorSteps: num("BRAIN_MAX_DIRECTOR_STEPS", 12),
    maxActorSteps: num("BRAIN_MAX_ACTOR_STEPS", 6),

    reconnectBaseMs: num("BRAIN_RECONNECT_BASE_MS", 1000),
    reconnectMaxMs: num("BRAIN_RECONNECT_MAX_MS", 30000),

    npcChatRangeBlocks: num("BRAIN_NPC_CHAT_RANGE", 8),
    actorHistoryTurns: num("BRAIN_ACTOR_HISTORY_TURNS", 20),
    npcContextTimeoutMs: num("BRAIN_NPC_CONTEXT_TIMEOUT_MS", 8000),
  };
}

/** Turn a bare tool-command name into the fully-namespaced MCP tool name the model sees. */
export function namespacedTool(toolName, serverName = MCP_SERVER_NAME) {
  return `mcp__${serverName}__${toolName}`;
}

export function namespaceAll(toolNames, serverName = MCP_SERVER_NAME) {
  return toolNames.map((t) => namespacedTool(t, serverName));
}

/** Tools an actor turn must NOT have: everything in ALL_TOOLS except ACTOR_TOOLS, namespaced. */
export function actorDisallowedTools(serverName = MCP_SERVER_NAME) {
  const allowed = new Set(ACTOR_TOOLS);
  return namespaceAll(ALL_TOOLS.filter((t) => !allowed.has(t)), serverName);
}
