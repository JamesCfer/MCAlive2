// Structured logger with two output modes:
//
//   - DEFAULT: one clean, human-readable line per log call -
//     `HH:MM:SS  LEVEL  message  key=val ...` - with well-known message
//     shapes (sense events, director scenes, tool calls, actor turns,
//     guardrail skips, bridge connect/reconnect) rendered as a short
//     narrative line instead of a raw field dump. This is what an operator
//     watches scroll by in a terminal to understand "what is the AI doing
//     right now" without piping through `jq`.
//   - BRAIN_LOG_JSON=1: the original one-JSON-object-per-line format, still
//     available for tooling (the smoke test parses this mode) and for
//     anyone who wants to `| jq` production logs.
//
// `formatHumanLine` is exported and pure (takes a Date so it's
// deterministic) specifically so it can be unit-tested directly without
// spawning a process - see test/smoke.mjs section "Logger human-readable
// formatting".

function pad2(n) {
  return String(n).padStart(2, "0");
}

function timeStr(d) {
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

function truncate(s, max) {
  if (s === undefined || s === null) return s;
  const str = String(s);
  return str.length > max ? str.slice(0, max - 1) + "…" : str;
}

/** One-line compact rendering of a tool-call args object: long individual
 * string values are shortened, then the whole rendering is capped at ~max
 * chars total, so a single tool-call line never wraps the terminal. */
export function compactArgs(args, max = 120) {
  if (args === undefined) return "{}";
  let json;
  try {
    json = JSON.stringify(args, (_key, value) =>
      typeof value === "string" && value.length > 40 ? value.slice(0, 37) + "..." : value
    );
  } catch {
    json = String(args);
  }
  if (json === undefined) json = "{}";
  // Drop quotes around plain identifier keys for readability: {"x":1} -> {x:1}
  json = json.replace(/"([a-zA-Z_$][a-zA-Z0-9_$]*)":/g, "$1:");
  return json.length > max ? json.slice(0, max - 3) + "..." : json;
}

function fmtEventReceived(fields) {
  const event = fields.event;
  const d = fields.data || {};
  switch (event) {
    case "player_chat":
      return `event player_chat ${d.player || "?"}: "${d.message || ""}"`;
    case "player_join":
      return `event player_join ${d.player || "?"} joined`;
    case "player_quit":
      return `event player_quit ${d.player || "?"} left`;
    case "player_death":
      return `event player_death ${d.player || "?"} died`;
    case "npc_interact":
      return `event npc_interact ${d.player || "?"} -> ${d.npcId || "?"}`;
    case "npc_attacked":
      return `event npc_attacked ${d.npcId || "?"} attacked by ${d.player || "?"}`;
    case "npc_death":
      return `event npc_death ${d.npcName || d.npcId || "?"} died`;
    default:
      return `event ${event} ${compactArgs(d, 100)}`;
  }
}

const LEVEL_LABEL = { info: "INFO ", warn: "WARN ", error: "ERROR" };

/** Render one log call as a single human-readable line. Pure/deterministic
 * (takes `now` rather than reading the clock) so it can be unit-tested. */
export function formatHumanLine(level, msg, fields = {}, now = new Date()) {
  const time = timeStr(now);
  const levelLabel = LEVEL_LABEL[level] || String(level).toUpperCase().padEnd(5);
  let body;

  switch (msg) {
    case "event_received":
      body = fmtEventReceived(fields);
      break;

    case "director_scene_starting": {
      const n = fields.batchSize;
      body = `scene #${fields.sceneNumber} starting (${n} event${n === 1 ? "" : "s"})`;
      break;
    }

    case "dry_run_director_turn":
      body = `scene #${fields.sceneNumber} starting [dry-run]`;
      break;

    case "tool_call": {
      const args = fields.argsLine !== undefined ? fields.argsLine : compactArgs(fields.args);
      body = `  ⚒ ${fields.tool} ${args}`.trimEnd();
      break;
    }

    case "director_scene_summary":
      body = `✓ scene #${fields.sceneNumber} decided: ${fields.summary || "(no summary)"}`;
      break;

    case "actor_turn_starting":
      body = `actor ${fields.npcId} starting (${fields.trigger}) with ${fields.player}`;
      break;

    case "dry_run_actor_turn":
      body = `actor ${fields.npcId} starting (${fields.trigger}) with ${fields.player} [dry-run]`;
      break;

    case "actor_turn_complete": {
      // Strip a trailing ```report``` block (structured, not for humans)
      // before showing what the NPC actually said.
      const spoken = String(fields.result || "").split("```")[0].trim();
      body = `✓ actor ${fields.npcId} replied to ${fields.player}: "${truncate(spoken, 100)}"`;
      break;
    }

    case "turn_skipped_kill_switch":
      body = `⏭ ${fields.kind} turn skipped: kill switch active`;
      break;

    case "turn_skipped_budget_exceeded":
      body = `⏭ ${fields.kind} turn skipped: budget exceeded (${fields.tokensUsed}/${fields.dailyTokenBudget} tokens)`;
      break;

    // Loud, once-per-UTC-day: the first budget skip of the day (see
    // index.mjs's guardrailBlock). Every later skip that same day logs the
    // quiet "turn_skipped_budget_exceeded" line above (at debug level)
    // instead, so an exhausted budget doesn't flood the log for hours.
    case "budget_exhausted":
      body = `⚠ ${fields.message}`;
      break;

    case "budget_reset":
      body = `⚠ operator reset today's token budget counter via the console (was ${fields.previousTokensUsed}/${fields.dailyTokenBudget} tokens)`;
      break;

    case "turn_skipped_rate_limited":
      body = `⏭ ${fields.kind} turn skipped: rate limited (retry in ${fields.retryInMs}ms)`;
      break;

    case "bridge_auth_ok":
      body = `bridge connected (${fields.url})`;
      break;

    case "bridge_auth_failed":
      body = `bridge auth failed: ${fields.error}`;
      break;

    case "bridge_connect_failed":
      body = `bridge connect failed: ${fields.error}`;
      break;

    case "bridge_reconnect_scheduled":
      body = `bridge reconnecting (attempt ${fields.attempt}, in ${fields.delayMs}ms)`;
      break;

    case "console_listening":
      body = `console listening on http://${fields.bind}:${fields.port} (token required)`;
      break;

    case "self_update_disabled":
      body = `self-update disabled (${fields.reason})`;
      break;

    case "self_update_available":
      body = `⟳ brain update available: ${fields.local} -> ${fields.remote}`;
      break;

    case "self_update_pulling":
      body = `⟳ pulling latest brain code (git pull --ff-only)...`;
      break;

    case "self_update_npm_install":
      body = `⟳ package-lock.json changed, running npm install...`;
      break;

    case "self_update_restarting":
      body = `⟳ restarting to apply update (waiting for idle, then exit 75)`;
      break;

    case "self_update_restarting_with_turn_in_flight":
      body = `⚠ idle-wait cap (${fields.idleWaitCapSec}s) hit with a turn presumed still in flight - restarting anyway`;
      break;

    case "director_turn_timed_out":
      body = `⚠ scene #${fields.sceneNumber} timed out after ${Math.round((fields.elapsedMs || 0) / 1000)}s (cap ${fields.timeoutSec}s) - aborted`;
      break;

    case "actor_turn_timed_out":
      body = `⚠ actor ${fields.npcId} <- ${fields.player} timed out after ${Math.round((fields.elapsedMs || 0) / 1000)}s (cap ${fields.timeoutSec}s) - aborted`;
      break;

    case "order_requeued_on_boot":
      body = `↺ requeued order from state/orders.json (${fields.timestamp}): ${fields.text}`;
      break;

    case "self_update_pull_failed":
      body = `⚠ self-update pull failed, skipping: ${fields.error}`;
      break;

    case "self_update_diff_failed":
      body = `⚠ self-update diff check failed, defaulting to npm install: ${fields.error}`;
      break;

    case "self_update_npm_install_failed":
      body = `⚠ self-update npm install failed (restarting anyway): ${fields.error}`;
      break;

    case "self_update_check_failed":
      body = `⚠ self-update check failed: ${fields.error}`;
      break;

    case "self_update_up_to_date":
      body = `brain is up to date (${fields.head})`;
      break;

    case "position_tracking_installed":
      body = `live position tracking installed (interval ${fields.intervalTicks} ticks)`;
      break;

    case "position_tracking_unavailable":
      body = `⚠ ${fields.message}`;
      break;

    case "position_tracking_disabled":
      body = `live position tracking disabled (${fields.reason})`;
      break;

    default: {
      const rest = Object.entries(fields)
        .map(([k, v]) => `${k}=${typeof v === "object" && v !== null ? compactArgs(v, 60) : v}`)
        .join(" ");
      body = rest ? `${msg}  ${rest}` : msg;
    }
  }

  return `${time}  ${levelLabel}  ${body}`;
}

function jsonMode() {
  const v = process.env.BRAIN_LOG_JSON;
  return v === "1" || (v || "").toLowerCase() === "true";
}

function debugMode() {
  const v = process.env.BRAIN_DEBUG;
  return v === "1" || (v || "").toLowerCase() === "true";
}

function emit(stream, level, msg, fields) {
  if (jsonMode()) {
    const line = { ts: new Date().toISOString(), level, msg, ...fields };
    stream.write(JSON.stringify(line) + "\n");
  } else {
    stream.write(formatHumanLine(level, msg, fields) + "\n");
  }
}

export const log = {
  info(msg, fields = {}) {
    emit(process.stdout, "info", msg, fields);
  },
  warn(msg, fields = {}) {
    emit(process.stderr, "warn", msg, fields);
  },
  error(msg, fields = {}) {
    emit(process.stderr, "error", msg, fields);
  },
  // Suppressed unless BRAIN_DEBUG=1 (or BRAIN_LOG_JSON=1, so tooling that
  // already opted into full JSON output can still see it) - for
  // high-frequency "nothing happened" lines like self-update's
  // up-to-date check, not worth an operator's attention by default.
  debug(msg, fields = {}) {
    if (!debugMode() && !jsonMode()) return;
    emit(process.stdout, "info", msg, fields);
  },
};

// Monotonically increasing per-process scene counter, so the console and
// the decisions journal can both refer to "scene #N" and mean the same
// scene, even though the number is assigned in index.mjs and threaded
// through to lib/director-turn.mjs.
let sceneCounter = 0;
export function nextSceneNumber() {
  sceneCounter += 1;
  return sceneCounter;
}
