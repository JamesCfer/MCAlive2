// World model: aggregates a snapshot of the live world (places, NPCs,
// terrain, and diagnostics of what's wrong) purely from EXISTING bridge
// commands - no new plugin work. Two consumers:
//   - mcp-bridge.mjs's `world_overview` director tool renders this into a
//     compact text digest (the text-only director's "eyes on the world").
//   - console-server.mjs's `GET /worldmodel` hands the raw JSON to the 3D
//     map viewer (lib/console-server.mjs's renderMapPage()).
//
// Bridge commands used (all pre-existing, see mcp-bridge.mjs / DESIGN.md):
//   - ledger_query {collection:"places"}  -> {records:[{id,name,kind,origin,
//     bounds?,builtBy,...}]} (DESIGN.md places schema)
//   - ledger_query {collection:"npcs"}    -> {records:[{id,name,home,alive,...}]}
//   - scan_area {x1,z1,x2,z2,yHint}       -> {minY,maxY,medianY,flat,
//     columns:[{x,z,y,material}]} for <=256 requested columns, or
//     {grid:[...]} (a fixed 8x8=64-point downsample) for larger areas, up to
//     its own 4096-column hard cap (plugin/.../senses/TerrainSampler.java)
//   - get_block {x,y,z}                   -> {material,blockData} - used
//     ONLY for the NPC off-ground diagnostic, budgeted (see
//     MAX_GET_BLOCK_PROBES below)
//   - gadget:world-scan {world?,region?,step?,maxCells?} -> {ok,world,step,
//     origin:{x,z},cols,rows,sampledColumns,heights:[[int|null,...],...],
//     surface,loadedChunks,bounds:{x1,z1,x2,z2},spawn:{x,y,z},
//     players:[{name,x,y,z}]} (brain/gadgets/world-scan.java, auto-installed
//     on boot by index.mjs's installWorldScan - see README "Gadgets").
//     PREFERRED terrain source: it surveys every currently-loaded chunk
//     (spawn, wilderness, wherever players have gone), not just the
//     bounding box of recorded places. Falls back to the pre-existing
//     scan_area-over-places behavior below when the gadget call fails
//     (older server, gadgets disabled, connection down) - see
//     terrainFromWorldScan()/buildWorldModel()'s try/catch.
//
// No npc_list/npc_get (live NPC position) REQUEST/RESPONSE bridge command
// exists - only ledger_query/ledger_get and npc_context expose NPC data, and
// none of those carry a *live* position. Live positions instead arrive as a
// PUSHED "entity_positions" bridge event (brain/gadgets/position-tracker.java,
// auto-installed on boot by index.mjs), cached by lib/position-cache.mjs.
// buildWorldModel() stays pure/testable by never importing that cache
// directly - callers (index.mjs) pass a getter/map via opts.npcPositions
// (see normalizeNpcPositionsGetter() below). When no live position is cached
// for an NPC (never seen yet, or opts.npcPositions wasn't supplied at all -
// e.g. mcp-bridge.mjs's world_overview, a separate short-lived process that
// never sees pushed events), NPC "pos" falls back to the ledger record's
// `home` coordinate, flagged so callers know it is a static approximation,
// not a live read.

// Hard cap on total get_block calls a single buildWorldModel() run may make
// (all spent on the NPC off-ground diagnostic) - keeps a scene with many
// NPCs from turning "check the world" into a slow storm of round trips.
const MAX_GET_BLOCK_PROBES = 40;
// scan_area's own hard cap (TerrainSampler.SCAN_AREA_MAX_COLUMNS) - requests
// are shrunk to fit under this before being sent.
const SCAN_AREA_MAX_COLUMNS = 4096;
// Fallback half-size (in blocks) for the terrain scan footprint when there
// are no places to bound around. No bridge command reports the world's
// configured spawn point, so this is centered on world origin (0,0).
const DEFAULT_AREA_RADIUS = 64;
// How far a place's bounding box (or origin) pads the auto-computed terrain
// area, so a scan isn't razor-tight around a single point.
const AREA_PADDING = 8;
// Default cap on cols*rows sampled by the world-scan gadget (mirrors its own
// default - see gadgets/world-scan.java) when the caller (index.mjs's
// config.worldScanMaxCells) doesn't override it.
const DEFAULT_WORLD_SCAN_MAX_CELLS = 4096;
// Vertical threshold (blocks) a place must clear before floating/buried.
const PLACE_Y_THRESHOLD = 3;
// Vertical thresholds (blocks) an NPC must clear before "off-ground".
const NPC_ABOVE_THRESHOLD = 2;
const NPC_BELOW_THRESHOLD = 1;

function isFiniteNum(v) {
  return typeof v === "number" && Number.isFinite(v);
}

function clamp(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v));
}

/** Normalizes opts.npcPositions - a `(id) => pos|null` getter, a Map, or a
 * plain object keyed by npc id - into a single getter function, so the rest
 * of buildWorldModel() doesn't care which shape the caller passed. Absent
 * entirely -> every lookup returns null (today's ledger-home-only
 * behavior). Keeps this module import-free of lib/position-cache.mjs, so it
 * stays independently testable with plain injected data. */
