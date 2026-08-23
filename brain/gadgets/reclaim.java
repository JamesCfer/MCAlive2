package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Death is permanent here, but not final: an NPC leaves a memorial head stamped with its
 * id, and whoever carries that head can call them back.
 *
 * ANY of the Ancients may do it. When one of the dead leaves a head lying in the world,
 * the nearest living Ancient - of any line, not only their own - sets out on foot to
 * fetch it, and where they pick it up is where the dead one stands again. A rival line
 * restoring another line's sworn is allowed, and is a far better story than a rule
 * against it.
 *
 * Every part of this is a plain condition: who is dead, whether a head exists, which
 * Ancient is nearest. No model is consulted, so restoring the dead costs no tokens.
 *
 * The heads themselves are kept from despawning while they lie in the world, otherwise
 * the five-minute vanilla timer would quietly make every death final after all.
 */
public class Reclaim implements GadgetContract {

    private static Integer TASK_ID = null;
    private static final Map<String, String> ERRANDS = new HashMap<String, String>(); // ancientId -> npcId being fetched
    private static int restored = 0;
    private static int preserved = 0;
    private static final List<String> LOG = new ArrayList<String>();

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("reclaim-generation");
        Integer cur = pdc.get(k, PersistentDataType.INTEGER);
        int g = cur == null ? 0 : cur.intValue();
        if (bump) { g = g + 1; pdc.set(k, PersistentDataType.INTEGER, Integer.valueOf(g)); }
        return g;
    }

    private static int reap(GadgetContext ctx) {
        int killed = 0;
        for (org.bukkit.scheduler.BukkitTask t : ctx.server().getScheduler().getPendingTasks()) {
            if (t.getOwner() != ctx.plugin()) continue;
            Object inner = runnableOf(t);
            if (inner != null && inner.getClass().getName().contains("Reclaim")) { t.cancel(); killed++; }
        }
        return killed;
    }

    private static Object runnableOf(Object task) {
        Class<?> c = task.getClass();
        while (c != null) {
            java.lang.reflect.Field[] fs = c.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                if (!Runnable.class.isAssignableFrom(fs[i].getType())) continue;
                try {
                    fs[i].setAccessible(true);
                    Object v = fs[i].get(task);
                    if (v != null && v != task) return v;
                } catch (Throwable ignored) { }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean walkTo(GadgetContext ctx, String npcId, int x, int y, int z) {
        try {
            JsonObject to = new JsonObject();
            to.addProperty("x", x); to.addProperty("y", y); to.addProperty("z", z);
            JsonObject a = new JsonObject();
            a.addProperty("npcId", npcId);
            a.add("to", to);
            JsonObject call = new JsonObject();
            call.addProperty("id", "navigate");
            call.add("args", a);
            JsonObject r = ctx.invoke("gadget_run", call);
            return r.has("started") && r.get("started").getAsBoolean();
        } catch (Throwable t) { return false; }
    }


    private static boolean stillWalking(GadgetContext ctx, String npcId) {
        try {
            JsonObject a = new JsonObject();
            a.addProperty("npcId", npcId);
            a.addProperty("action", "status");
            JsonObject call = new JsonObject();
            call.addProperty("id", "navigate");
            call.add("args", a);
            JsonObject r = ctx.invoke("gadget_run", call);
            return r.has("walking") && r.get("walking").getAsBoolean();
        } catch (Throwable t) { return false; }
    }


    /**
     * Head for a memorial that is too far for one search. A* is node-bounded, so a
     * pilgrimage of hundreds of blocks is walked as a series of shorter legs aimed along
     * the bearing - the same way a person crosses country they cannot see the end of.
     */
    private static boolean walkToward(GadgetContext ctx, String npcId, Location from, Location goal) {
        if (walkTo(ctx, npcId, goal.getBlockX(), goal.getBlockY(), goal.getBlockZ())) return true;
        double dx = goal.getX() - from.getX(), dz = goal.getZ() - from.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1) return false;
        World w = from.getWorld();
        for (int leg = 64; leg >= 16; leg -= 16) {
            int nx = (int) Math.round(from.getX() + dx / d * leg);
            int nz = (int) Math.round(from.getZ() + dz / d * leg);
            w.getChunkAt(nx >> 4, nz >> 4).load(true);
            int ny = w.getHighestBlockYAt(nx, nz, org.bukkit.HeightMap.OCEAN_FLOOR) + 1;
            if (walkTo(ctx, npcId, nx, ny, nz)) return true;
        }
        return false;
    }

    /** The npc id stamped on a head item, or null if this is just an item. */
    private static String headId(GadgetContext ctx, ItemStack stack) {
        if (stack == null || stack.getType() != Material.PLAYER_HEAD) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(
                ctx.plugin().npcManager().key(), PersistentDataType.STRING);
    }

    /**
     * Every memorial head lying in the world, kept alive against the despawn timer.
     *
     * A head can only be seen in a LOADED chunk, and the ground where someone died is
     * usually far from anywhere anyone still walks - so the resting place of each of the
     * dead is loaded first. Without this the memorials of the far-flung dead are
     * invisible and nobody ever comes for them.
     */
    private static Map<String, Item> heads(GadgetContext ctx, boolean preserve) {
        Map<String, Item> found = new HashMap<String, Item>();
        for (NpcData d : ctx.plugin().npcManager().all()) {
            if (!d.dead) continue;
            Location rest = d.lastLocation != null ? d.lastLocation : d.home;
            if (rest == null || rest.getWorld() == null) continue;
            try {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        rest.getWorld().getChunkAt((rest.getBlockX() >> 4) + dx,
                                (rest.getBlockZ() >> 4) + dz).load(true);
                    }
                }
            } catch (Throwable ignored) { }
        }
        for (World w : ctx.server().getWorlds()) {
            for (Item item : w.getEntitiesByClass(Item.class)) {
                String id = headId(ctx, item.getItemStack());
                if (id == null) continue;
                if (preserve) {
                    item.setTicksLived(1);        // a memorial should not rot away
                    preserved++;
                }
                found.put(id, item);
            }
        }
        return found;
    }

    private static List<String> ancients(GadgetContext ctx) {
        List<String> out = new ArrayList<String>();
        for (NpcData d : ctx.plugin().npcManager().all()) {
            if (d.dead) continue;
            if (d.id.indexOf('-') >= 0) continue;   // the Ancients are the un-numbered ones
            out.add(d.id);
        }
        return out;
    }

    private void beat(GadgetContext ctx) {
        NpcManager npcs = ctx.plugin().npcManager();
        Map<String, Item> lying = heads(ctx, true);
        if (lying.isEmpty()) { ERRANDS.clear(); return; }

        for (Map.Entry<String, Item> e : lying.entrySet()) {
          try {
            String deadId = e.getKey();
            Item head = e.getValue();
            NpcData dead = npcs.get(deadId);
            if (dead == null || !dead.dead) continue;      // already back among the living

            // is an Ancient already fetching this one?
            String carrier = null;
            for (Map.Entry<String, String> er : ERRANDS.entrySet()) {
                if (er.getValue().equals(deadId)) { carrier = er.getKey(); break; }
            }

            if (carrier != null) {
                NpcData a = npcs.get(carrier);
                Entity ae = a == null ? null : npcs.resolveEntity(a);
                if (ae == null || a.dead) { ERRANDS.remove(carrier); continue; }
                if (!ae.getWorld().equals(head.getWorld())) continue;
                double gap = ae.getLocation().distance(head.getLocation());
                // The search is node-bounded, so one call only paths part of the way to a
                // memorial hundreds of blocks off. Set out again each beat once the last
                // leg is walked, and the journey is covered in stages.
                if (gap > 3.0 && !stillWalking(ctx, carrier)) {
                    if (!walkToward(ctx, carrier, ae.getLocation(), head.getLocation())) {
                        ERRANDS.remove(carrier);      // no road to it at all
                    }
                    continue;
                }
                if (gap <= 3.0) {
                    // close enough to take it up - and where it is taken up is where
                    // they come back
                    Location at = head.getLocation();
                    head.remove();
                    ERRANDS.remove(carrier);
                    try {
                        JsonObject rev = new JsonObject();
                        rev.addProperty("id", deadId);
                        rev.addProperty("x", at.getBlockX());
                        rev.addProperty("y", at.getBlockY() + 1);
                        rev.addProperty("z", at.getBlockZ());
                        ctx.invoke("npc_revive", rev);
                        // npc_revive clears the plugin death flag but not the ledger,
                        // which is what the console reads - so mark them living there too
                        try {
                            JsonObject q = new JsonObject();
                            q.addProperty("collection", "npcs");
                            q.addProperty("id", deadId);
                            JsonObject rec = ctx.invoke("ledger_get", q);
                            rec.addProperty("alive", true);
                            rec.addProperty("hunger", 14);
                            rec.addProperty("activity", "newly restored, unsteady on their feet");
                            rec.addProperty("restoredBy", carrier);
                            JsonObject put = new JsonObject();
                            put.addProperty("collection", "npcs");
                            put.add("record", rec);
                            ctx.invoke("ledger_put", put);
                        } catch (Throwable ignored) { }
                        restored++;
                        LOG.add(a.name + " restored " + dead.name);
                        while (LOG.size() > 12) LOG.remove(0);
                        JsonObject ev = new JsonObject();
                        ev.addProperty("restoredNpc", deadId);
                        ev.addProperty("restoredName", dead.name);
                        ev.addProperty("byAncient", carrier);
                        ev.addProperty("at", at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
                        ctx.plugin().bridge().broadcastEvent("npc_restored", ev);
                    } catch (Throwable ignored) { }
                }
                continue;
            }

            // nobody is going: send the nearest free Ancient, whatever line they belong to
            String bestId = null;
            double bestD = Double.MAX_VALUE;
            for (String aid : ancients(ctx)) {
                if (ERRANDS.containsKey(aid)) continue;
                NpcData a = npcs.get(aid);
                Entity ae = a == null ? null : npcs.resolveEntity(a);
                if (ae == null || !ae.getWorld().equals(head.getWorld())) continue;
                double d = ae.getLocation().distance(head.getLocation());
                if (d < bestD) { bestD = d; bestId = aid; }
            }
            if (bestId == null) continue;
            Location h = head.getLocation();
            NpcData chosen = npcs.get(bestId);
            Entity chosenE = chosen == null ? null : npcs.resolveEntity(chosen);
            if (chosenE != null && walkToward(ctx, bestId, chosenE.getLocation(), h)) {
                ERRANDS.put(bestId, deadId);
                LOG.add(npcs.get(bestId).name + " set out for " + dead.name + " (" + Math.round(bestD) + "b)");
                while (LOG.size() > 12) LOG.remove(0);
                JsonObject ev = new JsonObject();
                ev.addProperty("ancient", bestId);
                ev.addProperty("goingFor", deadId);
                ev.addProperty("blocksAway", Math.round(bestD));
                ctx.plugin().bridge().broadcastEvent("ancient_seeks_head", ev);
            } else {
                LOG.add("no road yet to " + dead.name + " memorial");
                while (LOG.size() > 12) LOG.remove(0);
            }
          } catch (Throwable t) {
            LOG.add("memorial error: " + t.getClass().getSimpleName() + " " + t.getMessage());
            while (LOG.size() > 12) LOG.remove(0);
          }
        }
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("scan")) {
            Map<String, Item> lying = heads(ctx, false);
            JsonObject out = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Map.Entry<String, Item> e : lying.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("npcId", e.getKey());
                Location l = e.getValue().getLocation();
                o.addProperty("at", l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
                arr.add(o);
            }
            out.add("headsLying", arr);
            JsonArray deadArr = new JsonArray();
            for (NpcData d : ctx.plugin().npcManager().all()) {
                if (!d.dead) continue;
                JsonObject o = new JsonObject();
                o.addProperty("npcId", d.id);
                o.addProperty("name", d.name);
                o.addProperty("headExists", lying.containsKey(d.id));
                deadArr.add(o);
            }
            out.add("dead", deadArr);
            return out;
        }
        if (action.equals("remember")) {
            // Re-lay a memorial that the vanilla five-minute item timer swept away before
            // heads were being preserved. It restores the token, not the NPC: an Ancient
            // still has to walk out and take it up.
            NpcManager npcs = ctx.plugin().npcManager();
            String npcId = args.get("npcId").getAsString();
            NpcData d = npcs.get(npcId);
            if (d == null) throw new IllegalArgumentException("no NPC with id: " + npcId);
            if (!d.dead) throw new IllegalStateException(npcId + " is not dead");
            Location where;
            if (args.has("x")) {
                where = new Location(ctx.world(null), args.get("x").getAsInt(),
                        args.get("y").getAsInt(), args.get("z").getAsInt());
            } else if (d.lastLocation != null) {
                where = d.lastLocation.clone();
            } else if (d.home != null) {
                where = d.home.clone();
            } else {
                throw new IllegalStateException("nowhere known to lay " + npcId + " memorial");
            }
            where.getChunk().load();
            int surface = where.getWorld().getHighestBlockYAt(where.getBlockX(), where.getBlockZ(),
                    org.bukkit.HeightMap.OCEAN_FLOOR);
            if (where.getBlockY() < surface - 8) where.setY(surface + 1);   // not buried in rock
            Item dropped = where.getWorld().dropItem(where.clone().add(0.5, 1, 0.5), npcs.headOf(d));
            dropped.setTicksLived(1);
            JsonObject out = new JsonObject();
            out.addProperty("laid", npcId);
            out.addProperty("name", d.name);
            out.addProperty("at", where.getBlockX() + "," + where.getBlockY() + "," + where.getBlockZ());
            return out;
        }
        if (action.equals("gather_memorials")) {
            // Used when a line moves: lift every memorial and re-lay it on the ground the
            // line now holds, so the dead can still be fetched. One head per dead NPC -
            // any stragglers are cleared first so nobody can be restored twice.
            NpcManager npcs = ctx.plugin().npcManager();
            JsonObject where = args.getAsJsonObject("sites");   // npcId prefix -> {x,y,z}
            for (Map.Entry<String, Item> e : heads(ctx, false).entrySet()) e.getValue().remove();
            JsonArray laid = new JsonArray();
            for (NpcData d : npcs.all()) {
                if (!d.dead) continue;
                String line = d.id.indexOf('-') > 0 ? d.id.substring(0, d.id.indexOf('-')) : d.id;
                if (!where.has(line)) continue;
                JsonObject site = where.getAsJsonObject(line);
                World w = ctx.world(null);
                Location at = new Location(w, site.get("x").getAsInt(), site.get("y").getAsInt(), site.get("z").getAsInt());
                at.getChunk().load(true);
                Item dropped = w.dropItem(at.clone().add(0.5, 1, 0.5), npcs.headOf(d));
                dropped.setTicksLived(1);
                d.lastLocation = at.clone();          // so its resting place is found again
                JsonObject o = new JsonObject();
                o.addProperty("npcId", d.id);
                o.addProperty("name", d.name);
                o.addProperty("at", at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
                laid.add(o);
            }
            npcs.save();
            JsonObject out = new JsonObject();
            out.add("memorials", laid);
            return out;
        }
        if (action.equals("jobs")) {
            JsonObject out = new JsonObject();
            JsonObject m = new JsonObject();
            NpcManager npcs = ctx.plugin().npcManager();
            for (Map.Entry<String, String> e : ERRANDS.entrySet()) {
                NpcData d = npcs.get(e.getValue());
                m.addProperty(e.getKey(), "going to reclaim " + (d == null ? e.getValue() : d.name) + "'s head");
            }
            out.add("doing", m);
            return out;
        }
        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("restored", restored);
            out.addProperty("errandsInFlight", ERRANDS.size());
            JsonArray l = new JsonArray();
            for (String s : LOG) l.add(s);
            out.add("recent", l);
            return out;
        }
        if (action.equals("stop")) {
            generation(ctx, true);
            int killed = reap(ctx);
            if (TASK_ID != null) { ctx.cancelTask(TASK_ID.intValue()); TASK_ID = null; }
            JsonObject out = new JsonObject();
            out.addProperty("stopped", true);
            out.addProperty("staleTimersCancelled", killed);
            return out;
        }

        final int myGen = generation(ctx, true);
        int killed = reap(ctx);
        if (TASK_ID != null) { ctx.cancelTask(TASK_ID.intValue()); TASK_ID = null; }
        ERRANDS.clear();
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 60;
        TASK_ID = Integer.valueOf(ctx.runTimer(period, new Runnable() {
            public void run() {
                try {
                    if (generation(ctx, false) != myGen) { reap(ctx); return; }
                    beat(ctx);
                } catch (Throwable ignored) { }
            }
        }));
        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("generation", myGen);
        out.addProperty("staleTimersCancelled", killed);
        return out;
    }
}
