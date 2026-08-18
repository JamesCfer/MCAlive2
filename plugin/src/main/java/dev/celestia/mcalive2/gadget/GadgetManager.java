package dev.celestia.mcalive2.gadget;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of runtime-compiled gadgets: {id -> description, source, compiled instance}.
 * Persists (id, description, source) to {@code gadgets.json} in the plugin's data
 * folder. Each successfully (re)compiled gadget is registered as dispatcher command
 * {@code gadget:<id>}, which runs it on the main thread via
 * {@link dev.celestia.mcalive2.bridge.CommandDispatcher}.
 *
 * <p>On {@link #loadAll()}, every persisted gadget is recompiled against the current
 * plugin classes; one that fails to compile (e.g. because the plugin's API shifted under
 * it) logs a warning and stays unloaded - its source is kept so a later gadget_define
 * fix, or a future plugin version, can bring it back.
 */
public class GadgetManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final MCAlive2Plugin plugin;
    private final CommandDispatcher dispatcher;
    private final GadgetCompiler compiler = new GadgetCompiler();
    private final Map<String, Entry> gadgets = new LinkedHashMap<>();

    public GadgetManager(MCAlive2Plugin plugin, CommandDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
    }

    private static final class Entry {
        String description;
        String source;
        GadgetContract instance; // null if currently unloaded (compile failure)

        Entry(String description, String source, GadgetContract instance) {
            this.description = description;
            this.source = source;
            this.instance = instance;
        }
    }

    /**
     * Compile {@code source} as gadget {@code id}, and on success (re)register it as
     * dispatcher command {@code gadget:<id>} and persist it. On failure the thrown
     * {@link GadgetCompiler.GadgetCompileException} carries the full javac diagnostics -
     * callers should let it propagate so the bridge error message IS the diagnostics.
     */
    public synchronized void define(String id, String source, String description)
            throws GadgetCompiler.GadgetCompileException {
        GadgetContract instance = compileGadget(id, source);
        gadgets.put(id, new Entry(description, source, instance));
        registerCommand(id);
        save();
    }

    public synchronized JsonObject run(String id, JsonObject args) {
        Entry entry = gadgets.get(id);
        if (entry == null) throw new IllegalArgumentException("no gadget with id: " + id);
        if (entry.instance == null) {
            throw new IllegalArgumentException("gadget \"" + id + "\" is not currently loaded (last compile failed)");
        }
        return runInstance(id, entry.instance, args);
    }

    public synchronized JsonArray list() {
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Entry> e : gadgets.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getKey());
            o.addProperty("description", e.getValue().description == null ? "" : e.getValue().description);
            arr.add(o);
        }
        return arr;
    }

    public synchronized JsonObject get(String id) {
        Entry entry = gadgets.get(id);
        if (entry == null) throw new IllegalArgumentException("no gadget with id: " + id);
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("description", entry.description == null ? "" : entry.description);
        o.addProperty("source", entry.source);
        return o;
    }

    public synchronized void delete(String id) {
        if (gadgets.remove(id) == null) throw new IllegalArgumentException("no gadget with id: " + id);
        dispatcher.unregister("gadget:" + id);
        save();
    }

    // ---- compile / run helpers ----

    private GadgetContract compileGadget(String id, String source) throws GadgetCompiler.GadgetCompileException {
        // The gadget id names the registry entry / command only - never the Java class.
        // The author declares any public class implementing GadgetContract; the compiler finds it.
        return compiler.compile(source, GadgetContract.class, plugin.getClass().getClassLoader());
    }

    private void registerCommand(String id) {
        dispatcher.register("gadget:" + id, args -> {
            synchronized (GadgetManager.this) {
                return run(id, args == null ? new JsonObject() : args);
            }
        });
    }

    private JsonObject runInstance(String id, GadgetContract instance, JsonObject args) {
        GadgetContext ctx = new GadgetContext(plugin, dispatcher);
        try {
            JsonObject result = instance.run(args == null ? new JsonObject() : args, ctx);
            return result == null ? new JsonObject() : result;
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
            JsonArray frames = new JsonArray();
            StackTraceElement[] trace = e.getStackTrace();
            for (int i = 0; i < Math.min(5, trace.length); i++) {
                frames.add(trace[i].toString());
            }
            error.add("stack", frames);
            plugin.getLogger().warning("Gadget \"" + id + "\" threw: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return error;
        }
    }

    // ---- lifecycle ----

    private File file() {
        return new File(plugin.getDataFolder(), "gadgets.json");
    }

    public synchronized void save() {
        try {
            plugin.getDataFolder().mkdirs();
            JsonArray arr = new JsonArray();
            for (Map.Entry<String, Entry> e : gadgets.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.getKey());
                o.addProperty("description", e.getValue().description == null ? "" : e.getValue().description);
                o.addProperty("source", e.getValue().source);
                arr.add(o);
            }
            Files.write(file().toPath(), GSON.toJson(arr).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save gadgets.json: " + e.getMessage());
        }
    }

    /** Loads persisted gadgets and recompiles each, registering the ones that succeed. */
    public synchronized void loadAll() {
        File file = file();
        if (!file.exists()) return;
        try {
            String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (s.isBlank()) return;
            JsonArray arr = JsonParser.parseString(s).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String id = o.get("id").getAsString();
                String description = o.has("description") && !o.get("description").isJsonNull()
                        ? o.get("description").getAsString() : "";
                String source = o.get("source").getAsString();

                GadgetContract instance = null;
                try {
                    instance = compileGadget(id, source);
                    registerCommand(id);
                } catch (GadgetCompiler.GadgetCompileException e) {
                    plugin.getLogger().warning("Gadget \"" + id
                            + "\" failed to recompile on load and stays unloaded: " + e.getMessage());
                }
                gadgets.put(id, new Entry(description, source, instance));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load gadgets.json: " + e.getMessage());
        }
    }
}