function normalizeNpcPositionsGetter(npcPositions) {
  if (!npcPositions) return () => null;
  if (typeof npcPositions === "function") return (id) => npcPositions(id) || null;
  if (npcPositions instanceof Map) return (id) => npcPositions.get(id) || null;
  return (id) => npcPositions[id] || null;
}

/** Extracts a {x1,y1,z1,x2,y2,z2} bounds object from a place record, or null
 * if the record carries no usable bounds (DESIGN.md marks `bounds` optional
 * on the places collection). */
function placeBounds(record) {
  const b = record && record.bounds;
  if (!b || typeof b !== "object") return null;
  const keys = ["x1", "y1", "z1", "x2", "y2", "z2"];
  if (!keys.every((k) => isFiniteNum(b[k]))) return null;
  return { x1: b.x1, y1: b.y1, z1: b.z1, x2: b.x2, y2: b.y2, z2: b.z2 };
}

function placeOrigin(record) {
  const o = record && record.origin;
  if (o && isFiniteNum(o.x) && isFiniteNum(o.y) && isFiniteNum(o.z)) {
    return { x: o.x, y: o.y, z: o.z };
  }
  return { x: 0, y: 0, z: 0 };
}

/** Turns a scan_area result (either {columns:[...]} for small areas or
 * {grid:[...]} for the fixed 8x8 downsample of large ones) into the pinned
 * terrain shape: a dense 2D heights grid keyed off the DISTINCT x/z values
 * actually present in the response (robust to either shape's point order). */
function terrainFromScan(scan) {
  const points = (scan && (scan.columns || scan.grid)) || [];
  if (!points.length) return null;

  const xs = [...new Set(points.map((p) => p.x))].sort((a, b) => a - b);
  const zs = [...new Set(points.map((p) => p.z))].sort((a, b) => a - b);
  if (!xs.length || !zs.length) return null;

  const heightAt = new Map();
  for (const p of points) heightAt.set(`${p.x},${p.z}`, isFiniteNum(p.y) ? p.y : null);

  const heights = xs.map((x) => zs.map((z) => {
    const h = heightAt.get(`${x},${z}`);
    return isFiniteNum(h) ? h : null;
  }));

  const stepX = xs.length > 1 ? (xs[xs.length - 1] - xs[0]) / (xs.length - 1) : 1;
  const stepZ = zs.length > 1 ? (zs[zs.length - 1] - zs[0]) / (zs.length - 1) : 1;

  return {
    origin: { x: xs[0], z: zs[0] },
    step: stepX || stepZ || 1,
    cols: zs.length,
    rows: xs.length,
    heights,
    materials: null,
  };
}

/** Maps a gadget:world-scan response onto the SAME pinned terrain shape
 * terrainFromScan() produces - {origin:{x,z}, step, cols, rows, heights} -
 * so every consumer (nearestGroundHeight, console-server.mjs's terrain
 * renderer) works unchanged regardless of which source produced it.
 *
 * The two sources number their axes oppositely: terrainFromScan's `heights`
 * is indexed [xIndex][zIndex] with `rows` = count of distinct x values and
 * `cols` = count of distinct z values (see its own comment). The gadget's
 * `heights` is ALSO indexed [ix][iz] with ix running over x (0..cols-1) and
 * iz running over z (0..rows-1) - i.e. the gadget's `cols` is an x-count and
 * its `rows` is a z-count, the opposite naming. The array itself needs no
 * reshaping (both are already [xIndex][zIndex]); only the cols/rows SCALARS
 * swap names on the way in. Nulls (chunk not loaded) pass through as-is. */
function terrainFromWorldScan(scan) {
  if (!scan || !Array.isArray(scan.heights) || !scan.heights.length) return null;
  const origin = scan.origin && isFiniteNum(scan.origin.x) && isFiniteNum(scan.origin.z)
    ? { x: scan.origin.x, z: scan.origin.z } : { x: 0, z: 0 };
  // `surface` is an optional [cols][rows] array of block-type names (or
  // nulls) the gadget only includes for smaller scans - same [ix][iz]
  // indexing as `heights`, so it can be carried through with no reshaping.
  // Absent (large scans) -> materials stays null, and every consumer (the
  // /map voxel renderer) falls back to the height-based colour ramp.
  const materials = Array.isArray(scan.surface) && scan.surface.length ? scan.surface : null;
  return {
    origin,
    step: isFiniteNum(scan.step) && scan.step > 0 ? scan.step : 1,
    rows: isFiniteNum(scan.cols) ? scan.cols : scan.heights.length, // x-count
    cols: isFiniteNum(scan.rows) ? scan.rows : (scan.heights[0] || []).length, // z-count
    heights: scan.heights,
    materials,
  };
}

