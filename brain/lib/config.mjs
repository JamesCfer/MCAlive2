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
  "npc_equip", "npc_pose", "npc_revive", "npc_head_check", "npc_do", "npc_give",
  // Players
  "give_item", "apply_effect", "list_players",
  // Ledger
  "ledger_put", "ledger_get", "ledger_query", "ledger_delete", "npc_context",
  // Info
  "get_server_info", "get_block", "sample_terrain", "scan_area",
  // World model - a compact "what exists and what's wrong" digest composed
  // brain-side from ledger_query/scan_area/get_block (see lib/worldmodel.mjs)
  "world_overview",
  // Formulas - reusable, parameterized recipes over the tools above
  "formula_define", "formula_run", "formula_list", "formula_get", "formula_delete",
  // NPC jobs - NPCs physically working real stations/chests with finite items
  "npc_assign_job", "npc_job_cancel",
  // Behavior programs - crew goals authored ONCE that the plugin then runs
  // indefinitely with zero AI involvement (gather real blocks, build a
  // registered blueprint log-by-log, deposit/withdraw real items), waking
  // the director only via behavior_done / behavior_blocked
  "behavior_create", "behavior_update", "behavior_pause", "behavior_resume",
  "behavior_delete", "behavior_status", "blueprint_register", "blueprint_delete",
  // Gadgets - runtime-injected Java primitives compiled on the running server
  "gadget_define", "gadget_run", "gadget_list", "gadget_get", "gadget_delete",
  // Chronicle - the director's prose campaign notebook, brain-local and
  // file-backed (see lib/chronicle.mjs), never a bridge passthrough.
  // Director-only by omission from ACTOR_TOOLS, like every tool above.
  "chronicle_append", "chronicle_read", "chronicle_rewrite",
];

// NPC actors may ONLY call these three tools (DESIGN.md "NPC actors").
// Everything else - including npc_context itself, which would let an actor
// metagame past the plugin-enforced knowledge isolation - is denied.
export const ACTOR_TOOLS = ["npc_say", "npc_look_at", "npc_pose", "npc_do", "npc_give"];

// Sense events that feed the director's debounced scene loop. player_quit
// only updates local presence tracking and never wakes anything (matching
// gm/'s lesson: nothing useful for a director to react to in a quiet exit).
/**
 * What is worth waking the director for: PLAYER MOMENTS, and nothing else.
 *
 * This set used to include the ambient life of the world - somebody walked into a
 * region, somebody explored, the world-turn heartbeat - and the director dutifully
 * spent a turn on each. Reading the journal back, most of them concluded nothing:
 * "Silence. A plain, unremarkable terrain sample with no notable features." Four of
 * those in five minutes from one player walking around, until a five million token
 * day was gone by ten at night and no NPC could speak.
 *
 * The ambient events now feed lib/needs-log.mjs instead, and the tokens they used to
 * cost are spent once an hour building something the world actually lacks.
 */
export const DIRECTOR_WAKE_EVENTS = new Set([
  "player_join",
  "player_death",
  "player_idle_scene",
  "npc_head_taken", // a player picked up a dead NPC's head: the revival token
  "player_chat", // only when NOT routed to an actor (see index.mjs routing)
  "operator_order", // Lore Console "order the world" - see console-server.mjs/index.mjs submitOrder
  "sequence_done", // plugin-side timed spectacle sequence (e.g. strike_lightning or formula_run) finished
  "formula_error", // a formula_run step failed plugin-side
]);

/**
 * Events that are evidence about what the world cannot do yet. These do not wake
 * anything: they are written down, and read once an hour by the developer.
 *
 * npc_attacked is here for attribution rather than as a need of its own - a death
 * inside a minute of one names its killer instead of guessing (see NeedsLog.causeOf).
 */
export const NEEDS_LOG_EVENTS = new Set([
  "npc_death",
  "npc_attacked",
  "npc_job_blocked", // an NPC's assigned job stalled (e.g. missing inputs)
  "behavior_blocked", // a behavior program stalled and paused itself
]);

/**
 * Deliberately handled nowhere: player_explored, region_enter, region_exit,
 * world_turn, npc_job_done, behavior_done. Ambient or purely-informational, and
 * every one of them used to cost a director turn.
 */

