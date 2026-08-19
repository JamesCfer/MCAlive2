package dev.celestia.mcalive2.senses;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.util.Standing;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Grid-based terrain summaries: the director's eyes for both {@code player_explored}
 * events and the {@code sample_terrain} actuator.
 */
public final class TerrainSampler {

    /** How far above/below a yHint {@link #surfaceY} will scan for the surface (see {@link Standing}). */
    private static final int SURFACE_SCAN_RADIUS = 32;
    /** Hard cap on columns a single {@link #scanArea} call may cover. */
    public static final int SCAN_AREA_MAX_COLUMNS = 4096;
    /** Above this column count, {@link #scanArea} returns a downsampled grid instead of every column. */
    private static final int SCAN_AREA_INLINE_COLUMNS = 256;
    private static final int SCAN_AREA_GRID = 8;

    private TerrainSampler() {}

    /**
     * The surface y of a column: with no hint, the plain highest-block lookup (fine for
     * open ground). With a yHint, scans +/- {@link #SURFACE_SCAN_RADIUS} around it for the
     * nearest solid-ground-with-headroom - the same search {@link Standing} does for NPCs -
     * so a floating island's top is found instead of the crater floor underneath it.
     */
    public static int surfaceY(World world, int x, int z, Integer yHint) {
        if (yHint == null) {
            return world.getHighestBlockYAt(x, z);
        }
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int requestedFeetY = Math.max(minY, Math.min(maxY, yHint));
        try {
            int feetY = Standing.resolve(requestedFeetY, minY, maxY, SURFACE_SCAN_RADIUS,
                    y -> y >= minY && y <= maxY && world.getBlockAt(x, y, z).isPassable());
            return feetY - 1;
        } catch (IllegalStateException noStandingSpot) {
            // Nothing standable near the hint in THIS column - e.g. open sky beside a
            // floating island, or a sheer drop. That is a normal feature of terrain, not
            // an error: fall back to the plain highest-block surface so one such column
            // can never abort an entire scan of thousands of columns.
            return world.getHighestBlockYAt(x, z);
        }
    }

    /**
     * Summarizes a rectangular column region for build resolution: surface height and
     * material per column, plus min/max/median and a flatness flag. Areas of up to {@link
     * #SCAN_AREA_INLINE_COLUMNS} columns get every column back; larger areas (up to {@link
     * #SCAN_AREA_MAX_COLUMNS}) get summary stats plus an 8x8 downsampled grid instead.
     */
    public static JsonObject scanArea(World world, int minX, int minZ, int maxX, int maxZ, Integer yHint) {
        long columns = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        if (columns > SCAN_AREA_MAX_COLUMNS) {
            throw new IllegalArgumentException("scan_area too large: " + columns + " columns (cap " + SCAN_AREA_MAX_COLUMNS + ")");
        }
        int n = (int) columns;
        int[] ys = new int[n];
        int idx = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        JsonArray columnsArr = n <= SCAN_AREA_INLINE_COLUMNS ? new JsonArray() : null;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int y = surfaceY(world, x, z, yHint);
                Material mat = world.getBlockAt(x, y, z).getType();
                ys[idx++] = y;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (columnsArr != null) columnsArr.add(columnJson(x, z, y, mat));
            }
        }

        int[] sorted = ys.clone();
        Arrays.sort(sorted);
        double medianY = sorted.length % 2 == 0
                ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0
                : sorted[sorted.length / 2];

        JsonObject result = new JsonObject();
        result.addProperty("minY", minY);
        result.addProperty("maxY", maxY);
        result.addProperty("medianY", medianY);
        result.addProperty("flat", (maxY - minY) <= 2);
        if (columnsArr != null) {
            result.add("columns", columnsArr);
        } else {
            JsonArray grid = new JsonArray();
            for (int gx = 0; gx < SCAN_AREA_GRID; gx++) {
                int x = minX + (maxX == minX ? 0 : (maxX - minX) * gx / (SCAN_AREA_GRID - 1));
                for (int gz = 0; gz < SCAN_AREA_GRID; gz++) {
                    int z = minZ + (maxZ == minZ ? 0 : (maxZ - minZ) * gz / (SCAN_AREA_GRID - 1));
                    int y = surfaceY(world, x, z, yHint);
                    Material mat = world.getBlockAt(x, y, z).getType();
                    grid.add(columnJson(x, z, y, mat));
                }
            }
            result.add("grid", grid);
        }
        return result;
    }

    private static JsonObject columnJson(int x, int z, int y, Material material) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("y", y);
        o.addProperty("material", material.getKey().getKey());
        return o;
    }

    /** Summarizes a rectangular column region with a 4x4 = 16-point heightmap grid. */
    public static JsonObject sampleRect(World world, int minX, int minZ, int maxX, int maxZ) {
        int[] heights = new int[16];
        boolean water = false, lava = false;
        Map<String, Integer> biomeCounts = new HashMap<>();

        int idx = 0;
        for (int gx = 0; gx < 4; gx++) {
            for (int gz = 0; gz < 4; gz++) {
                int x = minX + (maxX == minX ? 0 : (maxX - minX) * gx / 3);
                int z = minZ + (maxZ == minZ ? 0 : (maxZ - minZ) * gz / 3);
                Block top = world.getHighestBlockAt(x, z);
                int y = top.getY();
                heights[idx++] = y;
                Material mat = top.getType();
                if (mat == Material.WATER) water = true;
                if (mat == Material.LAVA) lava = true;
                Biome biome = world.getBiome(x, y, z);
                biomeCounts.merge(biome.getKey().getKey(), 1, Integer::sum);
            }
        }

        int[] sorted = heights.clone();
        Arrays.sort(sorted);
        int min = sorted[0];
        int max = sorted[sorted.length - 1];
        double median = (sorted[7] + sorted[8]) / 2.0;

        String dominantBiome = biomeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");

        JsonObject heightmap = new JsonObject();
        heightmap.addProperty("min", min);
        heightmap.addProperty("max", max);
        heightmap.addProperty("median", median);
        JsonArray grid = new JsonArray();
        for (int h : heights) grid.add(h);
        heightmap.add("grid", grid);

        JsonObject features = new JsonObject();
        features.addProperty("water", water);
        features.addProperty("lava", lava);

        JsonObject result = new JsonObject();
        result.add("heightmap", heightmap);
        result.addProperty("biome", dominantBiome);
        result.add("features", features);
        return result;
    }
}