/** Min/max Y across a terrain grid's non-null heights, or null if every cell
 * is null (nothing loaded yet). Used to derive bounds.minY/maxY from a
 * gadget:world-scan terrain, which reports no Y range of its own. */
function terrainYRange(terrain) {
  if (!terrain || !terrain.heights.length) return null;
  let minY = Infinity, maxY = -Infinity;
  for (const row of terrain.heights) {
    if (!row) continue;
    for (const h of row) {
      if (!isFiniteNum(h)) continue;
      if (h < minY) minY = h;
      if (h > maxY) maxY = h;
    }
  }
  return minY <= maxY ? { minY, maxY } : null;
}

/** Nearest terrain grid height under a world (x,z), or null if there is no
 * terrain data (scan skipped/failed) or the point falls entirely outside
 * the scanned grid's index range after clamping - used as the "local ground
 * surface" a place's floating/buried diagnostics compare against. */
function nearestGroundHeight(terrain, x, z) {
  if (!terrain || !terrain.heights.length) return null;
  const step = terrain.step || 1;
  const ix = clamp(Math.round((x - terrain.origin.x) / step), 0, terrain.rows - 1);
  const iz = clamp(Math.round((z - terrain.origin.z) / step), 0, terrain.cols - 1);
  const row = terrain.heights[ix];
  const h = row ? row[iz] : null;
  return isFiniteNum(h) ? h : null;
}

/** Clamps a requested [minX,maxX]x[minZ,maxZ] scan footprint so its column
 * count fits under scan_area's own hard cap, shrinking symmetrically around
 * the footprint's center rather than failing outright. */
function clampScanFootprint(minX, minZ, maxX, maxZ) {
  let x1 = Math.round(minX), z1 = Math.round(minZ), x2 = Math.round(maxX), z2 = Math.round(maxZ);
  if (x2 < x1) [x1, x2] = [x2, x1];
  if (z2 < z1) [z1, z2] = [z2, z1];
  const cols = (x2 - x1 + 1) * (z2 - z1 + 1);
  if (cols <= SCAN_AREA_MAX_COLUMNS) return { x1, z1, x2, z2 };
  const cx = (x1 + x2) / 2, cz = (z1 + z2) / 2;
  const side = Math.max(1, Math.floor(Math.sqrt(SCAN_AREA_MAX_COLUMNS)) - 1);
  return {
    x1: Math.round(cx - side / 2), z1: Math.round(cz - side / 2),
    x2: Math.round(cx + side / 2), z2: Math.round(cz + side / 2),
  };
}

/** Best-effort "is this block solid ground" check from get_block's
 * {material,blockData} reply - a coarse heuristic (no "passable" flag is
 * returned by the bridge), good enough for a diagnostic digest rather than
 * precise physics. */
function looksSolid(getBlockResult) {
  const mat = String((getBlockResult && getBlockResult.material) || "").toLowerCase();
  if (!mat) return null; // unknown - probe failed or returned nothing usable
  if (mat.includes("air")) return false;
  if (mat === "water" || mat === "lava") return false;
  return true;
}

/** Budgeted off-ground check for one NPC: at most 3 get_block probes (feet
 * block, one below, two below), spent from the shared `budget` counter so
 * the total across every NPC never exceeds MAX_GET_BLOCK_PROBES. Returns
 * `{ offGround, message }` or null if the budget ran out before this NPC
 * could be checked at all (silently skipped, not flagged in error). */
async function checkNpcGrounding(call, pos, budget) {
  const fx = Math.floor(pos.x), fz = Math.floor(pos.z), fy = Math.floor(pos.y);
  const probe = async (yy) => {
    if (budget.remaining <= 0) return null;
    budget.remaining -= 1;
    try {
      return await call("get_block", { x: fx, y: yy, z: fz });
    } catch {
      return null;
    }
  };

  const atFeet = await probe(fy);
  const atFeetSolid = looksSolid(atFeet);
  if (atFeetSolid === true) {
    return { offGround: true, message: "possibly floating or in terrain (standing inside a solid block)" };
  }

  let foundGroundAt = null;
  for (let d = 1; d <= 1 + NPC_ABOVE_THRESHOLD; d++) {
    const below = await probe(fy - d);
    const solid = looksSolid(below);
    if (solid === true) { foundGroundAt = fy - d; break; }
    if (solid === null) break; // ran out of budget or probe failed - stop, treat as unknown
  }

  if (foundGroundAt === null) {
    // No solid ground found within the probed window below the NPC -
    // consistent with the NPC standing more than NPC_ABOVE_THRESHOLD blocks
    // above any surface this budget could find.
    return { offGround: true, message: "possibly floating or in terrain (no solid ground found nearby below)" };
  }
  const offsetAboveGround = fy - foundGroundAt - 1; // 1 = standing directly on top of solid ground
  if (offsetAboveGround > NPC_ABOVE_THRESHOLD || offsetAboveGround < -NPC_BELOW_THRESHOLD) {
    return { offGround: true, message: `possibly floating or in terrain (${offsetAboveGround} blocks above nearest ground)` };
  }
  return { offGround: false, message: null };
}

