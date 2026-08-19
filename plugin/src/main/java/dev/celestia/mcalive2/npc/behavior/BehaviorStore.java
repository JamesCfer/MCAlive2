package dev.celestia.mcalive2.npc.behavior;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory registry + persistence for {@link BehaviorProgram}s, in the style of
 * {@link dev.celestia.mcalive2.formula.FormulaStore}: plain Gson + java.util, no Bukkit
 * dependency, one behaviors.json file. Cursors (and their carrying maps) persist with
 * the programs, so a server restart resumes every crew mid-program.
 */
public class BehaviorStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, BehaviorProgram> programs = new LinkedHashMap<>();

    public synchronized void put(BehaviorProgram program) {
        programs.put(program.id, program);
    }

    public synchronized BehaviorProgram get(String id) {
        return programs.get(id);
    }

    public synchronized boolean remove(String id) {
        return programs.remove(id) != null;
    }

    public synchronized List<BehaviorProgram> all() {
        return new ArrayList<>(programs.values());
    }

    // ---- persistence: single behaviors.json file ----

    public synchronized void save(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create directory: " + parent);
        }
        JsonArray arr = new JsonArray();
        for (BehaviorProgram p : programs.values()) arr.add(p.toJson());
        Files.write(file.toPath(), GSON.toJson(arr).getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void load(File file) throws IOException {
        if (!file.exists()) return;
        String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (s.isBlank()) return;
        JsonArray arr = JsonParser.parseString(s).getAsJsonArray();
        programs.clear();
        for (JsonElement e : arr) {
            BehaviorProgram p = BehaviorProgram.fromJson(e.getAsJsonObject());
            programs.put(p.id, p);
        }
    }
}
