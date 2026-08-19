package dev.celestia.mcalive2.npc.behavior;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.celestia.mcalive2.ledger.BlueprintParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named, persistent blueprints for {@link BehaviorEngine}'s build step: id -> an origin
 * plus the same relative {dx,dy,dz,material[,data]} block list {@link BlueprintParser}
 * already validates for build_blueprint. Registered once via blueprint_register, then
 * built log-by-log by NPC crews any number of times. Plain Gson + java.util - no Bukkit
 * dependency - persisted to blueprints.json in the style of
 * {@link dev.celestia.mcalive2.formula.FormulaStore}.
 */
public class BlueprintRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One registered blueprint: where it stands in the world and what it is made of. */
    public record Blueprint(String id, int ox, int oy, int oz, List<BlueprintParser.BlockEntry> blocks) {}

    private final Map<String, Blueprint> blueprints = new LinkedHashMap<>();

    /**
     * Validate and store a blueprint, overwriting any existing record with the same id.
     *
     * @param spec      {id, origin{x,y,z}, blocks:[{dx,dy,dz,material[,data]}]}
     * @param maxBlocks hard cap on block count (mirrors config max-fill-volume)
     * @param maxOffset hard cap on |dx|,|dy|,|dz| per entry
     */
    public synchronized Blueprint register(JsonObject spec, int maxBlocks, int maxOffset) {
        String id = reqString(spec, "id");
        JsonElement originEl = spec.get("origin");
        if (originEl == null || !originEl.isJsonObject()) {
            throw new IllegalArgumentException("blueprint needs an origin {x,y,z}");
        }
        JsonObject origin = originEl.getAsJsonObject();
        int ox = reqInt(origin, "x");
        int oy = reqInt(origin, "y");
        int oz = reqInt(origin, "z");
        JsonArray blocks = spec.has("blocks") && spec.get("blocks").isJsonArray()
                ? spec.getAsJsonArray("blocks") : null;
        List<BlueprintParser.BlockEntry> entries = BlueprintParser.parse(blocks, maxBlocks, maxOffset);
        Blueprint bp = new Blueprint(id, ox, oy, oz, entries);
        blueprints.put(id, bp);
        return bp;
    }

    public synchronized Blueprint get(String id) {
        return blueprints.get(id);
    }

    public synchronized boolean delete(String id) {
        return blueprints.remove(id) != null;
    }

    public synchronized List<Blueprint> all() {
        return new ArrayList<>(blueprints.values());
    }

    // ---- JSON ----

    public static JsonObject toJson(Blueprint bp) {
        JsonObject o = new JsonObject();
        o.addProperty("id", bp.id());
        JsonObject origin = new JsonObject();
        origin.addProperty("x", bp.ox());
        origin.addProperty("y", bp.oy());
        origin.addProperty("z", bp.oz());
        o.add("origin", origin);
        JsonArray blocks = new JsonArray();
        for (BlueprintParser.BlockEntry e : bp.blocks()) {
            JsonObject b = new JsonObject();
            b.addProperty("dx", e.dx());
            b.addProperty("dy", e.dy());
            b.addProperty("dz", e.dz());
            b.addProperty("material", e.material());
            if (e.data() != null) b.addProperty("data", e.data());
            blocks.add(b);
        }
        o.add("blocks", blocks);
        return o;
    }

    // ---- persistence: single blueprints.json file ----

    public synchronized void save(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create directory: " + parent);
        }
        JsonArray arr = new JsonArray();
        for (Blueprint bp : blueprints.values()) arr.add(toJson(bp));
        Files.write(file.toPath(), GSON.toJson(arr).getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void load(File file, int maxBlocks, int maxOffset) throws IOException {
        if (!file.exists()) return;
        String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (s.isBlank()) return;
        JsonArray arr = JsonParser.parseString(s).getAsJsonArray();
        blueprints.clear();
        for (JsonElement e : arr) {
            register(e.getAsJsonObject(), maxBlocks, maxOffset);
        }
    }

    private static String reqString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) throw new IllegalArgumentException("missing required field: " + key);
        return e.getAsString();
    }

    private static int reqInt(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            throw new IllegalArgumentException("origin missing \"" + key + "\"");
        }
        return e.getAsInt();
    }
}
