// Plain-English "what has my world been doing" record for the operator,
// appended to brain/state/decisions.log (created on first write) - readable
// with no JSON knowledge at all. One block per director scene or actor
// turn: timestamp, a plain description of what triggered it, each tool
// call it made (compact), and the final decision (the director's/actor's
// summary text, or "no action", or "skipped: <reason>" when a guardrail
// blocked it before it ever ran). Dry-run turns still journal, marked
// "[dry-run]", since a dry run is exactly the case an operator most wants
// a paper trail for while tuning the brain.
//
// Rotation: when decisions.log exceeds a size threshold (default 1MB,
// overridable via BRAIN_DECISIONS_MAX_BYTES for tests) it is renamed to
// decisions.log.1 (replacing any previous .1) and a fresh file is started -
// one rotation kept, not a numbered series.

import fs from "node:fs";
import path from "node:path";
import { compactArgs } from "./logger.mjs";

const DEFAULT_MAX_BYTES = 1024 * 1024; // 1MB

function maxBytes() {
  const v = Number(process.env.BRAIN_DECISIONS_MAX_BYTES);
  return Number.isFinite(v) && v > 0 ? v : DEFAULT_MAX_BYTES;
}

function journalPath(config) {
  return path.join(config.stateDir, "decisions.log");
}

function rotateIfNeeded(config) {
  const file = journalPath(config);
  let size;
  try {
    size = fs.statSync(file).size;
  } catch {
    return; // no file yet - nothing to rotate
  }
  if (size < maxBytes()) return;
  const rotated = file + ".1";
  try {
    fs.rmSync(rotated, { force: true });
  } catch {
    /* ignore */
  }
  try {
    fs.renameSync(file, rotated);
  } catch {
    /* ignore - worst case the file just keeps growing */
  }
}

function appendBlock(config, text) {
  try {
    fs.mkdirSync(config.stateDir, { recursive: true });
    rotateIfNeeded(config);
    fs.appendFileSync(journalPath(config), text);
  } catch {
    // Journaling must never crash the brain - console logging already
    // carries the same information.
  }
}

function compactToolCall(tool, args) {
  return `  ⚒ ${tool} ${compactArgs(args)}`;
}

/** Plain-English description of what triggered a director scene: event
 * names (collapsed with a count when repeated) plus the players involved. */
function describeTrigger(batch) {
  if (!batch || !batch.length) return "(no events)";
  const counts = new Map();
  const players = new Set();
  for (const e of batch) {
    counts.set(e.event, (counts.get(e.event) || 0) + 1);
    const p = e.data && e.data.player;
    if (p) players.add(p);
  }
  const eventsStr = [...counts.entries()].map(([ev, n]) => (n > 1 ? `${ev} x${n}` : ev)).join(", ");
  const playersStr = players.size ? ` (player: ${[...players].join(", ")})` : "";
  return eventsStr + playersStr;
}

function decisionLine(summary) {
  const text = summary && String(summary).trim();
  return text ? text : "no action";
}

function toolCallLines(toolCalls) {
  if (!toolCalls || !toolCalls.length) return [];
  return ["Tool calls:", ...toolCalls.map((tc) => compactToolCall(tc.tool, tc.args))];
}

/** Journal one completed (or dry-run) director scene. */
export function journalScene({ config, sceneNumber, batch, toolCalls = [], summary, dryRun = false }) {
  const ts = new Date().toISOString();
  const lines = [
    `=== ${ts}  Scene #${sceneNumber}${dryRun ? " [dry-run]" : ""} ===`,
    `Triggered by: ${describeTrigger(batch)}`,
    ...toolCallLines(toolCalls),
    `Decision: ${dryRun && !summary ? "(dry run - no API call made)" : decisionLine(summary)}`,
    "",
  ];
  appendBlock(config, lines.join("\n") + "\n");
}

/** Journal one completed (or dry-run) NPC actor turn. */
export function journalActor({ config, npcId, player, trigger, toolCalls = [], summary, dryRun = false }) {
  const ts = new Date().toISOString();
  const lines = [
    `=== ${ts}  Actor ${npcId} <- ${player} (${trigger})${dryRun ? " [dry-run]" : ""} ===`,
    ...toolCallLines(toolCalls),
    `Decision: ${dryRun && !summary ? "(dry run - no API call made)" : decisionLine(summary)}`,
    "",
  ];
  appendBlock(config, lines.join("\n") + "\n");
}

/** Journal a director scene or actor turn that a guardrail blocked before
 * it ever ran (kill switch / budget / rate limit). */
export function journalSkip({ config, kind, reason, sceneNumber, batch, npcId, player, trigger }) {
  const ts = new Date().toISOString();
  const header =
    kind === "director"
      ? [`=== ${ts}  Scene #${sceneNumber} ===`, `Triggered by: ${describeTrigger(batch)}`]
      : [`=== ${ts}  Actor ${npcId || "?"} <- ${player || "?"} (${trigger || "?"}) ===`];
  const lines = [...header, `Decision: skipped: ${reason}`, ""];
  appendBlock(config, lines.join("\n") + "\n");
}