/**
 * @param {(cmd: string, args?: object) => Promise<any>} call - issues one
 *   bridge command (pass in BridgeClient#call bound to a client, or an
 *   equivalent - see mcp-bridge.mjs's `call()` / test fakes below).
 * @param {object} opts
 * @param {string} opts.now - ISO timestamp for `generatedAt` (injected, not
 *   read from Date, so this module is deterministic/testable).
 * @param {{x1:number,z1:number,x2:number,z2:number}} [opts.area] - explicit
 *   terrain scan footprint (world_overview's `area` param); overrides the
 *   auto-computed bounding box of all places.
 * @param {number} [opts.maxGetBlockProbes] - override MAX_GET_BLOCK_PROBES (tests only).
 * @param {number} [opts.worldScanMaxCells] - maxCells passed to the
 *   gadget:world-scan terrain call (default DEFAULT_WORLD_SCAN_MAX_CELLS).
 * @param {((id: string) => object|null)|Map|object} [opts.npcPositions] -
 *   live-position source (a getter, a Map, or a plain object keyed by npc
 *   id), each entry shaped `{world?,x,y,z,at?,stale?}`. When an entry exists
 *   and carries finite x/y/z it is preferred over the ledger's `home`
 *   coordinate; `stale:true` on the entry adds the "position-stale" flag
 *   instead of dropping the position. Omit entirely to keep today's
 *   ledger-home-only behavior (see index.mjs / lib/position-cache.mjs).
 * @returns {Promise<object>} the pinned world model shape - see module header.
 */
