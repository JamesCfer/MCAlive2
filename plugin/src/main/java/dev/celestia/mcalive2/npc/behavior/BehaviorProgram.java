package dev.celestia.mcalive2.npc.behavior;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One director-authored "behavior program": a crew of NPCs plus an ordered list of steps
 * the plugin runs indefinitely with zero AI involvement (see BehaviorEngine). The
 * director authors the goal ONCE; the world runs it, waking the director only via
 * behavior_done / behavior_blocked events.
 *
 * <p>Pure Gson + java.util - no Bukkit dependency - so parsing/validation and the JSON
 * roundtrip (including per-NPC cursors and their carrying maps) are unit-testable
 * without a server, in the style of {@link dev.celestia.mcalive2.npc.NpcData}. Step
 * specs are kept as validated raw {@link JsonObject}s; every positional field is plain
 * {x,y,z} JSON that the engine resolves against a live world at run time.
 */
public class BehaviorProgram {

    /** The exactly-eight step types a program may contain; anything else is rejected on parse. */
    public static final Set<String> STEP_TYPES = Set.of(
            "gather", "build", "deposit", "withdraw", "work_station", "goto", "wait", "follow");

    /** One crew member's position in the program: which step it is on, step-local
     *  progress scratch (claimed block, break/work tick counters, ...), and the virtual
     *  inventory it carries between steps. Persisted so a restart resumes mid-program. */
    public static class Cursor {
        public int step;
        public JsonObject progress = new JsonObject();
        public Map<String, Integer> carrying = new LinkedHashMap<>();

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("step", step);
            o.add("progress", progress.deepCopy());
            JsonObject c = new JsonObject();
            for (Map.Entry<String, Integer> e : carrying.entrySet()) c.addProperty(e.getKey(), e.getValue());
            o.add("carrying", c);
            return o;
        }

