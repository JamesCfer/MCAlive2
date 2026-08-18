package dev.celestia.mcalive2.formula;

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
import java.util.function.Predicate;

/**
 * Stores runtime-authored formula records: reusable recipes over existing bridge
 * commands (see DESIGN.md "formula engine"). Records are kept as raw {@link JsonObject}s,
 * the same way {@link dev.celestia.mcalive2.ledger.Ledger} stores its collections.
 * Plain Gson + java.util only - no Bukkit dependency - so define-time validation is
 * unit-testable in isolation; the caller supplies the live command whitelist as a
 * predicate rather than this class depending on the dispatcher directly.
 */
public class FormulaStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, JsonObject> formulas = new LinkedHashMap<>();

    /**
     * Validate and store a formula definition, overwriting any existing record with the
     * same id.
     *
     * @param spec               the formula record: {id, description?, params?, steps, loop?}
     * @param isRegisteredCommand tests whether a step's {@code cmd} is a live dispatcher command
     * @return the stored (defensively copied) record
     */
    public synchronized JsonObject define(JsonObject spec, Predicate<String> isRegisteredCommand) {
        String id = reqString(spec, "id");

        if (!spec.has("steps") || !spec.get("steps").isJsonArray() || spec.getAsJsonArray("steps").isEmpty()) {
            throw new IllegalArgumentException("formula must have at least one step");
        }
        for (JsonElement stepEl : spec.getAsJsonArray("steps")) {
            if (!stepEl.isJsonObject()) throw new IllegalArgumentException("formula step must be an object");
            JsonObject step = stepEl.getAsJsonObject();
            String cmd = reqString(step, "cmd");
            if (cmd.startsWith("formula_")) {
                throw new IllegalArgumentException("formula steps cannot call formula_* commands (no recursion): " + cmd);
            }
            if (cmd.equals("npc_assign_job")) {
                throw new IllegalArgumentException("formula steps cannot call npc_assign_job");
            }
            if (isRegisteredCommand == null || !isRegisteredCommand.test(cmd)) {
                throw new IllegalArgumentException("unknown command: " + cmd);
            }
            if (step.has("args") && step.get("args").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : step.getAsJsonObject("args").entrySet()) {
                    JsonElement v = e.getValue();
                    if (v != null && !v.isJsonNull() && !v.isJsonPrimitive()) {
                        throw new IllegalArgumentException(
                                "step arg \"" + e.getKey() + "\" must be a number, string, or boolean");
                    }
                }
            } else if (step.has("args")) {
                throw new IllegalArgumentException("step \"args\" must be an object");
            }
        }
        if (spec.has("params") && !spec.get("params").isJsonArray()) {
            throw new IllegalArgumentException("formula \"params\" must be an array");
        }
        if (spec.has("loop") && !spec.get("loop").isJsonObject()) {
            throw new IllegalArgumentException("formula \"loop\" must be an object");
        }

        JsonObject copy = spec.deepCopy();
        formulas.put(id, copy);
        return copy;
    }

    public synchronized JsonObject get(String id) {
        return formulas.get(id);
    }

    public synchronized boolean delete(String id) {
        return formulas.remove(id) != null;
    }

    public synchronized List<JsonObject> all() {
        return new ArrayList<>(formulas.values());
    }

    private static String reqString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) throw new IllegalArgumentException("missing required field: " + key);
        return e.getAsString();
    }

    // ---- persistence: single formulas.json file ----

    public synchronized void save(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create directory: " + parent);
        }
        JsonArray arr = new JsonArray();
        for (JsonObject record : formulas.values()) arr.add(record);
        Files.write(file.toPath(), GSON.toJson(arr).getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void load(File file) throws IOException {
        if (!file.exists()) return;
        String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (s.isBlank()) return;
        JsonArray arr = JsonParser.parseString(s).getAsJsonArray();
        formulas.clear();
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            formulas.put(o.get("id").getAsString(), o);
        }
    }
}
