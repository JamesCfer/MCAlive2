// Global tool-result size cap: every non-error tool result mcp-bridge.mjs
// hands back to the model is capped at BRAIN_MAX_TOOL_RESULT_CHARS (default
// 3000) characters before it goes out over stdio. This is the single
// biggest lever against the token blowout that prompted this file - an
// agent turn re-sends its ENTIRE accumulated context on every subsequent
// step (lib/director-turn.mjs / lib/actor-turn.mjs), so one oversized early
// tool result (a full ledger dump, a 256-column terrain scan, ...) gets paid
// for again on every step after it, not just once.
//
// Truncation is "intelligent" rather than a blind character chop: for an
// object/array result, the largest array-valued fields (records, columns,
// gadgets, npcs, events, ...) are shrunk to their first few entries with a
// clear "... N more items omitted" marker, so the model still sees real
// examples of the shape of the data and knows to narrow its query rather
// than assuming the collection is empty/small. Only once that no longer
// gets the result under budget does this fall back to a hard string
// truncation with the same kind of marker.
//
// isError results are NEVER truncated (callers must pass isError:true) -
// the director/actor needs the FULL compiler/validation error text to
// iterate (e.g. gadget_define's javac diagnostics), and error text is
// rarely large enough to be the problem in the first place.

const DEFAULT_MAX_CHARS = 3000;
// Item-count ladder tried (largest first) when shrinking an array field to
// fit under the char budget - stop at the first count whose serialized
// result fits, rather than jumping straight to "1" and looking stingier
// than necessary.
const ITEM_CAP_LADDER = [50, 25, 10, 5, 2, 1, 0];

export function resolveMaxToolResultChars(env = process.env) {
  const v = Number(env.BRAIN_MAX_TOOL_RESULT_CHARS);
  return Number.isFinite(v) && v > 0 ? v : DEFAULT_MAX_CHARS;
}

function safeStringify(value) {
  try {
    return JSON.stringify(value, null, 1);
  } catch {
    return String(value);
  }
}

function omittedMarker(omitted) {
  return `... ${omitted} more item(s) omitted - narrow your query (filter/smaller area) to see them`;
}

/** Shrinks every array-valued field of a shallow-cloned object to `itemCap`
 * entries in place, recording how many were omitted per field. Returns
 * whether anything was actually shrunk (so the caller can stop once a
 * smaller cap no longer helps). */
function shrinkArrayFields(clone, itemCap) {
  let shrunkAny = false;
  for (const key of Object.keys(clone)) {
    const arr = clone[key];
    if (!Array.isArray(arr) || arr.length <= itemCap) continue;
    const omitted = arr.length - itemCap;
    clone[key] = arr.slice(0, itemCap);
    clone[`${key}_truncated`] = omittedMarker(omitted);
    shrunkAny = true;
  }
  return shrunkAny;
}

/** Hard last-resort: chop the serialized string itself and append a marker
 * naming how many characters were dropped. Always returns a string whose
 * length is <= maxChars (the marker itself is sized to fit). */
function hardTruncateString(text, maxChars) {
  if (text.length <= maxChars) return text;
  // Reserve room for the marker so the final string still fits maxChars.
  const markerFor = (omitted) => `\n... truncated (${omitted} more character(s) omitted) - narrow your query to see more`;
  // The omitted count depends on where we cut, which depends on the marker
  // length, which depends on the omitted count - two passes settles it.
  let cut = Math.max(0, maxChars - markerFor(0).length);
  let marker = markerFor(text.length - cut);
  while (cut + marker.length > maxChars && cut > 0) {
    cut -= 1;
    marker = markerFor(text.length - cut);
  }
  return text.slice(0, cut) + marker;
}

/**
 * Caps a tool result's serialized size. `data` may be the raw handler
 * return value (object/array/primitive) OR an already-formatted string
 * (e.g. world_overview's text digest) - both are supported since callers
 * differ (mcp-bridge.mjs's generic `tool()` passes handler output, the
 * world_overview tool passes a pre-built string).
 *
 * @param {*} data
 * @param {object} [opts]
 * @param {number} [opts.maxChars] - defaults to resolveMaxToolResultChars()
 * @param {boolean} [opts.isError] - when true, returns the text UNCHANGED
 *   (errors are never truncated - see module header)
 * @param {boolean} [opts.pretty] - when data is an object/array and no
 *   truncation is needed, whether to pretty-print (default true, matching
 *   mcp-bridge.mjs's existing JSON.stringify(data, null, 1) behavior)
 * @returns {string}
 */
export function capToolResultText(data, opts = {}) {
  const maxChars = Number.isFinite(opts.maxChars) && opts.maxChars > 0 ? opts.maxChars : DEFAULT_MAX_CHARS;
  const isString = typeof data === "string";
  let text = isString ? data : safeStringify(data);

  if (opts.isError || text.length <= maxChars) return text;

  // Try shrinking array fields first - only applicable to plain objects and
  // top-level arrays, and only useful if there's something to shrink at all.
  if (!isString && data && typeof data === "object") {
    if (Array.isArray(data)) {
      for (const itemCap of ITEM_CAP_LADDER) {
        if (data.length <= itemCap) continue;
        const omitted = data.length - itemCap;
        const candidate = safeStringify(data.slice(0, itemCap)) + `\n${omittedMarker(omitted)}`;
        text = candidate;
        if (candidate.length <= maxChars) return candidate;
      }
    } else {
      for (const itemCap of ITEM_CAP_LADDER) {
        const clone = { ...data };
        const shrunkAny = shrinkArrayFields(clone, itemCap);
        if (!shrunkAny) continue;
        const candidate = safeStringify(clone);
        text = candidate;
        if (candidate.length <= maxChars) return candidate;
      }
    }
  }

  // Either a string result, a primitive, or shrinking arrays wasn't enough
  // (e.g. the bulk of the size is in non-array fields, or fields are already
  // at their smallest) - fall back to a hard character truncation.
  return hardTruncateString(text, maxChars);
}
