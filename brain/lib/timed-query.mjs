// Runs an Agent SDK query() with a hard wall-clock timeout, aborting the
// in-flight query if it fires. Shared by director-turn.mjs and
// actor-turn.mjs: without this, a single hung SDK call blocks forever, and
// since director scenes never run concurrently (lib/director-scheduler.mjs)
// that also wedges every subsequent event - including operator orders -
// behind it forever. Self-update (lib/self-update.mjs) has its own,
// independent cap for the same underlying risk (waitUntilIdle would
// otherwise wait for a wedged turn forever too).
//
// Verified against @anthropic-ai/claude-agent-sdk's sdk.d.ts:
//   - Options.abortController?: AbortController - "Controller for cancelling
//     the query. When aborted, the query will stop and clean up resources."
//     This works regardless of prompt shape (string or streaming
//     AsyncIterable), unlike Query#interrupt() which sdk.d.ts documents as
//     "only supported when streaming input/output is used" (director/actor
//     turns here use a plain string prompt).
//   - Query#close(): "Close the query and terminate the underlying process
//     ... Use this when you need to abort a query that is still running."
// Both are used together on timeout as belt-and-suspenders: abort() signals
// cooperative cancellation through the normal SDK path, close() forcibly
// tears down the CLI subprocess so a query that ignores/outlives the abort
// signal cannot keep running or keep the process alive.
//
// Critically, the timeout races the "give up and move on" decision, not the
// underlying generator: if the generator itself never yields another
// message and never resolves (a genuinely wedged SDK call - exactly what a
// fake never-resolving async generator simulates in tests), this module
// still returns control to the caller the instant the timeout fires,
// instead of awaiting a promise that may never settle.

/**
 * @param {object} params
 * @param {() => AsyncGenerator} params.startQuery - returns the live Query
 *   object; call query({prompt, options}) here (options.abortController must
 *   already be set by the caller before this runs).
 * @param {AbortController} params.abortController
 * @param {number} params.timeoutSec
 * @param {(msg: any) => void} params.onMessage - called, in order, for every
 *   streamed SDKMessage until either the stream ends or the timeout fires.
 * @returns {Promise<{ timedOut: boolean, elapsedMs: number }>}
 */
export async function consumeWithTimeout({ startQuery, abortController, timeoutSec, onMessage }) {
  const q = startQuery();
  const timeoutSecNum = Number(timeoutSec);
  const timeoutMs = Math.max(1, (timeoutSecNum > 0 ? timeoutSecNum : 300) * 1000); // floor is 1ms, not 1s - fractional-second timeouts (tests) must work
  const startedAt = Date.now();
  let timedOut = false;

  const consume = (async () => {
    for await (const msg of q) onMessage(msg);
  })();

  // Deliberately NOT unref'd: this is a short, bounded wait the caller
  // actually needs to fire to make forward progress (a wedged query would
  // otherwise never let this turn complete), not a background timer that
  // should let the process exit early if nothing else is pending.
  let timer;
  const timeout = new Promise((resolve) => {
    timer = setTimeout(() => {
      timedOut = true;
      resolve();
    }, timeoutMs);
  });

  try {
    await Promise.race([consume, timeout]);
  } finally {
    clearTimeout(timer);
  }

  if (timedOut) {
    try {
      abortController.abort();
    } catch {
      /* best effort */
    }
    try {
      if (typeof q.close === "function") q.close();
    } catch {
      /* best effort */
    }
    // The generator may still be mid-cleanup, or (a genuinely wedged call)
    // never settle at all - never await it, and never let its eventual
    // rejection surface as an unhandled rejection.
    consume.catch(() => {});
    return { timedOut: true, elapsedMs: Date.now() - startedAt };
  }

  // consume won the race - if it threw a real (non-timeout) error, this
  // rethrows it to the caller exactly as an unguarded `for await` would.
  await consume;
  return { timedOut: false, elapsedMs: Date.now() - startedAt };
}
