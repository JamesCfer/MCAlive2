// MCAlive2 system gadget: world-scan
// Surveys the ACTUAL loaded world - every currently-loaded chunk - into a coarse
// heightmap, so the brain's world model and 3D map show the real terrain (spawn,
// wilderness, whatever players have explored) instead of only the bounding box of
// recorded places.
//
// The plugin's scan_area is footprint-sized and capped; this walks the loaded-chunk
// set, auto-choosing a sample step so the result stays under maxCells no matter how
// much of the world is loaded.
//
// args (heightmap mode, the default): {
//   world?: string          - defaults to the first world
//   region?: {x1,z1,x2,z2}  - explicit block bounds; default = bounds of loaded chunks
//   step?: number           - blocks between samples (default: auto-fit to maxCells)
//   maxCells?: number       - cap on cols*rows (default 4096)
// }
// returns: {
//   ok, world, step, origin:{x,z}, cols, rows,
//   heights: [[int|null,...],...]   // null = that column's chunk isn't loaded
//   surface: [[string|null,...]]|null  // surface material names, omitted if large
//   loadedChunks, bounds:{x1,z1,x2,z2},
//   spawn:{x,y,z}, players:[{name,x,y,z}]
// }
//
// args (VOXEL mode - {voxels:true}): returns the TRUE cubic-voxel shell of the
// loaded world for the human /map viewer (console-server.mjs) - every solid
// block within Chebyshev distance 1 of at least one air block, so building
// interiors, overhangs, and near-surface caves render as real cubes instead
// of a top-surface heightmap. NEVER fed to the director (token cost).
// {
//   voxels: true,
//   world?: string,
//   area?: {x1,z1,x2,z2}    - block bounds; only loaded chunks inside are scanned
//   maxBlocks?: number      - stop after ~this many emitted voxels (default 20000)
//   maxChunks?: number      - hard per-call chunk budget (default 12) so one call
//                             never stalls the main thread; page with `cursor`
//   cursor?: number         - index into the deterministic (x,z)-sorted loaded-
//                             chunk list to resume from (from a prior nextCursor)
// }
// returns: {
//   ok, mode:"voxels", world, palette:[materialName,...],
//   chunks:[{cx,cz,runs:[[lx,lz,yStart,runLen,paletteIdx],...]}],  // lx/lz 0..15,
//     vertical run-length encoding per column - compact, never one object/block
//   cursor, nextCursor:int|null,  // null = scan complete; else pass back as cursor
//   blocks, chunkTotal, loadedChunks
// }
package dev.celestia.mcalive2.gadget.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.gadget.GadgetContract;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class WorldScan implements GadgetContract {

    private static final int SURFACE_DETAIL_MAX_CELLS = 2500;
    // Voxel mode: shell thickness (Chebyshev distance from air) and the cap on
    // how tall a single chunk's scanned band may be (bounds per-chunk work).
    // A block can only ever show a face if it TOUCHES air. Anything deeper is enclosed
    // by solid neighbours on all six sides, so every one of its faces is culled by the
    // renderer and it is transferred, parsed and processed to draw precisely nothing.
    // Depth 1 still carries cave walls, building interiors and the undersides of
    // overhangs - those touch air too - while cutting the payload by about two thirds.
    private static final int SHELL = 1;
    private static final int MAX_BAND_HEIGHT = 128;
    // How far below a chunk's lowest surface the scan band starts - deep enough
    // to catch near-surface caves and basements without walking to bedrock.
    private static final int BAND_BELOW_SURFACE = 16;

    @Override
    public JsonObject run(JsonObject args, GadgetContext ctx) {
        String worldName = str(args, "world", null);
        World w = ctx.world(worldName);
        if (w == null) throw new IllegalArgumentException("no such world: " + worldName);

        if (bool(args, "voxels", false)) return voxelScan(args, w);

        int maxCells = (int) num(args, "maxCells", 4096, 64, 20000);

        // ---- bounds: explicit region, else the extent of all loaded chunks ----
        int x1, z1, x2, z2;
        Chunk[] loaded = w.getLoadedChunks();
        JsonObject region = args != null && args.has("region") && args.get("region").isJsonObject()
                ? args.getAsJsonObject("region") : null;
        if (region != null) {
            x1 = (int) num(region, "x1", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
            z1 = (int) num(region, "z1", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
            x2 = (int) num(region, "x2", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
            z2 = (int) num(region, "z2", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        } else {
            if (loaded.length == 0) {
                Location s = w.getSpawnLocation();
                x1 = s.getBlockX() - 64; x2 = s.getBlockX() + 64;
                z1 = s.getBlockZ() - 64; z2 = s.getBlockZ() + 64;
            } else {
                int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
                int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
                for (Chunk c : loaded) {
                    if (c.getX() < minCx) minCx = c.getX();
                    if (c.getX() > maxCx) maxCx = c.getX();
                    if (c.getZ() < minCz) minCz = c.getZ();
                    if (c.getZ() > maxCz) maxCz = c.getZ();
                }
                x1 = minCx << 4; x2 = (maxCx << 4) + 15;
                z1 = minCz << 4; z2 = (maxCz << 4) + 15;
            }
        }
        if (x2 < x1) { int t = x1; x1 = x2; x2 = t; }
        if (z2 < z1) { int t = z1; z1 = z2; z2 = t; }

        // ---- step: honour an explicit step, else grow it until the grid fits ----
        int spanX = x2 - x1 + 1, spanZ = z2 - z1 + 1;
        int step = (int) num(args, "step", 0, 1, 512);
        if (step <= 0) {
            step = 4;
            while (((spanX / step) + 1L) * ((spanZ / step) + 1L) > maxCells && step < 512) step *= 2;
        }

        int cols = spanX / step + 1;
        int rows = spanZ / step + 1;
        boolean withSurface = (long) cols * rows <= SURFACE_DETAIL_MAX_CELLS;

        JsonArray heights = new JsonArray();
        JsonArray surface = new JsonArray();
        int sampled = 0;
        for (int ix = 0; ix < cols; ix++) {
            int bx = x1 + ix * step;
            JsonArray hrow = new JsonArray();
            JsonArray srow = new JsonArray();
            for (int iz = 0; iz < rows; iz++) {
                int bz = z1 + iz * step;
                // Never force-load: an unloaded column is reported as null so the
                // map shows genuinely-known terrain only.
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) {
                    hrow.add(JsonNull.INSTANCE);
                    if (withSurface) srow.add(JsonNull.INSTANCE);
                    continue;
                }
                int y = w.getHighestBlockYAt(bx, bz);
                hrow.add(y);
                sampled++;
                if (withSurface) {
                    srow.add(w.getBlockAt(bx, y, bz).getType().getKey().getKey());
                }
            }
            heights.add(hrow);
            if (withSurface) surface.add(srow);
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("world", w.getName());
        out.addProperty("step", step);
        JsonObject origin = new JsonObject();
        origin.addProperty("x", x1);
        origin.addProperty("z", z1);
        out.add("origin", origin);
        out.addProperty("cols", cols);
        out.addProperty("rows", rows);
        out.addProperty("sampledColumns", sampled);
        out.add("heights", heights);
        out.add("surface", withSurface ? surface : JsonNull.INSTANCE);
        out.addProperty("loadedChunks", loaded.length);

        JsonObject bounds = new JsonObject();
        bounds.addProperty("x1", x1); bounds.addProperty("z1", z1);
        bounds.addProperty("x2", x2); bounds.addProperty("z2", z2);
        out.add("bounds", bounds);

        Location sp = w.getSpawnLocation();
        JsonObject spawn = new JsonObject();
        spawn.addProperty("x", sp.getBlockX());
        spawn.addProperty("y", sp.getBlockY());
        spawn.addProperty("z", sp.getBlockZ());
        out.add("spawn", spawn);

        JsonArray players = new JsonArray();
        for (Player p : w.getPlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName());
            o.addProperty("x", Math.round(p.getLocation().getX() * 10.0) / 10.0);
            o.addProperty("y", Math.round(p.getLocation().getY() * 10.0) / 10.0);
            o.addProperty("z", Math.round(p.getLocation().getZ() * 10.0) / 10.0);
            players.add(o);
        }
        out.add("players", players);
        return out;
    }

    // ------------------------------------------------------------------
    // Voxel mode: per loaded chunk, find the band of interest (lowest
    // surface - BAND_BELOW_SURFACE up to highest surface), build an air
    // mask over the band (padded 3 blocks into the 4 edge-neighbor chunks
    // when they are loaded, so cliff faces on chunk seams don't get holes),
    // dilate it by SHELL along each axis (three separable passes = exact
    // Chebyshev-distance-SHELL ball), then emit every solid block the
    // dilated mask covers, vertical-run-length-encoded per column against a
    // shared material palette. Budgeted by maxChunks + maxBlocks with a
    // cursor so one call never stalls the main thread; callers page.
    // ------------------------------------------------------------------
    /** True when a living NPC or a player is standing in this chunk. NPC bodies carry an
     *  "npc_id" tag in their persistent data, which identifies them without needing a
     *  handle on the plugin. */
    private static boolean occupied(Chunk c) {
        for (org.bukkit.entity.Entity e : c.getEntities()) {
            if (e instanceof org.bukkit.entity.Player) return true;
            for (org.bukkit.NamespacedKey k : e.getPersistentDataContainer().getKeys()) {
                if ("npc_id".equals(k.getKey())) return true;
            }
        }
        return false;
    }

    private JsonObject voxelScan(JsonObject args, World w) {
        int maxBlocks = (int) num(args, "maxBlocks", 20000, 1000, 60000);
        int maxChunks = (int) num(args, "maxChunks", 12, 1, 64);
        int cursor = (int) num(args, "cursor", 0, 0, Integer.MAX_VALUE);

        // Optional block-coordinate area -> inclusive chunk-coordinate bounds.
        boolean hasArea = false;
        int acx1 = 0, acz1 = 0, acx2 = 0, acz2 = 0;
        if (args != null && args.has("area") && args.get("area").isJsonObject()) {
            JsonObject a = args.getAsJsonObject("area");
            int ax1 = (int) num(a, "x1", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int az1 = (int) num(a, "z1", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int ax2 = (int) num(a, "x2", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int az2 = (int) num(a, "z2", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
            acx1 = Math.min(ax1, ax2) >> 4; acx2 = Math.max(ax1, ax2) >> 4;
            acz1 = Math.min(az1, az2) >> 4; acz2 = Math.max(az1, az2) >> 4;
            hasArea = true;
        }

        // Only ground somebody is standing on is part of the world as far as the map is
        // concerned. Chunks load transiently all the time - a forager walking, the
        // pathfinder reading blocks ahead, a memorial being looked up - and each one used
        // to be surveyed and drawn as a stray island of terrain nobody lives on.
        Chunk[] loaded = w.getLoadedChunks();
        // The map covers the 3x3 of chunks around anybody standing in the world: the
        // ground they are on plus one chunk of elbow room, so a settlement reads as a
        // place rather than a single square, without dragging in the whole landscape
        // that merely happened to be loaded as somebody walked through it.
        java.util.HashSet<Long> lived = new java.util.HashSet<>();
        for (Chunk c : loaded) {
            if (occupied(c)) lived.add((((long) c.getX()) << 32) ^ (c.getZ() & 0xFFFFFFFFL));
        }
        java.util.ArrayList<Chunk> chunks = new java.util.ArrayList<>();
        for (Chunk c : loaded) {
            if (hasArea && (c.getX() < acx1 || c.getX() > acx2 || c.getZ() < acz1 || c.getZ() > acz2)) continue;
            boolean near = false;
            for (int dx = -1; dx <= 1 && !near; dx++) {
                for (int dz = -1; dz <= 1 && !near; dz++) {
                    if (lived.contains(((((long) (c.getX() + dx)) << 32) ^ ((c.getZ() + dz) & 0xFFFFFFFFL)))) near = true;
                }
            }
            if (!near) continue;
            chunks.add(c);
        }
        // Deterministic order so cursor paging is stable across calls.
        chunks.sort((a, b) -> a.getX() != b.getX() ? Integer.compare(a.getX(), b.getX()) : Integer.compare(a.getZ(), b.getZ()));

        int wMin = w.getMinHeight(), wMax = w.getMaxHeight();
        java.util.ArrayList<String> palette = new java.util.ArrayList<>();
        java.util.HashMap<String, Integer> paletteIdx = new java.util.HashMap<>();
        JsonArray chunkArr = new JsonArray();
        int blocks = 0, done = 0;
        Integer nextCursor = null;

        for (int ci = cursor; ci < chunks.size(); ci++) {
            if (done >= maxChunks || blocks >= maxBlocks) { nextCursor = ci; break; }
            Chunk c = chunks.get(ci);
            int cx = c.getX(), cz = c.getZ();
            ChunkSnapshot snap = c.getChunkSnapshot();

            int minSurf = Integer.MAX_VALUE, maxSurf = Integer.MIN_VALUE;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int hy = snap.getHighestBlockYAt(lx, lz);
                    if (hy < minSurf) minSurf = hy;
                    if (hy > maxSurf) maxSurf = hy;
                }
            }
            done++;
            if (maxSurf < wMin) continue; // entirely-empty chunk: nothing to emit

            int yLo = Math.max(wMin, minSurf - BAND_BELOW_SURFACE);
            int yHiSolid = Math.min(wMax - 1, maxSurf);
            // Air band reaches one above the highest solid so every top surface
            // sits within SHELL of sky air even on a perfectly flat chunk.
            int yHiAir = Math.min(wMax - 1, maxSurf + 1);
            if (yHiAir - yLo + 1 > MAX_BAND_HEIGHT) yLo = yHiAir - MAX_BAND_HEIGHT + 1;
            int H = yHiAir - yLo + 1;

            // Edge-neighbor snapshots (loaded only) for the seam pad.
            ChunkSnapshot west = w.isChunkLoaded(cx - 1, cz) ? w.getChunkAt(cx - 1, cz).getChunkSnapshot() : null;
            ChunkSnapshot east = w.isChunkLoaded(cx + 1, cz) ? w.getChunkAt(cx + 1, cz).getChunkSnapshot() : null;
            ChunkSnapshot north = w.isChunkLoaded(cx, cz - 1) ? w.getChunkAt(cx, cz - 1).getChunkSnapshot() : null;
            ChunkSnapshot south = w.isChunkLoaded(cx, cz + 1) ? w.getChunkAt(cx, cz + 1).getChunkSnapshot() : null;

            int W = 16 + 2 * SHELL; // padded footprint
            boolean[] air = new boolean[W * H * W]; // idx = (px*H + y)*W + pz
            for (int px = 0; px < W; px++) {
                int gx = px - SHELL;
                for (int pz = 0; pz < W; pz++) {
                    int gz = pz - SHELL;
                    ChunkSnapshot sel = null;
                    int sx = gx, sz = gz;
                    if (gx >= 0 && gx < 16 && gz >= 0 && gz < 16) { sel = snap; }
                    else if (gx < 0 && gz >= 0 && gz < 16) { sel = west; sx = gx + 16; }
                    else if (gx > 15 && gz >= 0 && gz < 16) { sel = east; sx = gx - 16; }
                    else if (gz < 0 && gx >= 0 && gx < 16) { sel = north; sz = gz + 16; }
                    else if (gz > 15 && gx >= 0 && gx < 16) { sel = south; sz = gz - 16; }
                    if (sel == null) continue; // unloaded neighbor / diagonal corner: no air known
                    int base = (px * H) * W + pz;
                    for (int y = 0; y < H; y++) {
                        if (airLike(sel.getBlockType(sx, yLo + y, sz))) air[base + y * W] = true;
                    }
                }
            }

            // Separable Chebyshev-SHELL dilation: x pass air->tmp, z pass
            // tmp->air, y pass air->tmp; tmp is the final mask.
            boolean[] tmp = new boolean[air.length];
            for (int px = 0; px < W; px++) {
                for (int y = 0; y < H; y++) {
                    for (int pz = 0; pz < W; pz++) {
                        boolean v = false;
                        for (int d = -SHELL; d <= SHELL && !v; d++) {
                            int q = px + d;
                            if (q >= 0 && q < W && air[(q * H + y) * W + pz]) v = true;
                        }
                        tmp[(px * H + y) * W + pz] = v;
                    }
                }
            }
            for (int px = 0; px < W; px++) {
                for (int y = 0; y < H; y++) {
                    for (int pz = 0; pz < W; pz++) {
                        boolean v = false;
                        for (int d = -SHELL; d <= SHELL && !v; d++) {
                            int q = pz + d;
                            if (q >= 0 && q < W && tmp[(px * H + y) * W + q]) v = true;
                        }
                        air[(px * H + y) * W + pz] = v;
                    }
                }
            }
            for (int px = 0; px < W; px++) {
                for (int y = 0; y < H; y++) {
                    for (int pz = 0; pz < W; pz++) {
                        boolean v = false;
                        for (int d = -SHELL; d <= SHELL && !v; d++) {
                            int q = y + d;
                            if (q >= 0 && q < H && air[(px * H + q) * W + pz]) v = true;
                        }
                        tmp[(px * H + y) * W + pz] = v;
                    }
                }
            }

            // Emit the center 16x16: solid blocks inside the dilated-air mask,
            // vertical-run-length encoded per column as [lx,lz,yStart,len,pi].
            JsonArray runs = new JsonArray();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int px = lx + SHELL, pz = lz + SHELL;
                    int runStart = 0, runLen = 0, runPi = -1;
                    for (int y = yLo; y <= yHiSolid; y++) {
                        int pi = -1;
                        if (tmp[(px * H + (y - yLo)) * W + pz]) {
                            Material m = snap.getBlockType(lx, y, lz);
                            if (!airLike(m)) {
                                String key = m.getKey().getKey();
                                Integer id = paletteIdx.get(key);
                                if (id == null) { id = palette.size(); palette.add(key); paletteIdx.put(key, id); }
                                pi = id;
                            }
                        }
                        if (pi == runPi && pi >= 0) { runLen++; continue; }
                        if (runPi >= 0) { runs.add(runJson(lx, lz, runStart, runLen, runPi)); blocks += runLen; }
                        runPi = pi; runStart = y; runLen = pi >= 0 ? 1 : 0;
                    }
                    if (runPi >= 0) { runs.add(runJson(lx, lz, runStart, runLen, runPi)); blocks += runLen; }
                }
            }
            JsonObject cj = new JsonObject();
            cj.addProperty("cx", cx);
            cj.addProperty("cz", cz);
            cj.add("runs", runs);
            chunkArr.add(cj);
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("mode", "voxels");
        out.addProperty("world", w.getName());
        JsonArray pal = new JsonArray();
        for (String p : palette) pal.add(p);
        out.add("palette", pal);
        out.add("chunks", chunkArr);
        out.addProperty("cursor", cursor);
        if (nextCursor != null) out.addProperty("nextCursor", nextCursor); else out.add("nextCursor", JsonNull.INSTANCE);
        out.addProperty("blocks", blocks);
        out.addProperty("chunkTotal", chunks.size());
        out.addProperty("loadedChunks", loaded.length);
        return out;
    }

    /** Voxel-mode "air": true air plus non-solid decorations (short grass,
     *  flowers, leaf litter, vines, torches...) which would otherwise render
     *  as opaque cubes speckled over the real surface. Water and lava stay
     *  visible - they are the only non-solid blocks worth drawing. */
    private static boolean airLike(Material m) {
        if (m.isAir()) return true;
        if (m == Material.WATER || m == Material.LAVA) return false;
        return !m.isSolid();
    }

    private static JsonArray runJson(int lx, int lz, int yStart, int len, int pi) {
        JsonArray run = new JsonArray();
        run.add(lx); run.add(lz); run.add(yStart); run.add(len); run.add(pi);
        return run;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        if (o == null || !o.has(k) || !o.get(k).isJsonPrimitive()) return def;
        try { return o.get(k).getAsBoolean(); } catch (Exception e) { return def; }
    }

    private static String str(JsonObject o, String k, String def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static double num(JsonObject o, String k, double def, double min, double max) {
        if (o == null || !o.has(k) || !o.get(k).isJsonPrimitive()) return def;
        try { return Math.max(min, Math.min(max, o.get(k).getAsDouble())); } catch (Exception e) { return def; }
    }
}
