package dev.celestia.estari.senses;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.celestia.estari.EstariPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which 8x8-chunk (configurable) cells each player has already explored, so
 * {@code player_explored} fires exactly once per cell. Visited cells persist across
 * restarts in the plugin data folder.
 */
public class ExploredTracker {

    private final EstariPlugin plugin;
    private final int cellChunks;
    /** world name -> set of packed (cellX,cellZ) keys already explored by anyone */
    private final Map<String, Set<Long>> visited = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public ExploredTracker(EstariPlugin plugin) {
        this.plugin = plugin;
        this.cellChunks = Math.max(1, plugin.getConfig().getInt("explored-cell-chunks", 8));
        load();
    }

    private static long pack(int cellX, int cellZ) {
        return (((long) cellX) << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    /**
     * Call when a player has moved. Returns a fresh {@code player_explored} payload the
     * first time anyone enters this cell, or {@code null} if the cell was already explored.
     */
    public JsonObject checkExplored(Player player) {
        Location loc = player.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        int cellX = Math.floorDiv(chunkX, cellChunks);
        int cellZ = Math.floorDiv(chunkZ, cellChunks);
        String world = loc.getWorld().getName();

        Set<Long> set = visited.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet());
        if (!set.add(pack(cellX, cellZ))) return null;
        dirty = true;

        int minX = cellX * cellChunks * 16;
        int minZ = cellZ * cellChunks * 16;
        int maxX = minX + cellChunks * 16 - 1;
        int maxZ = minZ + cellChunks * 16 - 1;

        JsonObject data = TerrainSampler.sampleRect(loc.getWorld(), minX, minZ, maxX, maxZ);
        data.addProperty("player", player.getName());
        data.addProperty("world", world);
        JsonObject cell = new JsonObject();
        cell.addProperty("cellX", cellX);
        cell.addProperty("cellZ", cellZ);
        cell.addProperty("chunkSpan", cellChunks);
        data.add("cell", cell);
        return data;
    }

    // ---- persistence ----

    private File file() {
        return new File(plugin.getDataFolder(), "explored.json");
    }

    public void saveIfDirty() {
        if (!dirty) return;
        save();
    }

    public void save() {
        try {
            plugin.getDataFolder().mkdirs();
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Set<Long>> e : visited.entrySet()) {
                JsonArray arr = new JsonArray();
                for (long key : e.getValue()) arr.add(key);
                root.add(e.getKey(), arr);
            }
            Files.write(file().toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save explored.json: " + e.getMessage());
        }
    }

    public void load() {
        File f = file();
        if (!f.exists()) return;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(s).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                Set<Long> set = ConcurrentHashMap.newKeySet();
                for (JsonElement el : e.getValue().getAsJsonArray()) set.add(el.getAsLong());
                visited.put(e.getKey(), set);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load explored.json: " + e.getMessage());
        }
    }
}
