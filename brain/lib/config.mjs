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

/** Seconds-based knob with a minutes-based fallback for anyone who already
 * exported the old var: BRAIN_UPDATE_CHECK_SEC wins when set; otherwise
 * BRAIN_UPDATE_CHECK_MIN (converted to seconds) is honored; otherwise
 * `fallbackSec`. Mirrors num()'s "read straight off process.env" behavior. */
function secWithMinFallback(secName, minName, fallbackSec) {
  const secRaw = process.env[secName];
  if (secRaw !== undefined && secRaw !== "") {
    const n = Number(secRaw);
    if (Number.isFinite(n)) return n;
  }
  const minRaw = process.env[minName];
  if (minRaw !== undefined && minRaw !== "") {
    const n = Number(minRaw);
    if (Number.isFinite(n)) return n * 60;
  }
  return fallbackSec;
}

// The name the mcalive2 MCP tool server is registered under. Tool names as
// seen by the model are namespaced "mcp__<this>__<toolName>".
export const MCP_SERVER_NAME = "mcalive2";

// The full actuator/ledger/info command set exposed by brain/mcp-bridge.mjs,
// per DESIGN.md "Plugin subsystems" section. This is the DIRECTOR's toolset
// in full - "deliberately absent: broadcast/title/actionbar narration tools
// and set_time", which is enforced plugin-side (those commands simply don't
// exist on the bridge), not by a brain-side deny-list.
export const ALL_TOOLS = [
  // World
  "set_block", "fill_region", "build_blueprint", "spawn_entity", "remove_entity",
  "spawn_particles", "play_sound", "set_weather",
  // World (operator-order set-pieces - director-only by omission from
  // ACTOR_TOOLS below, same as every other world tool)
  "create_explosion", "strike_lightning", "move_region",
  // NPCs
  "npc_spawn", "npc_update", "npc_remove", "npc_say", "npc_walk_to", "npc_look_at",
  "npc_equip", "npc_pose", "npc_revive", "npc_head_check",
  // Players
  "give_item", "apply_effect", "list_players",
  // Ledger
  "ledger_put", "ledger_get", "ledger_query", "ledger_delete", "npc_context",
  // Info
  "get_server_info", "get_block", "sample_terrain",
  // Formulas - reusable, parameterized recipes over the tools above
  "formula_define", "formula_run", "formula_list", "formula_get", "formula_delete",
  // NPC jobs - NPCs physically working real stations/chests with finite items
  "npc_assign_job", "npc_job_cancel",
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
  "npc_head_taken",
  "player_explored",
  "player_idle_scene",
  "region_enter",
  "region_exit",
  "player_chat", // only when NOT routed to an actor (see index.mjs routing)
  "operator_order", // Lore Console "order the world" - see console-server.mjs/index.mjs submitOrder
  "sequence_done", // plugin-side timed spectacle sequence (e.g. strike_lightning or formula_run) finished
  "formula_error", // a formula_run step failed plugin-side
  "npc_job_done", // an NPC's assigned job finished all its repeats
  "npc_job_blocked", // an NPC's assigned job stalled (e.g. missing inputs)
]);

export function loadConfig(env = process.env) {
  const mcalive2Token = env.MCALIVE2_TOKEN || "change-me";
  return {
    mcalive2Url: env.MCALIVE2_URL || "ws://127.0.0.1:8765",
    mcalive2Token,

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
    // Scenes containing an "operator_order" event (a one-shot command from
    // the Lore Console - see console-server.mjs/index.mjs) get this higher
    // turn ceiling instead of maxDirectorSteps, since carrying out a big
    // set-piece order to completion can take far more tool-call steps than
    // an ordinary reactive scene. See lib/director-turn.mjs.
    orderMaxSteps: num("BRAIN_ORDER_MAX_STEPS", 80),

    reconnectBaseMs: num("BRAIN_RECONNECT_BASE_MS", 1000),
    reconnectMaxMs: num("BRAIN_RECONNECT_MAX_MS", 30000),

    // Self-update: seconds between checking origin/main for new brain code
    // (each check is one lightweight `git ls-remote origin main`, not a
    // GitHub API call, so a short interval is cheap). 0 disables entirely;
    // also disabled (gracefully) when brain/.. isn't a git checkout at all -
    // see lib/self-update.mjs. BRAIN_UPDATE_CHECK_MIN (minutes) is still
    // read as a fallback for anyone who already exported it, converted to
    // seconds; BRAIN_UPDATE_CHECK_SEC wins when both are set.
    updateCheckSec: secWithMinFallback("BRAIN_UPDATE_CHECK_SEC", "BRAIN_UPDATE_CHECK_MIN", 10),

    npcChatRangeBlocks: num("BRAIN_NPC_CHAT_RANGE", 8),
    actorHistoryTurns: num("BRAIN_ACTOR_HISTORY_TURNS", 20),
    npcContextTimeoutMs: num("BRAIN_NPC_CONTEXT_TIMEOUT_MS", 8000),

    // Lore Console: a tiny local HTTP page for the operator to type
    // free-text directives ("add a ruined tower...") that get folded into
    // the world's lore - see lib/console-server.mjs.
    consoleEnabled: bool01("BRAIN_CONSOLE", true),
    consoleBind: env.BRAIN_CONSOLE_BIND || "127.0.0.1",
    consolePort: num("BRAIN_CONSOLE_PORT", 7777),
    consoleToken: env.BRAIN_CONSOLE_TOKEN || mcalive2Token,
  };
}

/** Turn a bare tool-command name into the fully-namespaced MCP tool name the model sees. */
export function namespacedTool(toolName, serverName = MCP_SERVER_NAME) {
  return `mcp__${serverName}__${toolName}`;
}

export function namespaceAll(toolNames, serverName = MCP_SERVER_NAME) {
  return toolNames.map((t) => namespacedTool(t, serverName));
}

/** Inverse of namespacedTool(): strip the mcp__<server>__ prefix a tool_use
 * block's name carries, for logging/journaling under its bare command name.
 * Leaves non-namespaced names (shouldn't occur here, but harmless) as-is. */
export function stripToolPrefix(toolName, serverName = MCP_SERVER_NAME) {
  const prefix = `mcp__${serverName}__`;
  return toolName.startsWith(prefix) ? toolName.slice(prefix.length) : toolName;
}

/** Tools an actor turn must NOT have: everything in ALL_TOOLS except ACTOR_TOOLS, namespaced. */
export function actorDisallowedTools(serverName = MCP_SERVER_NAME) {
  const allowed = new Set(ACTOR_TOOLS);
  return namespaceAll(ALL_TOOLS.filter((t) => !allowed.has(t)), serverName);
}
