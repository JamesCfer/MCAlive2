package dev.celestia.mcalive2.npc.behavior;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import dev.celestia.mcalive2.util.Json;
import org.bukkit.Material;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Bukkit-facing wiring for behavior programs: registers the {@code behavior_*} and
 * {@code blueprint_*} bridge commands over {@link BehaviorEngine}'s store/registry.
 * Definitions are validated here at author time (structure via
 * {@link BehaviorProgram#validateStep}, materials against the live {@link Material}
 * table, blueprint references against the registry) so a program that starts running
 * can only block on world conditions, never on a typo.
 */
public class BehaviorActuators {

    private final MCAlive2Plugin plugin;
    private final NpcManager npcs;
    private final BehaviorEngine engine;

    public BehaviorActuators(MCAlive2Plugin plugin, NpcManager npcs, BehaviorEngine engine) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.engine = engine;
    }

    public void register(CommandDispatcher d) {
        d.register("behavior_create", this::create);
        d.register("behavior_update", this::update);
        d.register("behavior_pause", this::pause);
        d.register("behavior_resume", this::resume);
        d.register("behavior_delete", this::delete);
        d.register("behavior_status", this::status);
        d.register("blueprint_register", this::blueprintRegister);
        d.register("blueprint_delete", this::blueprintDelete);
    }

    // ---- behavior commands ----

    private JsonObject create(JsonObject args) {
        BehaviorProgram p = BehaviorProgram.fromJson(args);
        validateAgainstWorld(p);
        p.paused = false;
        p.createdAt = Instant.now().toString();
        p.resetCursors();
        engine.store().put(p);
        engine.clearRuntime(p.id);
        engine.save();
        return new JsonObject();
    }

    private JsonObject update(JsonObject args) {
        String id = Json.reqString(args, "id");
        BehaviorProgram existing = engine.store().get(id);
        if (existing == null) throw new IllegalArgumentException("no behavior program with id: " + id);
        // build the replacement from the existing record overlaid with the new fields,
        // then re-validate the whole thing
        JsonObject merged = existing.toJson();
        for (String key : new String[]{"name", "npcIds", "loop", "steps"}) {
            if (args.has(key) && !args.get(key).isJsonNull()) merged.add(key, args.get(key));
        }
        merged.remove("cursors");
        merged.remove("placed");
        BehaviorProgram p = BehaviorProgram.fromJson(merged);
        validateAgainstWorld(p);
        p.paused = false;
        p.resetCursors();
        // physical stock a crew member already carries survives a redefinition
        for (Map.Entry<String, BehaviorProgram.Cursor> e : p.cursors.entrySet()) {
            BehaviorProgram.Cursor old = existing.cursors.get(e.getKey());
            if (old != null) e.getValue().carrying.putAll(old.carrying);
        }
        engine.store().put(p);
        engine.clearRuntime(id);
        engine.save();
        return new JsonObject();
    }

    private JsonObject pause(JsonObject args) {
        BehaviorProgram p = reqProgram(args);
        p.paused = true;
        engine.releaseTickets(p.id);
        engine.save();
        return new JsonObject();
    }

    private JsonObject resume(JsonObject args) {
        BehaviorProgram p = reqProgram(args);
        p.paused = false; // clears a blocked state too - the engine picks it back up next beat
        engine.save();
        return new JsonObject();
    }

    private JsonObject delete(JsonObject args) {
        String id = Json.reqString(args, "id");
        engine.store().remove(id);
        engine.clearRuntime(id);
        engine.save();
        return new JsonObject();
    }

    private JsonObject status(JsonObject args) {
        JsonArray arr = new JsonArray();
        String only = Json.optString(args, "id", null);
        for (BehaviorProgram p : engine.store().all()) {
            if (only != null && !only.equals(p.id)) continue;
            arr.add(summarize(p));
        }
        if (only != null && arr.isEmpty()) throw new IllegalArgumentException("no behavior program with id: " + only);
        JsonObject out = new JsonObject();
        out.add("programs", arr);
        return out;
    }

    private JsonObject summarize(BehaviorProgram p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id);
        o.addProperty("name", p.name);
        o.addProperty("paused", p.paused);
        o.addProperty("loop", p.loop);
        o.addProperty("crew", p.cursors.size());
        o.addProperty("steps", p.steps.size());
        JsonObject cursors = new JsonObject();
        for (Map.Entry<String, BehaviorProgram.Cursor> e : p.cursors.entrySet()) {
            BehaviorProgram.Cursor c = e.getValue();
            JsonObject cur = new JsonObject();
            cur.addProperty("step", c.step);
            String type = p.stepType(c.step);
            cur.addProperty("type", type == null ? "done" : type);
            if (!c.carrying.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> item : c.carrying.entrySet()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(item.getValue()).append("x ").append(item.getKey());
                }
                cur.addProperty("carrying", sb.toString());
            }
            if ("build".equals(type)) {
                String blueprintId = Json.optString(p.steps.get(c.step), "blueprintId", null);
                BlueprintRegistry.Blueprint bp = blueprintId == null ? null : engine.blueprints().get(blueprintId);
                if (bp != null) {
                    cur.addProperty("blueprint", p.placedFor(c.step).size() + "/" + bp.blocks().size() + " placed");
                }
            }
            cursors.add(e.getKey(), cur);
        }
        o.add("cursors", cursors);
        return o;
    }

    // ---- blueprint commands ----

    private JsonObject blueprintRegister(JsonObject args) {
        BlueprintRegistry.Blueprint bp = engine.blueprints().register(
                args, engine.fillCap(), BehaviorEngine.BLUEPRINT_MAX_OFFSET);
        for (dev.celestia.mcalive2.ledger.BlueprintParser.BlockEntry entry : bp.blocks()) {
            if (Material.matchMaterial(entry.material().toUpperCase(Locale.ROOT)) == null) {
                engine.blueprints().delete(bp.id());
                throw new IllegalArgumentException("unknown material in blueprint: " + entry.material());
            }
        }
        engine.save();
        JsonObject out = new JsonObject();
        out.addProperty("id", bp.id());
        out.addProperty("blocks", bp.blocks().size());
        return out;
    }

    private JsonObject blueprintDelete(JsonObject args) {
        engine.blueprints().delete(Json.reqString(args, "id"));
        engine.save();
        return new JsonObject();
    }

    // ---- validation ----

    private BehaviorProgram reqProgram(JsonObject args) {
        String id = Json.reqString(args, "id");
        BehaviorProgram p = engine.store().get(id);
        if (p == null) throw new IllegalArgumentException("no behavior program with id: " + id);
        return p;
    }

    /** Author-time checks that need the live world: crew NPCs exist and live, materials
     *  are real, referenced blueprints are registered. */
    private void validateAgainstWorld(BehaviorProgram p) {
        for (String npcId : p.npcIds) {
            NpcData data = npcs.get(npcId);
            if (data == null) throw new IllegalArgumentException("no NPC with id: " + npcId);
            if (data.dead) throw new IllegalArgumentException("NPC is dead: " + npcId);
        }
        int i = 0;
        for (JsonObject step : p.steps) {
            String type = step.get("type").getAsString();
            switch (type) {
                case "gather" -> reqMaterial(step.get("material").getAsString(), i);
                case "build" -> {
                    String blueprintId = step.get("blueprintId").getAsString();
                    if (engine.blueprints().get(blueprintId) == null) {
                        throw new IllegalArgumentException("step " + i + " references unregistered blueprint: "
                                + blueprintId + " (call blueprint_register first)");
                    }
                }
                case "deposit" -> {
                    if (step.get("items").isJsonArray()) reqItemMaterials(step.getAsJsonArray("items"), i);
                }
                case "withdraw" -> reqItemMaterials(step.getAsJsonArray("items"), i);
                case "follow" -> {
                    String followId = step.get("npcId").getAsString();
                    if (npcs.get(followId) == null) {
                        throw new IllegalArgumentException("step " + i + " follows unknown NPC: " + followId);
                    }
                }
                default -> { /* nothing world-dependent */ }
            }
            i++;
        }
    }

    private void reqItemMaterials(JsonArray items, int stepIndex) {
        for (JsonElement el : items) {
            reqMaterial(el.getAsJsonObject().get("material").getAsString(), stepIndex);
        }
    }

    private void reqMaterial(String name, int stepIndex) {
        if (Material.matchMaterial(name.toUpperCase(Locale.ROOT)) == null) {
            throw new IllegalArgumentException("step " + stepIndex + " has unknown material: " + name);
        }
    }
}
