// MCAlive2 system gadget: position-tracker
// Streams live positions of NPCs (PDC-tagged entities) and online players to the
// brain as an "entity_positions" bridge event on a fixed cadence, so the brain can
// hold a live picture of where everything is instead of polling per request.
//
// Injected at runtime via gadget_define (no plugin change). The brain auto-installs
// and starts this on boot. Idempotent across brain restarts: the active task id is
// held in a JVM system property, so a re-run cancels the previous timer rather than
// stacking a second one (a full server restart clears both the property and the task).
//
// args: { intervalTicks?: number (default 20 = ~1s) }
// returns: { ok, streaming, intervalTicks }
package dev.celestia.mcalive2.gadget.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.gadget.GadgetContract;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public class PositionTracker implements GadgetContract {

    private static final String TASK_PROP = "mcalive2.postracker.task";

    @Override
    public JsonObject run(JsonObject args, GadgetContext ctx) {
        long interval = 20L;
        if (args != null && args.has("intervalTicks") && args.get("intervalTicks").isJsonPrimitive()) {
            try { interval = Math.max(5L, args.get("intervalTicks").getAsLong()); } catch (Exception ignored) {}
        }

        // Cancel a previously-started tracker task, if any (survives brain restart /
        // gadget redefine via the system property, since a fresh classloader loses statics).
        String prev = System.getProperty(TASK_PROP);
        if (prev != null) {
            try { ctx.cancelTask(Integer.parseInt(prev)); } catch (Exception ignored) {}
        }

        final NamespacedKey npcKey = ctx.key("npc_id");
        int taskId = ctx.runTimer(interval, () -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("at", System.currentTimeMillis());
            JsonArray npcs = new JsonArray();
            JsonArray players = new JsonArray();

            for (World w : ctx.server().getWorlds()) {
                for (Entity e : w.getEntities()) {
                    if (!e.isValid()) continue;
                    String id = e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
                    if (id == null) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("id", id);
                    o.addProperty("world", w.getName());
                    o.addProperty("x", round(e.getLocation().getX()));
                    o.addProperty("y", round(e.getLocation().getY()));
                    o.addProperty("z", round(e.getLocation().getZ()));
                    npcs.add(o);
                }
                for (Player p : w.getPlayers()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", p.getName());
                    o.addProperty("world", w.getName());
                    o.addProperty("x", round(p.getLocation().getX()));
                    o.addProperty("y", round(p.getLocation().getY()));
                    o.addProperty("z", round(p.getLocation().getZ()));
                    players.add(o);
                }
            }

            payload.add("npcs", npcs);
            payload.add("players", players);
            ctx.plugin().bridge().broadcastEvent("entity_positions", payload);
        });
        System.setProperty(TASK_PROP, Integer.toString(taskId));

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("streaming", true);
        out.addProperty("intervalTicks", interval);
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
