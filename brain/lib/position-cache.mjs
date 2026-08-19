// Live NPC/player position cache, fed by the plugin's PUSHED
// "entity_positions" bridge event (brain/gadgets/position-tracker.java,
// auto-installed on boot by index.mjs - see installPositionTracker there),
// broadcast on a ~1s timer. This replaces the world model's old
// polling-free-because-nothing-exists reality (lib/worldmodel.mjs used to
// have NO live position source at all and fell back unconditionally to a
// ledger NPC's `home` coordinate).
//
// Pure/injectable-clock (`nowMs` is always a parameter, never read from
// Date.now() internally except as the caller-facing default) so this stays
// independently unit-testable without a real clock or a real bridge
// connection - see test/smoke.mjs's "position cache" section.
//
// index.mjs is the only writer (onEvent's "entity_positions" branch calls
// updateFromEvent()); lib/worldmodel.mjs is a reader, but only ever through
// a getter passed in via opts.npcPositions - it never imports this module
// directly, so buildWorldModel() stays pure/testable on its own (see
// worldmodel.mjs's normalizeNpcPositionsGetter()).

const DEFAULT_STALE_SEC = 30;

export class PositionCache {
  constructor({ staleSec = DEFAULT_STALE_SEC } = {}) {
    this.staleSec = staleSec;
    this.npcs = new Map(); // id -> {world,x,y,z,at}
    this.players = new Map(); // name -> {world,x,y,z,at}
  }

  /** Feed one pushed "entity_positions" event payload
   * ({at, npcs:[{id,world,x,y,z}], players:[{name,world,x,y,z}]}) into the
   * cache, overwriting each entity's latest known position. `nowMs` is
   * injected (index.mjs passes Date.now(); tests pass a fixed value) so the
   * class never reads the clock on its own - `at` on the payload itself is
   * trusted for staleness math, falling back to `nowMs` only if the plugin
   * didn't stamp one. */
  updateFromEvent(data, nowMs = Date.now()) {
    if (!data || typeof data !== "object") return;
    const at = typeof data.at === "number" && Number.isFinite(data.at) ? data.at : nowMs;
    for (const n of Array.isArray(data.npcs) ? data.npcs : []) {
      if (!n || typeof n.id !== "string") continue;
      this.npcs.set(n.id, { world: n.world, x: n.x, y: n.y, z: n.z, at });
    }
    for (const p of Array.isArray(data.players) ? data.players : []) {
      if (!p || typeof p.name !== "string") continue;
      this.players.set(p.name, { world: p.world, x: p.x, y: p.y, z: p.z, at });
    }
  }

  _get(map, key, nowMs) {
    const entry = map.get(key);
    if (!entry) return null;
    const ageSec = (nowMs - entry.at) / 1000;
    return { ...entry, stale: ageSec > this.staleSec };
  }

  /** Latest known position for one NPC id, or null if never seen.
   * `{world,x,y,z,at,stale}` - `stale:true` once older than `staleSec`
   * (still returned, not hidden, so a caller can choose to show a stale
   * position rather than nothing). */
  npcPosition(id, nowMs = Date.now()) {
    return this._get(this.npcs, id, nowMs);
  }

  /** Latest known position for one player name, or null if never seen. */
  playerPosition(name, nowMs = Date.now()) {
    return this._get(this.players, name, nowMs);
  }

  /** Snapshot of every cached position (fresh + stale), for diagnostics. */
  all(nowMs = Date.now()) {
    const npcs = {};
    for (const id of this.npcs.keys()) npcs[id] = this.npcPosition(id, nowMs);
    const players = {};
    for (const name of this.players.keys()) players[name] = this.playerPosition(name, nowMs);
    return { npcs, players };
  }
}

// The running brain process's one cache instance. index.mjs feeds pushed
// "entity_positions" events into this (never routed to the director
// scheduler - see config.mjs's DIRECTOR_WAKE_EVENTS, which deliberately
// omits "entity_positions") and threads an `npcPosition`-bound getter into
// buildWorldModel() (lib/worldmodel.mjs) for /worldmodel and any other
// world-model consumer running in the SAME process.
//
// Note: mcp-bridge.mjs (the director/actor Agent SDK tool server) is a
// SEPARATE process spawned fresh per query() and explicitly ignores pushed
// bridge events on its own connection (see its onmessage handler's comment)
// - this singleton is never wired into it, since an always-empty cache
// there would be pure overhead for no benefit. Its `world_overview` tool
// keeps using ledger-home positions only, same as before this feature.
export const positionCache = new PositionCache();
