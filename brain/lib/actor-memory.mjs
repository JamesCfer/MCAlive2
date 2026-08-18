// Rolling per-(npc, player) conversation memory, kept in brain process
// memory only (not persisted - a restart starts conversations fresh, which
// is fine for M1; the ledger is the durable memory, not this transcript).
// DESIGN.md: "rolling conversation transcript (kept in brain memory per
// npc+player, summarized past 20 turns)".

export class ActorMemory {
  constructor(historyTurns = 20) {
    this.historyTurns = historyTurns;
    this.sessions = new Map(); // `${npcId}::${player}` -> { turns: [{who,text,at}], summary: string }
  }

  _key(npcId, player) {
    return `${npcId}::${player}`;
  }

  _session(npcId, player) {
    const key = this._key(npcId, player);
    let s = this.sessions.get(key);
    if (!s) {
      s = { turns: [], summary: "" };
      this.sessions.set(key, s);
    }
    return s;
  }

  /** Record one line of dialogue (who: "player"|"npc"). */
  record(npcId, player, who, text) {
    const s = this._session(npcId, player);
    s.turns.push({ who, text, at: new Date().toISOString() });
    if (s.turns.length > this.historyTurns) {
      const overflow = s.turns.splice(0, s.turns.length - this.historyTurns);
      // Deterministic, offline-safe summarization: fold overflowing turns
      // into a short running summary rather than calling out to a model.
      // Good enough for M1; a real summarizer call can replace this later
      // without changing the ActorMemory interface.
      const folded = overflow.map((t) => `${t.who}: ${t.text}`).join(" / ");
      s.summary = (s.summary ? s.summary + " " : "") + `[earlier: ${folded}]`;
      // Keep the running summary itself from growing unbounded.
      if (s.summary.length > 2000) s.summary = s.summary.slice(s.summary.length - 2000);
    }
  }

  /** Everything to hand the actor prompt for this conversation. */
  transcript(npcId, player) {
    const s = this._session(npcId, player);
    return { summary: s.summary, recent: s.turns.slice() };
  }
}