export async function buildWorldModel(call, opts = {}) {
  const generatedAt = opts.now;
  const diagnostics = [];
  const getLivePosition = normalizeNpcPositionsGetter(opts.npcPositions);

  // ---- places ----
  let placeRecords = [];
  try {
    const res = await call("ledger_query", { collection: "places" });
    placeRecords = (res && res.records) || [];
  } catch (e) {
    diagnostics.push({ severity: "error", kind: "query-failed", subject: "places", message: `ledger_query(places) failed: ${e.message}`, at: null });
  }

  // ---- npcs ----
  let npcRecords = [];
  try {
    const res = await call("ledger_query", { collection: "npcs" });
    npcRecords = (res && res.records) || [];
  } catch (e) {
    diagnostics.push({ severity: "error", kind: "query-failed", subject: "npcs", message: `ledger_query(npcs) failed: ${e.message}`, at: null });
  }

  const places = placeRecords.map((r) => ({
    id: r.id,
    name: r.name || r.id,
    kind: r.kind || "unknown",
    origin: placeOrigin(r),
    bounds: placeBounds(r),
    builtBy: r.builtBy || "unknown",
    flags: [],
  }));

  // Prefer a live position (pushed "entity_positions" event, cached - see
  // module header) over the ledger's static `home` coordinate. Stale-but-
  // present live data is still used (flagged, not discarded) rather than
  // silently reverting to ledger-home, which would hide a gadget/plugin
  // problem behind an equally-plausible-looking static answer.
  const npcs = npcRecords.map((r) => {
    const flags = [];
    let pos = null;
    const live = getLivePosition(r.id);
    if (live && isFiniteNum(live.x) && isFiniteNum(live.y) && isFiniteNum(live.z)) {
      pos = { x: live.x, y: live.y, z: live.z };
      if (live.stale) flags.push("position-stale");
    } else if (r.home && isFiniteNum(r.home.x) && isFiniteNum(r.home.y) && isFiniteNum(r.home.z)) {
      pos = { x: r.home.x, y: r.home.y, z: r.home.z };
      flags.push("position-from-ledger-home (no live-position cached)");
    }
    return {
      id: r.id,
      name: r.name || r.id,
      role: r.role || r.faction || "unknown",
      pos,
      alive: r.alive !== false,
      flags,
    };
  });

  // ---- places-bounds: bounding box of all places (padded), or a default
  // footprint around world origin when there are none yet (no bridge
  // command reports the configured spawn point). Used as: (a) the
  // terrain-scan fallback footprint when the world-scan gadget is
  // unavailable, and (b) unioned into the gadget-derived bounds below so a
  // recorded place or live NPC outside the currently-loaded world extent
  // still ends up on the map. ----
  let minX, minZ, maxX, maxZ, minY, maxY;
  if (places.length) {
    minX = Infinity; minZ = Infinity; maxX = -Infinity; maxZ = -Infinity; minY = Infinity; maxY = -Infinity;
    for (const p of places) {
      const b = p.bounds;
      if (b) {
        minX = Math.min(minX, b.x1, b.x2); maxX = Math.max(maxX, b.x1, b.x2);
        minZ = Math.min(minZ, b.z1, b.z2); maxZ = Math.max(maxZ, b.z1, b.z2);
        minY = Math.min(minY, b.y1, b.y2); maxY = Math.max(maxY, b.y1, b.y2);
      } else {
        minX = Math.min(minX, p.origin.x); maxX = Math.max(maxX, p.origin.x);
        minZ = Math.min(minZ, p.origin.z); maxZ = Math.max(maxZ, p.origin.z);
        minY = Math.min(minY, p.origin.y); maxY = Math.max(maxY, p.origin.y);
      }
    }
    minX -= AREA_PADDING; maxX += AREA_PADDING;
    minZ -= AREA_PADDING; maxZ += AREA_PADDING;
    minY -= AREA_PADDING; maxY += AREA_PADDING;
  } else {
    minX = -DEFAULT_AREA_RADIUS; maxX = DEFAULT_AREA_RADIUS;
    minZ = -DEFAULT_AREA_RADIUS; maxZ = DEFAULT_AREA_RADIUS;
    minY = 0; maxY = 128;
  }
  const placesBounds = { minX, minZ, maxX, maxZ, minY, maxY };

  // ---- terrain: PREFER gadget:world-scan - it surveys every currently-
  // loaded chunk (spawn, wilderness, wherever players have explored)
  // instead of only the bounding box of recorded places. Falls back to the
  // pre-existing scan_area-over-places behavior (below) when the gadget
  // call fails - older server, gadgets disabled, connection down - never a
  // crash either way; the fallback is recorded in `notes` so a caller/
  // operator can tell which mode produced the map. See gadgets/world-
  // scan.java and terrainFromWorldScan() above. ----
  const notes = [];
  let terrain = null;
  let bounds = null;
  let spawn = null;
  let players = [];
  let loadedChunks = null;
  try {
    const maxCells = isFiniteNum(opts.worldScanMaxCells) ? opts.worldScanMaxCells : DEFAULT_WORLD_SCAN_MAX_CELLS;
    const scan = await call("gadget:world-scan", { maxCells });
    const scannedTerrain = terrainFromWorldScan(scan);
    if (scan && scan.ok !== false && scannedTerrain) {
      terrain = scannedTerrain;
      loadedChunks = isFiniteNum(scan.loadedChunks) ? scan.loadedChunks : null;
      if (scan.spawn && isFiniteNum(scan.spawn.x) && isFiniteNum(scan.spawn.y) && isFiniteNum(scan.spawn.z)) {
        spawn = { x: scan.spawn.x, y: scan.spawn.y, z: scan.spawn.z };
      }
      if (Array.isArray(scan.players)) {
        players = scan.players
          .filter((p) => p && typeof p.name === "string" && isFiniteNum(p.x) && isFiniteNum(p.y) && isFiniteNum(p.z))
          .map((p) => ({ name: p.name, x: p.x, y: p.y, z: p.z }));
      }
      const b = scan.bounds;
      if (b && ["x1", "z1", "x2", "z2"].every((k) => isFiniteNum(b[k]))) {
        const yRange = terrainYRange(terrain);
        bounds = {
          minX: Math.min(b.x1, b.x2), maxX: Math.max(b.x1, b.x2),
          minZ: Math.min(b.z1, b.z2), maxZ: Math.max(b.z1, b.z2),
          minY: yRange ? yRange.minY : placesBounds.minY,
          maxY: yRange ? yRange.maxY : placesBounds.maxY,
        };
      }
    } else {
      notes.push("gadget:world-scan returned no usable terrain; falling back to a scan_area scan over recorded places (only that footprint is shown, not the whole loaded world)");
    }
  } catch (e) {
    notes.push(`gadget:world-scan unavailable (${e.message}); falling back to a scan_area scan over recorded places (only that footprint is shown, not the whole loaded world)`);
  }

  if (!bounds) bounds = { ...placesBounds };

  // Union the map extent with every recorded place and every NPC's current
  // position, so nothing recorded falls outside the map even if it sits
  // beyond the currently-loaded world (or the gadget was unavailable and
  // bounds is places-only to begin with).
  for (const p of places) {
    const b = p.bounds;
    if (b) {
      bounds.minX = Math.min(bounds.minX, b.x1, b.x2); bounds.maxX = Math.max(bounds.maxX, b.x1, b.x2);
      bounds.minZ = Math.min(bounds.minZ, b.z1, b.z2); bounds.maxZ = Math.max(bounds.maxZ, b.z1, b.z2);
      bounds.minY = Math.min(bounds.minY, b.y1, b.y2); bounds.maxY = Math.max(bounds.maxY, b.y1, b.y2);
    } else {
      bounds.minX = Math.min(bounds.minX, p.origin.x); bounds.maxX = Math.max(bounds.maxX, p.origin.x);
      bounds.minZ = Math.min(bounds.minZ, p.origin.z); bounds.maxZ = Math.max(bounds.maxZ, p.origin.z);
      bounds.minY = Math.min(bounds.minY, p.origin.y); bounds.maxY = Math.max(bounds.maxY, p.origin.y);
    }
  }
  for (const n of npcs) {
    if (!n.pos) continue;
    bounds.minX = Math.min(bounds.minX, n.pos.x); bounds.maxX = Math.max(bounds.maxX, n.pos.x);
    bounds.minZ = Math.min(bounds.minZ, n.pos.z); bounds.maxZ = Math.max(bounds.maxZ, n.pos.z);
    bounds.minY = Math.min(bounds.minY, n.pos.y); bounds.maxY = Math.max(bounds.maxY, n.pos.y);
  }

  // ---- fallback terrain: one coarse scan_area over the area of interest,
  // exactly as before this feature existed - only reached when the gadget
  // above was unavailable/unusable. ----
  if (!terrain) {
    const scanFootprint = opts.area
      ? clampScanFootprint(opts.area.x1, opts.area.z1, opts.area.x2, opts.area.z2)
      : clampScanFootprint(placesBounds.minX, placesBounds.minZ, placesBounds.maxX, placesBounds.maxZ);
    const footprintEmpty = scanFootprint.x2 < scanFootprint.x1 || scanFootprint.z2 < scanFootprint.z1;
    if (!footprintEmpty) {
      try {
        const yHint = Math.round((placesBounds.minY + placesBounds.maxY) / 2);
        const scan = await call("scan_area", { x1: scanFootprint.x1, z1: scanFootprint.z1, x2: scanFootprint.x2, z2: scanFootprint.z2, yHint });
        terrain = terrainFromScan(scan);
      } catch (e) {
        diagnostics.push({ severity: "warn", kind: "terrain-scan-failed", subject: "terrain", message: `scan_area failed: ${e.message}`, at: null });
      }
    }
  }

  // ---- diagnostics: places (floating / buried) ----
  for (const place of places) {
    const groundY = nearestGroundHeight(terrain, place.origin.x, place.origin.z);
    if (groundY == null) continue;
    const bottomY = place.bounds ? Math.min(place.bounds.y1, place.bounds.y2) : place.origin.y;
    const offset = bottomY - groundY;
    if (place.builtBy === "ai" && offset > PLACE_Y_THRESHOLD) {
      place.flags.push("floating");
      diagnostics.push({
        severity: "error",
        kind: "floating",
        subject: place.id,
        message: `${place.name} sits ${offset} block(s) above the scanned ground surface (builtBy=ai)`,
        at: place.origin,
      });
    } else if (offset < -PLACE_Y_THRESHOLD) {
      place.flags.push("buried");
      diagnostics.push({
        severity: "warn",
        kind: "buried",
        subject: place.id,
        message: `${place.name} is buried ${-offset} block(s) below the scanned ground surface`,
        at: place.origin,
      });
    }
  }

  // ---- diagnostics: npcs (no-position / off-ground / dead) ----
  const probeBudget = { remaining: isFiniteNum(opts.maxGetBlockProbes) ? opts.maxGetBlockProbes : MAX_GET_BLOCK_PROBES };
  for (const npc of npcs) {
    if (!npc.alive) {
      diagnostics.push({ severity: "info", kind: "dead", subject: npc.id, message: `${npc.name} is dead`, at: npc.pos || null });
      continue;
    }
    if (!npc.pos) {
      diagnostics.push({ severity: "warn", kind: "no-position", subject: npc.id, message: `${npc.name} has no known position (no cached live position and no ledger home)`, at: null });
      continue;
    }
    if (probeBudget.remaining <= 0) continue; // probe budget exhausted - skip silently, don't false-flag
    let result;
    try {
      result = await checkNpcGrounding(call, npc.pos, probeBudget);
    } catch {
      result = null;
    }
    if (result && result.offGround) {
      npc.flags.push("off-ground");
      diagnostics.push({ severity: "error", kind: "off-ground", subject: npc.id, message: `${npc.name}: ${result.message}`, at: npc.pos });
    }
  }

  return { generatedAt, bounds, places, npcs, terrain, diagnostics, spawn, players, notes, loadedChunks };
}

