package dev.celestia.mcalive2.ledger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates a blueprint: a JSON array of relative block placements
 * {@code {dx,dy,dz,material,[data]}}, the unit of AI construction (see DESIGN.md
 * "build_blueprint"). Kept Bukkit-free so it is unit-testable without a server.
 */
public final class BlueprintParser {

    /** One block placement relative to a build origin. */
    public record BlockEntry(int dx, int dy, int dz, String material, String data) {}

    private BlueprintParser() {}

    /**
     * @param blueprint the JSON array of block entries
     * @param maxBlocks  hard cap on entry count (mirrors config max-fill-volume)
     * @param maxOffset  hard cap on |dx|,|dy|,|dz| — keeps a single blueprint from
     *                   spanning an absurd bounding box even with few entries
     */
    public static List<BlockEntry> parse(JsonArray blueprint, int maxBlocks, int maxOffset) {
        if (blueprint == null) throw new IllegalArgumentException("blueprint must be a JSON array");
        if (blueprint.size() == 0) throw new IllegalArgumentException("blueprint has no blocks");
        if (blueprint.size() > maxBlocks) {
            throw new IllegalArgumentException(
                    "blueprint too large: " + blueprint.size() + " blocks (cap " + maxBlocks + ")");
        }
        List<BlockEntry> out = new ArrayList<>(blueprint.size());
        int i = 0;
        for (JsonElement el : blueprint) {
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("blueprint entry " + i + " is not an object");
            }
            JsonObject o = el.getAsJsonObject();
            int dx = reqInt(o, "dx", i);
            int dy = reqInt(o, "dy", i);
            int dz = reqInt(o, "dz", i);
            if (Math.abs(dx) > maxOffset || Math.abs(dy) > maxOffset || Math.abs(dz) > maxOffset) {
                throw new IllegalArgumentException("blueprint entry " + i + " offset exceeds cap " + maxOffset);
            }
            String material = reqString(o, "material", i);
            if (material.isBlank()) {
                throw new IllegalArgumentException("blueprint entry " + i + " has a blank material");
            }
            String data = o.has("data") && !o.get("data").isJsonNull() ? o.get("data").getAsString() : null;
            out.add(new BlockEntry(dx, dy, dz, material, data));
            i++;
        }
        // reject degenerate bounding boxes only when there's more than one distinct point
        // to bound; a single-block blueprint is always fine.
        return out;
    }

    private static int reqInt(JsonObject o, String key, int index) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            throw new IllegalArgumentException("blueprint entry " + index + " missing \"" + key + "\"");
        }
        double v = e.getAsDouble();
        if (v != Math.floor(v)) {
            throw new IllegalArgumentException("blueprint entry " + index + " \"" + key + "\" must be an integer");
        }
        return (int) v;
    }

    private static String reqString(JsonObject o, String key, int index) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            throw new IllegalArgumentException("blueprint entry " + index + " missing \"" + key + "\"");
        }
        return e.getAsString();
    }
}
