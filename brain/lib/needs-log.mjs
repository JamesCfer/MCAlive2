// What the world is short of, written down as it happens.
//
// This replaces reactive scene processing as the thing sense events feed. A director
// turn woken by "somebody walked somewhere" spent real tokens to conclude "Silence -
// a plain, unremarkable terrain sample", over and over, until a five million token
// day was gone. Nothing in the world changed as a result of any of them.
//
// So instead of reacting, we accumulate. Two writers:
//
//   - a death, the moment it happens, with the best cause we can establish
//   - an hourly sweep of the living: what they tried and could not do
//
// and one reader: the developer, once an hour, which turns the loudest entry into an
// actual feature. Tokens are spent building the thing the world is missing rather
// than narrating that nothing happened. That is the whole idea.
//
// The log lives in the ledger as facts/needs-log so it survives a brain restart and
// can be read from the console or the bridge like any other record.

import { log } from "./logger.mjs";

const RECORD_ID = "needs-log";
const MAX_ENTRIES = 200;
/** How recently an attack counts as the reason somebody died. */
const ATTACK_WINDOW_MS = 60_000;

/** Job endings that mean "tried and could not", as opposed to a job that worked. */
const WENT_WELL = ["brought up", "caught", "felled", "made ", "raised", "rested",
                   "slept", "spent time", "traded", "picked something up"];

function failed(why) {
  if (!why) return false;
  return !WENT_WELL.some((g) => why.startsWith(g));
}

export class NeedsLog {
  /**
   * @param {object} p
   * @param {(cmd:string, args:object, timeoutMs?:number) => Promise<any>} p.bridgeCall
   * @param {number} [p.timeoutMs]
   */
  constructor({ bridgeCall, timeoutMs = 20000 }) {
    this.bridgeCall = bridgeCall;
    this.timeoutMs = timeoutMs;
    // npcId -> {attacker, at} for the last thing that hit them. A death with a fresh
    // entry here has a real cause rather than an inferred one.
    this.recentAttacks = new Map();
  }

  // ---------------------------------------------------------------- storage

  async read() {
    try {
      const rec = await this.bridgeCall("ledger_get", { collection: "facts", id: RECORD_ID }, this.timeoutMs);
      return Array.isArray(rec && rec.entries) ? rec.entries : [];
    } catch {
      return [];
    }
  }

  async write(entries) {
    const trimmed = entries.slice(-MAX_ENTRIES);
    await this.bridgeCall("ledger_put", {
      collection: "facts",
      record: {
        id: RECORD_ID,
        kind: "needs-log",
        description: "What killed people and what they could not do. The developer builds from this.",
        updated: new Date().toISOString(),
        entries: trimmed,
      },
    }, this.timeoutMs);
    return trimmed;
  }

  async append(entry) {
    const entries = await this.read();
    entries.push({ at: new Date().toISOString(), status: "open", ...entry });
    return this.write(entries);
  }

  // ---------------------------------------------------------------- deaths

  /**
   * Remember who hit whom. npc_attacked already reaches the brain and carries the
   * attacker, so a death that follows one within the minute needs no guessing.
   */
  noteAttack(data) {
    if (!data || !data.npcId) return;
    this.recentAttacks.set(data.npcId, {
      // `player` is set only when a Player swung; otherwise attackerId is the mob's uuid
      // and attackerType tells us which, so a death by zombie reads as one.
      attacker: data.attackerType === "player" && data.player
        ? data.player
        : (data.attackerType === "mob" ? "a mob" : (data.attackerId || "something")),
      at: Date.now(),
    });
  }

  /**
   * Work out what killed somebody.
   *
   * The plugin's npc_death carries a killer only when a PLAYER did it, and the plugin
   * surface is frozen, so everything else has to be established from what we already
   * have: the attack feed, and the sheet the beat wrote a second before they died.
   */
  async causeOf(data) {
    const id = data.npcId;
    const hit = this.recentAttacks.get(id);
    if (data.killer) return { cause: `killed by ${data.killer}`, by: data.killer, certain: true };
    if (hit && Date.now() - hit.at < ATTACK_WINDOW_MS) {
      return { cause: `killed by ${hit.attacker}`, by: hit.attacker, certain: true };
    }
    let sheet = null;
    try {
      sheet = await this.bridgeCall("ledger_get", { collection: "npcs", id }, this.timeoutMs);
    } catch { /* the sheet is a bonus, not a requirement */ }
    if (sheet) {
      if ((sheet.hunger ?? 20) <= 0) {
        return { cause: "starved", certain: true, doing: sheet.activity, lastJobEnd: sheet.lastJobEnd };
      }
      if ((sheet.hunger ?? 20) <= 4) {
        return { cause: "starving, then something finished it", certain: false, doing: sheet.activity };
      }
      return { cause: "unknown", certain: false, doing: sheet.activity, lastJobEnd: sheet.lastJobEnd };
    }
    return { cause: "unknown", certain: false };
  }

  async recordDeath(data) {
    try {
      const found = await this.causeOf(data);
      const entry = {
        kind: "death",
        who: data.npcName || data.npcId,
        npcId: data.npcId,
        where: data.location || null,
        ...found,
      };
      await this.append(entry);
      this.recentAttacks.delete(data.npcId);
      log.info("needs_log_death", { who: entry.who, cause: entry.cause });
      return entry;
    } catch (e) {
      log.error("needs_log_death_failed", { error: String((e && e.stack) || e) });
      return null;
    }
  }

  // ---------------------------------------------------------------- the hourly sweep

