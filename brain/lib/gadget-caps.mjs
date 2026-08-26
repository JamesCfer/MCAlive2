// Does the gadget running on the server actually have this action?
//
// gadget:people ends its run() with an unconditional start: every `if (action.equals(..))`
// falls through to "cancel the timer and begin again". So calling an action the DEPLOYED
// gadget has never heard of does not return an error - it silently RESTARTS the world.
// Statics reset, running jobs are dropped, and (on any build without the beat-clock guard)
// every stale attendUntil sits thirty hours in the future and leaves the whole roster
// standing about "waiting".
//
// The brain and the gadgets deploy separately - brain code by a git push, gadget code by
// gadget_define - so there is always a window where the brain knows about an action the
// server does not. On 2026-08-25 that window was open indefinitely, because gadget_define
// had stopped answering entirely. Hence this: ask what the running gadget's source
// actually contains before calling anything new on it, and cache the answer briefly so a
// deploy is picked up within the minute without a second push.

import { log } from "./logger.mjs";

const TTL_MS = 60_000;
const cache = new Map(); // gadgetId -> { at, source }

async function sourceOf(bridgeCall, id) {
  const hit = cache.get(id);
  if (hit && Date.now() - hit.at < TTL_MS) return hit.source;
  try {
    const got = await bridgeCall("gadget_get", { id }, 15000);
    const source = (got && (got.source || got.code)) || "";
    cache.set(id, { at: Date.now(), source });
    return source;
  } catch (e) {
    // Unknown is not the same as absent, but treating it as absent is the safe way to be
    // wrong: the cost of skipping a bond update is nothing, and the cost of restarting
    // the world by accident is the whole roster.
    log.warn("gadget_caps_probe_failed", { id, error: String((e && e.message) || e) });
    cache.set(id, { at: Date.now(), source: "" });
    return "";
  }
}

/** True when the gadget on the server has a branch for this action. */
export async function supportsAction(bridgeCall, id, action) {
  const src = await sourceOf(bridgeCall, id);
  if (!src) return false;
  return src.includes(`action.equals("${action}")`);
}

/** Forget what we know, e.g. straight after installing a new build. */
export function forgetCaps(id) {
  if (id) cache.delete(id);
  else cache.clear();
}
