// Debounces director-bound sense events into scenes and hands each scene to
// a single director turn, one at a time. Events that arrive while a scene
// is mid-turn are queued for the next scene rather than dropped or run
// concurrently (DESIGN.md: "never two turns concurrently, queue overflow
// into next turn"). NPC actor turns are routed separately (see index.mjs)
// and are NOT subject to this debounce - they are handled as soon as they
// happen, independent of whatever the director is doing.

import { log } from "./logger.mjs";

export class DirectorScheduler {
  /**
   * @param {object} opts
   * @param {number} opts.debounceMs
   * @param {(batch: Array<{event:string, data:any, at:string}>) => Promise<void>} opts.runScene
   */
  constructor({ debounceMs, runScene }) {
    this.debounceMs = debounceMs;
    this.runScene = runScene;
    this.pending = [];
    this.timer = null;
    this.sceneRunning = false;
    this.scenesStarted = 0; // exposed for tests
  }

  push(event, data) {
    this.pending.push({ event, data, at: new Date().toISOString() });
    if (!this.timer) {
      this.timer = setTimeout(() => this._flush(), this.debounceMs);
      if (typeof this.timer.unref === "function") this.timer.unref();
    }
  }

  _flush() {
    this.timer = null;
    if (this.pending.length === 0) return;
    if (this.sceneRunning) {
      // A scene is mid-flight; try again shortly rather than running two
      // director turns concurrently.
      this.timer = setTimeout(() => this._flush(), 250);
      if (typeof this.timer.unref === "function") this.timer.unref();
      return;
    }
    const batch = this.pending;
    this.pending = [];
    this.sceneRunning = true;
    this.scenesStarted += 1;
    Promise.resolve()
      .then(() => this.runScene(batch))
      .catch((e) => log.error("scene_failed", { error: String(e && e.stack || e) }))
      .finally(() => {
        this.sceneRunning = false;
        if (this.pending.length > 0) {
          this.timer = setTimeout(() => this._flush(), this.debounceMs);
          if (typeof this.timer.unref === "function") this.timer.unref();
        }
      });
  }
}
