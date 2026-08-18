// Parses the optional ```report {...}``` block NPC actors may append after
// their dialogue (DESIGN.md "Actors end their reply with an optional
// structured report ... which the DIRECTOR validates into the ledger").
//
// Schema (all keys optional - actors propose, the director disposes):
//   { facts: [{ text, knownBy }], promise: {...}, questOffered: {...}, mood }
//
// Absent block = fine (no report this turn). Malformed block (bad fence,
// invalid JSON, non-object payload) = logged and ignored, never thrown -
// a broken actor reply must never crash the brain.

import { log } from "./logger.mjs";

const REPORT_FENCE = /```report\s*([\s\S]*?)```/;

/**
 * @param {string|null|undefined} text - the actor's full reply text
 * @returns {object|null} the parsed report object, or null if absent/malformed
 */
export function parseActorReport(text) {
  const m = REPORT_FENCE.exec(text || "");
  if (!m) return null;

  let parsed;
  try {
    parsed = JSON.parse(m[1]);
  } catch (e) {
    log.warn("actor_report_malformed", { error: String(e && e.message || e), raw: m[1].slice(0, 500) });
    return null;
  }

  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    log.warn("actor_report_malformed", { error: "report block did not parse to a JSON object", raw: m[1].slice(0, 500) });
    return null;
  }

  return parsed;
}
