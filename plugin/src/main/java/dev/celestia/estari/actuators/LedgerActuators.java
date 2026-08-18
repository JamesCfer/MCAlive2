package dev.celestia.estari.actuators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.estari.EstariPlugin;
import dev.celestia.estari.bridge.CommandDispatcher;
import dev.celestia.estari.ledger.Ledger;
import dev.celestia.estari.ledger.NpcContext;
import dev.celestia.estari.util.Json;

import java.io.File;

/**
 * Bukkit-facing wiring for the ledger: registers {@code ledger_put/get/query/delete}
 * and {@code npc_context}, and persists the (Bukkit-free) {@link Ledger} to the plugin
 * data folder.
 */
public class LedgerActuators {

    private final EstariPlugin plugin;
    private final Ledger ledger = new Ledger();

    public LedgerActuators(EstariPlugin plugin) {
        this.plugin = plugin;
    }

    public Ledger ledger() {
        return ledger;
    }

    public void register(CommandDispatcher d) {
        d.register("ledger_put", this::put);
        d.register("ledger_get", this::get);
        d.register("ledger_query", this::query);
        d.register("ledger_delete", this::delete);
        d.register("npc_context", this::npcContext);
    }

    private String collection(JsonObject args) {
        String c = Json.reqString(args, "collection");
        if (!Ledger.COLLECTIONS.contains(c)) {
            throw new IllegalArgumentException("unknown ledger collection: " + c
                    + " (valid: " + Ledger.COLLECTIONS + ")");
        }
        return c;
    }

    private JsonObject put(JsonObject args) {
        String collection = collection(args);
        JsonObject record = Json.reqObject(args, "record");
        return ledger.put(collection, record);
    }

    private JsonObject get(JsonObject args) {
        String collection = collection(args);
        String id = Json.reqString(args, "id");
        JsonObject record = ledger.get(collection, id);
        if (record == null) throw new IllegalArgumentException("no record " + id + " in " + collection);
        return record;
    }

    private JsonObject query(JsonObject args) {
        String collection = collection(args);
        JsonObject filter = args.has("filter") && args.get("filter").isJsonObject()
                ? args.getAsJsonObject("filter") : null;
        JsonArray arr = new JsonArray();
        for (JsonObject record : ledger.query(collection, filter)) arr.add(record);
        JsonObject out = new JsonObject();
        out.add("records", arr);
        return out;
    }

    private JsonObject delete(JsonObject args) {
        String collection = collection(args);
        String id = Json.reqString(args, "id");
        boolean removed = ledger.delete(collection, id);
        JsonObject out = new JsonObject();
        out.addProperty("removed", removed);
        return out;
    }

    private JsonObject npcContext(JsonObject args) {
        return NpcContext.resolve(ledger, Json.reqString(args, "npcId"));
    }

    // ---- lifecycle ----

    /** Called by the NPC death listener to record permadeath in the ledger's npcs collection, if it exists there. */
    public void markNpcDeadIfPresent(String npcId, String diedAtIso) {
        JsonObject record = ledger.get("npcs", npcId);
        if (record == null) return;
        record.addProperty("alive", false);
        record.addProperty("diedAt", diedAtIso);
        ledger.put("npcs", record);
    }

    private File dir() {
        return new File(plugin.getDataFolder(), "ledger");
    }

    public void save() {
        try {
            ledger.save(dir());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save ledger: " + e.getMessage());
        }
    }

    public void load() {
        try {
            ledger.load(dir());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load ledger: " + e.getMessage());
        }
    }
}
