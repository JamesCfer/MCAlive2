package dev.celestia.mcalive2.ledger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structured world memory: typed JSON-record collections, CRUD + subset-match query.
 * Plain classes + gson only — no Bukkit dependency, so this is unit-testable in isolation.
 */
public class Ledger {

    /** The seven collections MCAlive2 M1 knows about. */
    public static final Set<String> COLLECTIONS = Set.of(
            "npcs", "factions", "places", "quests", "facts", "promises", "players");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, Map<String, JsonObject>> data = new ConcurrentHashMap<>();

    public Ledger() {
        for (String c : COLLECTIONS) data.put(c, new LinkedHashMap<>());
    }

    private Map<String, JsonObject> collectionOf(String collection) {
        Map<String, JsonObject> map = data.get(collection);
        if (map == null) throw new IllegalArgumentException("unknown ledger collection: " + collection);
        return map;
    }

    /** Insert or replace a record. The record must carry a non-blank "id" field. */
    public synchronized JsonObject put(String collection, JsonObject record) {
        Map<String, JsonObject> map = collectionOf(collection);
        JsonElement idEl = record.get("id");
        if (idEl == null || idEl.isJsonNull() || idEl.getAsString().isBlank()) {
            throw new IllegalArgumentException("record is missing a non-blank \"id\" field");
        }
        JsonObject copy = record.deepCopy();
        map.put(idEl.getAsString(), copy);
        return copy;
    }

    public synchronized JsonObject get(String collection, String id) {
        return collectionOf(collection).get(id);
    }

    public synchronized boolean delete(String collection, String id) {
        return collectionOf(collection).remove(id) != null;
    }

    public synchronized List<JsonObject> all(String collection) {
        return new ArrayList<>(collectionOf(collection).values());
    }

    /**
     * Subset-match query: every key/value pair in {@code filter} must equal the
     * corresponding field in a record (scalar equality; for array fields, matches
     * if the array CONTAINS the filter value). A null/empty filter returns everything.
     */
    public synchronized List<JsonObject> query(String collection, JsonObject filter) {
        List<JsonObject> results = new ArrayList<>();
        for (JsonObject record : collectionOf(collection).values()) {
            if (matches(record, filter)) results.add(record);
        }
        return results;
    }

    private boolean matches(JsonObject record, JsonObject filter) {
        if (filter == null) return true;
        for (Map.Entry<String, JsonElement> entry : filter.entrySet()) {
            JsonElement want = entry.getValue();
            JsonElement have = record.get(entry.getKey());
            if (have == null) return false;
            if (have.isJsonArray()) {
                boolean contains = false;
                for (JsonElement item : have.getAsJsonArray()) {
                    if (item.equals(want)) { contains = true; break; }
                }
                if (!contains) return false;
            } else if (!have.equals(want)) {
                return false;
            }
        }
        return true;
    }

    // ---- persistence: one JSON array file per collection ----

    public synchronized void save(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("could not create ledger directory: " + dir);
        }
        for (String collection : COLLECTIONS) {
            JsonArray arr = new JsonArray();
            for (JsonObject record : collectionOf(collection).values()) arr.add(record);
            Files.write(new File(dir, collection + ".json").toPath(),
                    GSON.toJson(arr).getBytes(StandardCharsets.UTF_8));
        }
    }

    public synchronized void load(File dir) throws IOException {
        if (!dir.exists()) return;
        for (String collection : COLLECTIONS) {
            File f = new File(dir, collection + ".json");
            if (!f.exists()) continue;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (s.isBlank()) continue;
            JsonArray arr = JsonParser.parseString(s).getAsJsonArray();
            Map<String, JsonObject> map = collectionOf(collection);
            map.clear();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                map.put(o.get("id").getAsString(), o);
            }
        }
    }
}
