package dev.celestia.estari.senses;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

    private TerrainSampler() {}

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