  /**
   * What did the living try, and fail, to do?
   *
   * lastJobEnd is the job methods' own words for how their last attempt went, and the
   * failures are the honest inventory of what this world cannot yet do: "no game" over
   * and over is a food problem, "no rod" is the fishing chain, "could not reach a tree"
   * is a pathing or a terrain problem. Counting them across everybody alive turns forty
   * individual disappointments into one ranked list of what to build.
   */
  async sweep() {
    let npcs = [];
    try {
      const q = await this.bridgeCall("ledger_query", { collection: "npcs" }, this.timeoutMs);
      npcs = (q && q.records) || [];
    } catch (e) {
      log.error("needs_log_sweep_failed", { error: String((e && e.stack) || e) });
      return null;
    }
    const alive = npcs.filter((n) => n.alive !== false);
    if (alive.length === 0) return null;

    const reasons = new Map();
    for (const n of alive) {
      if (!failed(n.lastJobEnd)) continue;
      const key = String(n.lastJobEnd).trim();
      const seen = reasons.get(key) || { count: 0, who: [] };
      seen.count += 1;
      if (seen.who.length < 6) seen.who.push(n.name);
      reasons.set(key, seen);
    }

    const hungry = alive.filter((n) => (n.hunger ?? 20) <= 6).map((n) => n.name);
    const idle = alive.filter((n) => !n.job).map((n) => n.name);

    const ranked = [...reasons.entries()]
      .map(([why, v]) => ({ why, ...v }))
      .sort((a, b) => b.count - a.count);

    const entry = {
      kind: "need",
      population: alive.length,
      stuckOn: ranked.slice(0, 8),
      hungry: { count: hungry.length, who: hungry.slice(0, 8) },
      idle: { count: idle.length, who: idle.slice(0, 8) },
      // The headline is what most people are stuck on. That is the feature to build.
      what: ranked.length
        ? `${ranked[0].count} of ${alive.length} people last failed with "${ranked[0].why}"`
        : `nobody is stuck; ${hungry.length} hungry, ${idle.length} between jobs`,
    };
    await this.append(entry);
    log.info("needs_log_sweep", { what: entry.what, stuck: ranked.length });
    return entry;
  }

  // ---------------------------------------------------------------- the reader

  /**
   * The loudest open entry, shaped like a roadmap feature so the developer can build
   * from it without knowing this file exists. Deaths outrank grumbles: somebody dying
   * of something the world cannot handle is the most urgent thing there is.
   */
  async topFeature() {
    const entries = await this.read();
    const open = entries.filter((e) => e.status === "open");
    if (open.length === 0) return null;

    const deaths = open.filter((e) => e.kind === "death");
    if (deaths.length > 0) {
      // Group deaths by cause: three people starving is one feature, not three.
      const byCause = new Map();
      for (const d of deaths) {
        const k = d.cause || "unknown";
        const g = byCause.get(k) || { cause: k, who: [], entries: [] };
        g.who.push(d.who);
        g.entries.push(d);
        byCause.set(k, g);
      }
      const worst = [...byCause.values()].sort((a, b) => b.who.length - a.who.length)[0];
      return {
        id: `need-death-${Date.now()}`,
        area: "survival",
        title: `Stop people dying: ${worst.cause}`,
        what: `${worst.who.length} ${worst.who.length === 1 ? "person has" : "people have"} died - ${worst.who.join(", ")} - and the cause each time was: ${worst.cause}. `
            + `Find why the world lets this happen and give people what they need to survive it, as a gadget. `
            + `They are players: the answer is something a player could do, not something handed to them.`,
        acceptance: `Somebody in the same situation survives it, and the next sweep does not show the same cause again.`,
        status: "pending",
        source: "needs-log",
        sourceEntries: worst.entries.map((e) => e.at),
      };
    }

    const needs = open.filter((e) => e.kind === "need" && e.stuckOn && e.stuckOn.length);
    if (needs.length === 0) return null;
    // Add up the same complaint across every sweep, so a thing that keeps happening
    // beats a thing that happened loudly once.
    const totals = new Map();
    for (const n of needs) {
      for (const s of n.stuckOn) {
        const t = totals.get(s.why) || { why: s.why, count: 0, who: new Set(), entries: [] };
        t.count += s.count;
        for (const w of s.who || []) t.who.add(w);
        t.entries.push(n.at);
        totals.set(s.why, t);
      }
    }
    const worst = [...totals.values()].sort((a, b) => b.count - a.count)[0];
    if (!worst) return null;
    return {
      id: `need-stuck-${Date.now()}`,
      area: "capability",
      title: `They keep failing at: ${worst.why}`,
      what: `Across recent sweeps, "${worst.why}" is what people's jobs end in most often (${worst.count} times; `
          + `${[...worst.who].slice(0, 6).join(", ")}). Work out what is actually missing - a tool they cannot make, `
          + `a material that does not exist yet, a job that gives up too early, terrain they cannot cross - and build it as a gadget.`,
      acceptance: `The next sweep shows "${worst.why}" materially less often than ${worst.count}.`,
      status: "pending",
      source: "needs-log",
      sourceEntries: worst.entries,
    };
  }

  /** Mark the entries a feature was built from as dealt with, so it is not built twice. */
  async close(sourceEntries) {
    if (!sourceEntries || sourceEntries.length === 0) return;
    const stamps = new Set(sourceEntries);
    const entries = await this.read();
    for (const e of entries) if (stamps.has(e.at)) e.status = "built";
    await this.write(entries);
  }
}
