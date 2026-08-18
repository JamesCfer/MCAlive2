// Rolling per-(npc, player) conversation memory. DESIGN.md: "rolling
// conversation transcript (kept in brain memory per npc+player, summarized
// past 20 turns)". Persisted to <stateDir>/conversations.json (loaded on
// boot, saved on a debounce) so conversations survive a brain restart - the
// ledger remains the durable WORLD memory, but there is no reason a chat
// mid-conversation should be forgotten just because the process bounced.
// Capped at the most-recently-active 200 (npc, player) pairs so the file
// cannot grow unbounded across a long-running server.

import fs from "node:fs";
import path from "node:path";
import { log } from "./logger.mjs";

const DEFAULT_MAX_PAIRS = 200;
const DEFAULT_SAVE_DEBOUNCE_MS = 500;

export class ActorMemory {
  /**
   * @param {number} historyTurns
   * @param {object} [opts]
   * @param {string} [opts.statePath] - path to conversations.json; omit to disable persistence
   * @param {number} [opts.maxPairs]
   * @param {number} [opts.saveDebounceMs]
   */
  constructor(historyTurns = 20, opts = {}) {
    this.historyTurns = historyTurns;
    this.sessions = new Map(); // `${npcId}::${player}` -> { turns: [{who,text,at}], summary: string, lastAt: string }
    this.statePath = opts.statePath || null;
    this.maxPairs = opts.maxPairs ?? DEFAULT_MAX_PAIRS;
    this.saveDebounceMs = opts.saveDebounceMs ?? DEFAULT_SAVE_DEBOUNCE_MS;
    this._saveTimer = null;
  }

  _key(npcId, player) {
    return `${npcId}::${player}`;
  }

  _session(npcId, player) {
    const key = this._key(npcId, player);
    let s = this.sessions.get(key);
    if (!s) {
      s = { turns: [], summary: "", lastAt: new Date().toISOString() };
      this.sessions.set(key, s);
    }
    return s;
  }

  /** Load persisted conversations from statePath, if configured and present.
   * Safe to call with no file yet (fresh install) - a missing file is not
   * an error. Returns `this` for chaining. */
  load() {
    if (!this.statePath) return this;
    let raw;
    try {
      raw = fs.readFileSync(this.statePath, "utf8");
    } catch (e) {
      if (e && e.code !== "ENOENT") {
        log.warn("actor_memory_load_failed", { error: String(e && e.message || e) });
      }
      return this;
    }
    try {
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === "object") {
        for (const [key, s] of Object.entries(parsed)) {
          this.sessions.set(key, {
            turns: Array.isArray(s.turns) ? s.turns : [],
            summary: typeof s.summary === "string" ? s.summary : "",
            lastAt: s.lastAt || new Date().toISOString(),
          });
        }
      }
      log.info("actor_memory_loaded", { pairs: this.sessions.size, statePath: this.statePath });
    } catch (e) {
      log.warn("actor_memory_load_failed", { error: String(e && e.message || e) });
    }
    return this;
  }

  /** Record one line of dialogue (who: "player"|"npc"). */
  record(npcId, player, who, text) {
    const s = this._session(npcId, player);
    s.turns.push({ who, text, at: new Date().toISOString() });
    s.lastAt = new Date().toISOString();
    if (s.turns.length > this.historyTurns) {
      const overflow = s.turns.splice(0, s.turns.length - this.historyTurns);
      // Deterministic, offline-safe summarization: fold overflowing turns
      // into a short running summary rather than calling out to a model.
      // Good enough for now; a real summarizer call can replace this later
      // without changing the ActorMemory interface.
      const folded = overflow.map((t) => `${t.who}: ${t.text}`).join(" / ");
      s.summary = (s.summary ? s.summary + " " : "") + `[earlier: ${folded}]`;
      // Keep the running summary itself from growing unbounded.
      if (s.summary.length > 2000) s.summary = s.summary.slice(s.summary.length - 2000);
    }
    this._evictOverCap();
    this._scheduleSave();
  }

  /** Drop the least-recently-active pair(s) once over maxPairs. */
  _evictOverCap() {
    while (this.sessions.size > this.maxPairs) {
      let oldestKey = null;
      let oldestAt = null;
      for (const [key, s] of this.sessions) {
        if (oldestAt === null || s.lastAt < oldestAt) {
          oldestAt = s.lastAt;
          oldestKey = key;
        }
      }
      if (oldestKey === null) break;
      this.sessions.delete(oldestKey);
    }
  }

  _scheduleSave() {
    if (!this.statePath) return;
    if (this._saveTimer) return;
    this._saveTimer = setTimeout(() => {
      this._saveTimer = null;
      this._save();
    }, this.saveDebounceMs);
    if (typeof this._saveTimer.unref === "function") this._saveTimer.unref();
  }

  /** Force an immediate, synchronous write - bypassing the debounce. Used
   * on graceful shutdown and by tests simulating a restart. */
  saveSync() {
    if (this._saveTimer) {
      clearTimeout(this._saveTimer);
      this._saveTimer = null;
    }
    this._save();
  }

  _save() {
    if (!this.statePath) return;
    try {
      fs.mkdirSync(path.dirname(this.statePath), { recursive: true });
      const obj = {};
      for (const [key, s] of this.sessions) obj[key] = s;
      fs.writeFileSync(this.statePath, JSON.stringify(obj));
    } catch (e) {
      log.error("actor_memory_save_failed", { error: String(e && e.stack || e) });
    }
  }

  /** Everything to hand the actor prompt for this conversation. */
  transcript(npcId, player) {
    const s = this._session(npcId, player);
    return { summary: s.summary, recent: s.turns.slice() };
  }
}