export function loadConfig(env = process.env) {
  const mcalive2Token = env.MCALIVE2_TOKEN || "change-me";
  return {
    mcalive2Url: env.MCALIVE2_URL || "ws://127.0.0.1:8765",
    mcalive2Token,

    debounceMs: num("BRAIN_DEBOUNCE_MS", 2500),
    loreRefreshMs: num("BRAIN_LORE_REFRESH_MS", 600000),
    dailyTokenBudget: num("BRAIN_DAILY_TOKEN_BUDGET", 500000),
    // How often to write down what the living are stuck on (lib/needs-log.mjs).
    needsSweepMin: num("BRAIN_NEEDS_SWEEP_MIN", 60),
    maxTurnsPerMin: num("BRAIN_MAX_TURNS_PER_MIN", 10),

    directorModel: env.BRAIN_DIRECTOR_MODEL || "claude-sonnet-5",
    // The developer: once an hour, add the next roadmap feature (lib/developer.mjs).
    // 0 disables. Skipped when less than devMinRemainingTokens of the daily budget is left.
    devIntervalMin: num("BRAIN_DEV_INTERVAL_MIN", 60),
    devModel: env.BRAIN_DEV_MODEL || "claude-opus-5",
    devMaxTurns: num("BRAIN_DEV_MAX_TURNS", 120),
    devMinRemainingTokens: num("BRAIN_DEV_MIN_REMAINING_TOKENS", 800000),
    actorModel: env.BRAIN_ACTOR_MODEL || "claude-haiku-4-5-20251001",

    enabled: bool01("BRAIN_ENABLED", true),
    dryRun: bool01("BRAIN_DRY_RUN", false),

    loreDir: env.BRAIN_LORE_DIR || path.join(BRAIN_ROOT, "lore"),
    stateDir: env.BRAIN_STATE_DIR || path.join(BRAIN_ROOT, "state"),
    disabledFile: env.BRAIN_DISABLED_FILE || path.join(BRAIN_ROOT, "DISABLED"),
    mcpServerPath: env.BRAIN_MCP_SERVER_PATH || path.join(BRAIN_ROOT, "mcp-bridge.mjs"),

    // Hard wall-clock cap on a single director/actor SDK turn (lib/timed-
    // query.mjs). A hung streamed query() call would otherwise block
    // forever - and since director scenes never run concurrently, that
    // wedges every subsequent event (including operator orders) behind it
    // forever too. On timeout the query is aborted, journaled as a timed-
    // out decision, and still counts as a completed turn so the scheduler
    // moves on to the next batch.
    turnTimeoutSec: num("BRAIN_TURN_TIMEOUT_SEC", 300),

    // Cap on a single tool result's serialized size (lib/tool-result-cap.mjs),
    // forwarded explicitly into mcp-bridge.mjs's spawned-process env below
    // (that process is spawned fresh per turn by the Agent SDK, so it can't
    // be relied on to inherit this process's env - see director-turn.mjs/
    // actor-turn.mjs's mcpServers.env).
    maxToolResultChars: num("BRAIN_MAX_TOOL_RESULT_CHARS", 3000),

    maxDirectorSteps: num("BRAIN_MAX_DIRECTOR_STEPS", 12),
    maxActorSteps: num("BRAIN_MAX_ACTOR_STEPS", 6),
    // Scenes containing an "operator_order" event (a one-shot command from
    // the Lore Console - see console-server.mjs/index.mjs) get this higher
    // turn ceiling instead of maxDirectorSteps, since carrying out a big
    // set-piece order to completion can take far more tool-call steps than
    // an ordinary reactive scene. See lib/director-turn.mjs.
    orderMaxSteps: num("BRAIN_ORDER_MAX_STEPS", 80),
    // ...and the matching wall-clock allowance for those scenes (see
    // director-turn.mjs timeoutSecFor): a big order was being aborted by the
    // ordinary turnTimeoutSec partway through its work.
    orderTimeoutSec: num("BRAIN_ORDER_TIMEOUT_SEC", 1800),

    // Hard per-turn TOKEN ceiling (as opposed to turnTimeoutSec's wall-clock
    // ceiling above, or maxDirectorSteps'/orderMaxSteps' tool-call-count
    // ceiling): the step/time ceilings alone let a scene with many CHEAP
    // steps but one or two HUGE tool results (a full ledger dump, a
    // detail:"full" world_overview) blow through the daily budget in a
    // single scene, because an agent turn re-sends its whole accumulated
    // context on every subsequent step (see lib/director-turn.mjs's
    // maxTokensFor()/runDirectorTurn()). Checked WHILE a turn streams (not
    // just at the start, like dailyTokenBudget) and aborts the query the
    // moment it's exceeded, via the same AbortController+close mechanism
    // lib/timed-query.mjs already uses for timeouts.
    maxTokensPerTurn: num("BRAIN_MAX_TOKENS_PER_TURN", 120000),
    // ...and the matching higher ceiling for operator-order scenes (mirrors
    // orderMaxSteps/orderTimeoutSec above) - a big set-piece order
    // legitimately needs more tokens than an ordinary reactive scene, just
    // not an unbounded amount.
    maxTokensPerOrder: num("BRAIN_MAX_TOKENS_PER_ORDER", 600000),

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

    // Cap on how long self-update's restart will wait for the director
    // scheduler / actor queue to go idle before restarting anyway (lib/
    // self-update.mjs). Without this, a wedged turn (see turnTimeoutSec
    // above - should now be rare) could also block self-update forever,
    // since waitForIdle() previously had no way to give up.
    updateIdleWaitCapSec: num("BRAIN_UPDATE_IDLE_WAIT_SEC", 600),

    npcChatRangeBlocks: num("BRAIN_NPC_CHAT_RANGE", 8),
    actorHistoryTurns: num("BRAIN_ACTOR_HISTORY_TURNS", 20),
    npcContextTimeoutMs: num("BRAIN_NPC_CONTEXT_TIMEOUT_MS", 8000),

    // Live position tracking: on boot (after the bridge authenticates),
    // index.mjs auto-installs+starts brain/gadgets/position-tracker.java (a
    // runtime-injected gadget - see README "Gadgets") which pushes an
    // "entity_positions" bridge event on a timer, cached by
    // lib/position-cache.mjs and preferred by lib/worldmodel.mjs over the
    // static ledger `home` fallback. BRAIN_POSITION_TRACKING=0 disables the
    // boot auto-install entirely (world model always falls back to ledger
    // home positions, exactly as before this feature existed).
    positionTrackingEnabled: bool01("BRAIN_POSITION_TRACKING", true),
    positionIntervalTicks: num("BRAIN_POSITION_INTERVAL_TICKS", 20),
    // Server auto-restart to apply staged plugin updates. Gated server-side by
    // scripts/run-server.cmd's sentinel, so this is safe to leave on.
    serverAutoRestartEnabled: bool01("BRAIN_SERVER_AUTORESTART", true),
    serverRestartCheckSec: num("BRAIN_SERVER_RESTART_CHECK_SEC", 30),
    serverRestartGraceSec: num("BRAIN_SERVER_RESTART_GRACE_SEC", 30),
    positionStaleSec: num("BRAIN_POSITION_STALE_SEC", 30),

    // Whole-loaded-world terrain: on boot (after the bridge authenticates),
    // index.mjs auto-installs brain/gadgets/world-scan.java (a runtime-
    // injected gadget - see README "Gadgets"), which lib/worldmodel.mjs then
    // calls on demand ("gadget:world-scan") to survey EVERY currently-loaded
    // chunk into a coarse heightmap - spawn, wilderness, whatever players
    // have explored - instead of only the bounding box of recorded places.
    // BRAIN_WORLD_SCAN=0 disables the boot auto-install entirely (world
    // model always falls back to its pre-existing scan_area-over-places
    // behavior, exactly as before this feature existed).
    worldScanEnabled: bool01("BRAIN_WORLD_SCAN", true),
    // Cap on cols*rows sampled by the world-scan gadget - see
    // gadgets/world-scan.java's own maxCells doc.
    worldScanMaxCells: num("BRAIN_WORLD_SCAN_MAX_CELLS", 4096),

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