// ---------------------------------------------------------------------------
// Text digest - how the text-only director "sees" the world model. Pure
// (takes the already-built model, no I/O) so it's independently unit
// testable from buildWorldModel().
// ---------------------------------------------------------------------------

const SEVERITY_ORDER = { error: 0, warn: 1, info: 2 };
const SEVERITY_LABEL = { error: "ERROR", warn: "WARN", info: "INFO" };
// Kept small deliberately (see module-top-of-file token-blowout note in
// mcp-bridge.mjs / lib/tool-result-cap.mjs): a director calling
// world_overview with detail:"full" several times in one scene pays for
// this digest's FULL size again on every subsequent step of that turn, not
// just once - so the whole thing (map + lists) must stay comfortably under
// BRAIN_MAX_TOOL_RESULT_CHARS (default 3000), not merely "not enormous".
const MAP_MAX_WIDTH = 24;
const MAP_MAX_HEIGHT = 24;
// Cap on how many entries the PLACES / flagged-NPCs / PROBLEMS lists show
// before collapsing the rest into a "+N more" note. Deliberately small: the
// digest has THREE such lists plus the map/legend, and every one of them
// has to fit in the same ~3000-char budget together (BRAIN_MAX_TOOL_RESULT_
// CHARS default) - 15 verbose entries per list would blow the combined
// budget on its own even before free-text truncation is accounted for.
const LIST_CAP = 5;
// Cap on any free-text field (place name/kind/builtBy, npc name/role,
// diagnostic message) so a single verbose record can't blow the whole
// digest's budget by itself.
const FREE_TEXT_MAX_CHARS = 40;
// Legend entries ("A=Name") get an even tighter cap - they're purely a map
// key, not the primary information (the PLACES list already carries the
// full truncated name).
const LEGEND_NAME_MAX_CHARS = 16;