        public static Cursor fromJson(JsonObject o) {
            Cursor c = new Cursor();
            c.step = o.has("step") && !o.get("step").isJsonNull() ? o.get("step").getAsInt() : 0;
            if (o.has("progress") && o.get("progress").isJsonObject()) {
                c.progress = o.getAsJsonObject("progress").deepCopy();
            }
            if (o.has("carrying") && o.get("carrying").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("carrying").entrySet()) {
                    c.carrying.put(e.getKey(), e.getValue().getAsInt());
                }
            }
            return c;
        }
    }

    public String id;
    public String name;
    public List<String> npcIds = new ArrayList<>();
    public boolean loop = false;
    public boolean paused = false;
    /** Validated step specs, each carrying a "type" from {@link #STEP_TYPES}. */
    public List<JsonObject> steps = new ArrayList<>();
    /** npcId -> that crew member's cursor. */
    public Map<String, Cursor> cursors = new LinkedHashMap<>();
    /** Shared per-program build progress: step index -> set of already-placed blueprint
     *  block indices, so crew members never double-place (claims stay in cursor progress). */
    public Map<Integer, Set<Integer>> placed = new LinkedHashMap<>();
    public String createdAt;

    /** The shared placed-set for a build step, creating it on first use. */
    public Set<Integer> placedFor(int stepIndex) {
        return placed.computeIfAbsent(stepIndex, k -> new LinkedHashSet<>());
    }

    /** Initialize one fresh cursor (step 0, empty progress/carrying) per crew member. */
    public void resetCursors() {
        Map<String, Cursor> fresh = new LinkedHashMap<>();
        for (String npcId : npcIds) fresh.put(npcId, new Cursor());
        cursors = fresh;
        placed.clear();
    }

    // ---- validation ----

    /** Validate one step spec: known type, required fields present and well-shaped. */
    public static void validateStep(JsonObject step, int index) {
        String type = optString(step, "type");
        if (type == null || !STEP_TYPES.contains(type)) {
            throw new IllegalArgumentException("step " + index + " has unknown type: " + type);
        }
        switch (type) {
            case "gather" -> {
                reqNonBlank(step, "material", index);
                reqPositiveInt(step, "count", index);
                reqPositiveInt(step, "radius", index);
                reqPos(step, "anchor", index);
            }
            case "build" -> {
                reqNonBlank(step, "blueprintId", index);
                if (step.has("supplyChest") && !step.get("supplyChest").isJsonNull()) reqPos(step, "supplyChest", index);
            }
            case "deposit" -> {
                reqPos(step, "chest", index);
                JsonElement items = step.get("items");
                if (items == null || items.isJsonNull()) {
                    throw new IllegalArgumentException("step " + index + " (deposit) missing \"items\"");
                }
                if (items.isJsonPrimitive()) {
                    if (!"all".equals(items.getAsString())) {
                        throw new IllegalArgumentException("step " + index + " (deposit) items must be \"all\" or a list");
                    }
                } else if (items.isJsonArray()) {
                    validateItems(items.getAsJsonArray(), index);
                } else {
                    throw new IllegalArgumentException("step " + index + " (deposit) items must be \"all\" or a list");
                }
            }
            case "withdraw" -> {
                reqPos(step, "chest", index);
                if (!step.has("items") || !step.get("items").isJsonArray() || step.getAsJsonArray("items").isEmpty()) {
                    throw new IllegalArgumentException("step " + index + " (withdraw) needs a non-empty items list");
                }
                validateItems(step.getAsJsonArray("items"), index);
            }
            case "work_station" -> reqPos(step, "station", index);
            case "goto" -> {
                reqNumber(step, "x", index);
                reqNumber(step, "y", index);
                reqNumber(step, "z", index);
            }
            case "wait" -> reqPositiveInt(step, "ticks", index);
            case "follow" -> {
                reqNonBlank(step, "npcId", index);
                reqPositiveInt(step, "ticks", index);
            }
            default -> throw new IllegalArgumentException("step " + index + " has unknown type: " + type);
        }
    }

    private static void validateItems(JsonArray items, int index) {
        for (JsonElement el : items) {
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("step " + index + " items entries must be {material,count} objects");
            }
            JsonObject o = el.getAsJsonObject();
            reqNonBlank(o, "material", index);
            reqPositiveInt(o, "count", index);
        }
    }

    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static void reqNonBlank(JsonObject o, String key, int index) {
        String s = optString(o, key);
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("step " + index + " missing \"" + key + "\"");
        }
    }

    private static void reqNumber(JsonObject o, String key, int index) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("step " + index + " missing numeric \"" + key + "\"");
        }
    }

    private static void reqPositiveInt(JsonObject o, String key, int index) {
        reqNumber(o, key, index);
        if (o.get(key).getAsInt() <= 0) {
            throw new IllegalArgumentException("step " + index + " \"" + key + "\" must be positive");
        }
    }

    private static void reqPos(JsonObject o, String key, int index) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonObject()) {
            throw new IllegalArgumentException("step " + index + " missing position object \"" + key + "\"");
        }
        JsonObject pos = e.getAsJsonObject();
        reqNumber(pos, "x", index);
        reqNumber(pos, "y", index);
        reqNumber(pos, "z", index);
    }

    // ---- JSON roundtrip ----

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        JsonArray ids = new JsonArray();
        for (String npcId : npcIds) ids.add(npcId);
        o.add("npcIds", ids);
        o.addProperty("loop", loop);
        o.addProperty("paused", paused);
        JsonArray stepArr = new JsonArray();
        for (JsonObject s : steps) stepArr.add(s.deepCopy());
        o.add("steps", stepArr);
        JsonObject cur = new JsonObject();
        for (Map.Entry<String, Cursor> e : cursors.entrySet()) cur.add(e.getKey(), e.getValue().toJson());
        o.add("cursors", cur);
        JsonObject placedObj = new JsonObject();
        for (Map.Entry<Integer, Set<Integer>> e : placed.entrySet()) {
            JsonArray arr = new JsonArray();
            for (Integer i : e.getValue()) arr.add(i);
            placedObj.add(String.valueOf(e.getKey()), arr);
        }
        o.add("placed", placedObj);
        if (createdAt != null) o.addProperty("createdAt", createdAt);
        return o;
    }

    public static BehaviorProgram fromJson(JsonObject o) {
        BehaviorProgram p = new BehaviorProgram();
        p.id = req(o, "id");
        p.name = req(o, "name");
        if (!o.has("npcIds") || !o.get("npcIds").isJsonArray() || o.getAsJsonArray("npcIds").isEmpty()) {
            throw new IllegalArgumentException("behavior program needs a non-empty npcIds crew");
        }
        for (JsonElement e : o.getAsJsonArray("npcIds")) p.npcIds.add(e.getAsString());
        p.loop = o.has("loop") && !o.get("loop").isJsonNull() && o.get("loop").getAsBoolean();
        p.paused = o.has("paused") && !o.get("paused").isJsonNull() && o.get("paused").getAsBoolean();
        if (!o.has("steps") || !o.get("steps").isJsonArray() || o.getAsJsonArray("steps").isEmpty()) {
            throw new IllegalArgumentException("behavior program needs a non-empty steps list");
        }
        int i = 0;
        for (JsonElement e : o.getAsJsonArray("steps")) {
            if (!e.isJsonObject()) throw new IllegalArgumentException("step " + i + " is not an object");
            JsonObject step = e.getAsJsonObject();
            validateStep(step, i);
            p.steps.add(step.deepCopy());
            i++;
        }
        if (o.has("cursors") && o.get("cursors").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("cursors").entrySet()) {
                p.cursors.put(e.getKey(), Cursor.fromJson(e.getValue().getAsJsonObject()));
            }
        }
        if (o.has("placed") && o.get("placed").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("placed").entrySet()) {
                Set<Integer> set = new LinkedHashSet<>();
                for (JsonElement idx : e.getValue().getAsJsonArray()) set.add(idx.getAsInt());
                p.placed.put(Integer.parseInt(e.getKey()), set);
            }
        }
        p.createdAt = optString(o, "createdAt");
        return p;
    }

    /** The step type at an index, e.g. "gather" - convenience for status/engine code. */
    public String stepType(int index) {
        return index >= 0 && index < steps.size()
                ? steps.get(index).get("type").getAsString().toLowerCase(Locale.ROOT) : null;
    }

    private static String req(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) throw new IllegalArgumentException("missing required field: " + key);
        return e.getAsString();
    }
}