function fmtCoord(pos) {
  if (!pos) return "(unknown)";
  return `(${Math.round(pos.x)}, ${Math.round(pos.y)}, ${Math.round(pos.z)})`;
}

function fmtFlags(flags) {
  return flags && flags.length ? ` [${flags.join(", ")}]` : "";
}

/** Truncates a free-text field to FREE_TEXT_MAX_CHARS (default), non-string
 * values passed through unchanged (callers already guard most call sites,
 * this is just cheap insurance). */
function truncateText(s, max = FREE_TEXT_MAX_CHARS) {
  if (typeof s !== "string") return s;
  return s.length > max ? s.slice(0, max - 1) + "…" : s;
}

/** Splits `list` into the first `max` entries plus an omitted count, for
 * every capped section of the digest (PLACES, flagged NPCs, PROBLEMS, the
 * map legend). */
function capList(list, max = LIST_CAP) {
  if (!list || list.length <= max) return { shown: list || [], omitted: 0 };
  return { shown: list.slice(0, max), omitted: list.length - max };
}

function placeSizeText(place) {
  if (!place.bounds) return null;
  const b = place.bounds;
  const w = Math.abs(b.x2 - b.x1) + 1, h = Math.abs(b.y2 - b.y1) + 1, d = Math.abs(b.z2 - b.z1) + 1;
  return `${w}x${h}x${d}`;
}

/** Draws a top-down (x horizontal, z vertical) ASCII map: one letter per
 * place (reused if there are more places than letters), '+' for the world
 * spawn (0,0) when it falls inside the drawn bounds, '.' for empty ground,
 * scaled so the widest row is at most MAP_MAX_WIDTH characters. */
function drawAsciiMap(model) {
  const { minX, minZ, maxX, maxZ } = model.bounds;
  const spanX = Math.max(1, maxX - minX), spanZ = Math.max(1, maxZ - minZ);
  const cols = Math.max(1, Math.min(MAP_MAX_WIDTH, spanX + 1));
  const rows = Math.max(1, Math.min(MAP_MAX_HEIGHT, Math.round((spanZ + 1) * (cols / (spanX + 1)))));

  const grid = Array.from({ length: rows }, () => Array(cols).fill("."));

  const toCell = (x, z) => {
    const cx = clamp(Math.round(((x - minX) / spanX) * (cols - 1)), 0, cols - 1);
    const cz = clamp(Math.round(((z - minZ) / spanZ) * (rows - 1)), 0, rows - 1);
    return { cx, cz };
  };

  // Spawn marker first so a place letter at the exact same cell wins (more
  // informative than a bare '+'). Prefers the real spawn point reported by
  // gadget:world-scan; falls back to world origin (0,0) when unavailable
  // (pre-existing behavior).
  const sx = model.spawn ? model.spawn.x : 0, sz = model.spawn ? model.spawn.z : 0;
  if (minX <= sx && sx <= maxX && minZ <= sz && sz <= maxZ) {
    const { cx, cz } = toCell(sx, sz);
    grid[cz][cx] = "+";
  }

  const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  // Legend text (not the grid cells themselves) is the part that can blow
  // the digest's size budget with many places, so legend/letter-assignment
  // only covers the first LIST_CAP places - see PLACES section below, which
  // caps the same way for the same reason.
  const { shown: legendPlaces, omitted: legendOmitted } = capList(model.places, LIST_CAP);
  legendPlaces.forEach((place, i) => {
    const letter = ALPHABET[i % ALPHABET.length];
    const { cx, cz } = toCell(place.origin.x, place.origin.z);
    grid[cz][cx] = letter;
  });

  const legend = legendPlaces.map((p, i) => `${ALPHABET[i % ALPHABET.length]}=${truncateText(p.name, LEGEND_NAME_MAX_CHARS)}`).join("  ");
  const mapLines = grid.map((row) => row.join(""));
  return { mapLines, legend, legendOmitted };
}

/**
 * Renders a buildWorldModel() result into a compact text digest - the
 * text-only director's view of "what exists and what's wrong". `detail`
 * "full" additionally draws the top-down ASCII map; "summary" (default)
 * omits it for a shorter digest.
 */
export function formatWorldOverview(model, opts = {}) {
  const detail = opts.detail === "full" ? "full" : "summary";
  const lines = [];

  const aliveCount = model.npcs.filter((n) => n.alive).length;
  const deadCount = model.npcs.length - aliveCount;
  const errorCount = model.diagnostics.filter((d) => d.severity === "error").length;
  const warnCount = model.diagnostics.filter((d) => d.severity === "warn").length;
  const infoCount = model.diagnostics.filter((d) => d.severity === "info").length;

  lines.push(
    `World overview (generated ${model.generatedAt}): ${model.places.length} place(s), ` +
    `${model.npcs.length} NPC(s) (${aliveCount} alive / ${deadCount} dead), ` +
    `${model.diagnostics.length} problem(s) (${errorCount} error / ${warnCount} warn / ${infoCount} info)`
  );

  // Extent line: how much of the world this snapshot actually covers - the
  // gadget:world-scan-derived bounds/loadedChunks/spawn when available, so
  // the director knows whether it's looking at the whole loaded world or
  // just the fallback footprint around recorded places (see `notes`).
  if (model.bounds) {
    const b = model.bounds;
    const extentBits = [`x:${Math.round(b.minX)}..${Math.round(b.maxX)} z:${Math.round(b.minZ)}..${Math.round(b.maxZ)}`];
    if (typeof model.loadedChunks === "number") extentBits.push(`${model.loadedChunks} loaded chunk(s)`);
    lines.push(`Extent: ${extentBits.join(", ")}`);
  }
  if (model.spawn) {
    lines.push(`Spawn: ${fmtCoord(model.spawn)}`);
  }
  if (model.players && model.players.length) {
    lines.push(`Players online: ${model.players.map((p) => `${p.name} @ ${fmtCoord(p)}`).join(", ")}`);
  }
  if (model.notes && model.notes.length) {
    for (const n of model.notes) lines.push(`Note: ${n}`);
  }

  lines.push("");
  lines.push(`PLACES (${model.places.length})`);
  if (!model.places.length) {
    lines.push("  (none recorded)");
  } else {
    const { shown: placesShown, omitted: placesOmitted } = capList(model.places, LIST_CAP);
    for (const p of placesShown) {
      const size = placeSizeText(p);
      lines.push(
        `  - ${truncateText(p.name)} (${truncateText(p.kind)}) @ ${fmtCoord(p.origin)}` +
        (size ? ` size ${size}` : "") +
        ` builtBy=${truncateText(p.builtBy)}${fmtFlags(p.flags)}`
      );
    }
    if (placesOmitted > 0) lines.push(`  ... +${placesOmitted} more place(s) omitted - narrow your query (filter/smaller area) to see them`);
  }

  lines.push("");
  lines.push(`NPCS: ${aliveCount} alive / ${deadCount} dead`);
  const flaggedNpcs = model.npcs.filter((n) => n.flags.length);
  if (flaggedNpcs.length) {
    const { shown: npcsShown, omitted: npcsOmitted } = capList(flaggedNpcs, LIST_CAP);
    for (const n of npcsShown) {
      lines.push(`  - ${truncateText(n.name)} (${truncateText(n.role)}) @ ${fmtCoord(n.pos)} ${n.alive ? "alive" : "dead"}${fmtFlags(n.flags)}`);
    }
    if (npcsOmitted > 0) lines.push(`  ... +${npcsOmitted} more flagged NPC(s) omitted - narrow your query to see them`);
  } else {
    lines.push("  (no flagged NPCs)");
  }

  if (detail === "full") {
    lines.push("");
    lines.push("MAP (top-down, x -> right, z -> down; + = spawn)");
    if (model.terrain || model.places.length) {
      const { mapLines, legend, legendOmitted } = drawAsciiMap(model);
      lines.push(...mapLines);
      if (legend) lines.push(legend);
      if (legendOmitted > 0) lines.push(`  ... +${legendOmitted} more place(s) not shown in legend`);
    } else {
      lines.push("  (nothing to map yet)");
    }
  }

  lines.push("");
  lines.push(`PROBLEMS (${model.diagnostics.length}, worst first)`);
  if (!model.diagnostics.length) {
    lines.push("  (none)");
  } else {
    const sorted = model.diagnostics.slice().sort(
      (a, b) => (SEVERITY_ORDER[a.severity] ?? 3) - (SEVERITY_ORDER[b.severity] ?? 3)
    );
    const { shown: diagsShown, omitted: diagsOmitted } = capList(sorted, LIST_CAP);
    for (const d of diagsShown) {
      const label = SEVERITY_LABEL[d.severity] || String(d.severity || "?").toUpperCase();
      const at = d.at ? ` at ${fmtCoord(d.at)}` : "";
      lines.push(`  [${label}] ${d.kind}${d.subject ? ` (${d.subject})` : ""}: ${truncateText(d.message)}${at}`);
    }
    if (diagsOmitted > 0) lines.push(`  ... +${diagsOmitted} more problem(s) omitted (worst-first order - fix these first, then call world_overview again)`);
  }

  return lines.join("\n");
}
