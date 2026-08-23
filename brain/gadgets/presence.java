package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the ground under the world's NPCs loaded.
 *
 * The map view is built from gadget:world-scan, which can only survey chunks that are
 * currently LOADED, and chunk tickets are otherwise held only by BehaviorEngine programs
 * (capped per program). Any NPC driven by something else - the industry loop, a gadget,
 * or nothing at all - stands in a chunk that quietly unloads, so its part of the world
 * vanishes from the map and stops ticking.
 *
 * This holds a plugin chunk ticket around every living NPC and follows them as they move.
 * Bukkit chunk tickets are NOT refcounted, so releasing one the behavior engine believes
 * it still holds would silently unload its crew: this only ever releases a chunk that no
 * NPC is anywhere near, which is exactly the set the engine has no interest in either.
 *
 * Actions: "start", "stop", "status" (status also reports which NPC chunks are loaded,
 * without changing anything - use it to diagnose a patchy map).
 */
public class Presence implements GadgetContract {

    private static Integer TASK_ID = null;
    private static final Set<String> HELD = new HashSet<String>();
    private static int radius = 1;
    private static int lastHeld = 0;
    private static final Set<String> EMPTY_LAST_SWEEP = new HashSet<String>();

    private static String key(String world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    /**
     * Redefining a gadget loads a brand new class, so any static handle to a running
     * timer is lost and the old timer keeps ticking with nothing able to stop it. The
     * live generation number is therefore kept OUTSIDE the class, in the world's
     * persistent data: each run bumps it, and a timer whose generation is stale cancels
     * itself on its next beat.
     */
    private static int generation(GadgetContext ctx, boolean bump) {
        org.bukkit.World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("presence-generation");
        Integer cur = pdc.get(k, org.bukkit.persistence.PersistentDataType.INTEGER);
        int g = cur == null ? 0 : cur.intValue();
        if (bump) {
            g = g + 1;
            pdc.set(k, org.bukkit.persistence.PersistentDataType.INTEGER, Integer.valueOf(g));
        }
        return g;
    }

    /** Cancel timers left behind by earlier versions of this gadget, found by class name. */
    private static int reap(GadgetContext ctx) {
        int killed = 0;
        for (org.bukkit.scheduler.BukkitTask t : ctx.server().getScheduler().getPendingTasks()) {
            if (t.getOwner() != ctx.plugin()) continue;
            Object inner = runnableOf(t);
            if (inner == null) continue;
            String n = inner.getClass().getName();
            if (n.contains("Presence")) { t.cancel(); killed++; }
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

    private static class Spot {
        String npcId;
        String world;
        int cx;
        int cz;
        boolean loaded;
    }

    /** Where every resolvable, living NPC currently stands. */
    private static List<Spot> spots(GadgetContext ctx) {
        List<Spot> out = new ArrayList<Spot>();
        NpcManager npcs = ctx.plugin().npcManager();
        Collection<NpcData> all = npcs.all();
        for (NpcData d : all) {
            if (d.dead) continue;
            Location loc = null;
            Entity e = npcs.resolveEntity(d);
            if (e != null) loc = e.getLocation();
            else if (d.lastLocation != null) loc = d.lastLocation;
            if (loc == null || loc.getWorld() == null) continue;
            Spot s = new Spot();
            s.npcId = d.id;
            s.world = loc.getWorld().getName();
            s.cx = loc.getBlockX() >> 4;
            s.cz = loc.getBlockZ() >> 4;
            s.loaded = loc.getWorld().isChunkLoaded(s.cx, s.cz);
            out.add(s);
        }
        return out;
    }


    /** Is a living NPC or a player standing in this chunk? NPC bodies carry an "npc_id"
     *  tag in their persistent data. */
    private static boolean occupied(org.bukkit.Chunk c) {
        for (Entity e : c.getEntities()) {
            if (e instanceof org.bukkit.entity.Player) return true;
            for (org.bukkit.NamespacedKey k : e.getPersistentDataContainer().getKeys()) {
                if ("npc_id".equals(k.getKey())) return true;
            }
        }
        return false;
    }

    /**
     * Send home every chunk nobody is standing in.
     *
     * Ground gets loaded constantly for reasons that are over in a tick - a forager
     * striding on, the pathfinder reading the way ahead, a memorial being looked up -
     * and without this the world simply accumulates them: seventeen hundred chunks held
     * open for forty that were actually occupied. Capped per sweep so a big cleanup is
     * spread over several beats rather than stalling the server on one.
     */
    private static int releaseEmpty(GadgetContext ctx, int cap) {
        int freed = 0;
        for (World w : ctx.server().getWorlds()) {
            for (org.bukkit.Chunk c : w.getLoadedChunks()) {
                if (freed >= cap) return freed;
                String ck = key(w.getName(), c.getX(), c.getZ());
                if (HELD.contains(ck)) { EMPTY_LAST_SWEEP.remove(ck); continue; }
                if (occupied(c)) { EMPTY_LAST_SWEEP.remove(ck); continue; }
                if (EMPTY_LAST_SWEEP.add(ck)) continue;   // empty once: give it a sweep of grace
                EMPTY_LAST_SWEEP.remove(ck);
                if (w.removePluginChunkTicket(c.getX(), c.getZ(), ctx.plugin())) { /* ours, now released */ }
                if (w.unloadChunk(c.getX(), c.getZ(), true)) freed++;
            }
        }
        return freed;
    }

    private static void refresh(GadgetContext ctx) {
        List<Spot> here = spots(ctx);
        Set<String> want = new HashSet<String>();
        for (Spot s : here) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    want.add(key(s.world, s.cx + dx, s.cz + dz));
                }
            }
        }
        for (String k : want) {
            if (HELD.contains(k)) continue;
            String[] p = k.split(":");
            World w = ctx.server().getWorld(p[0]);
            if (w == null) continue;
            w.addPluginChunkTicket(Integer.parseInt(p[1]), Integer.parseInt(p[2]), ctx.plugin());
            HELD.add(k);
        }
        List<String> drop = new ArrayList<String>();
        for (String k : HELD) if (!want.contains(k)) drop.add(k);
        for (String k : drop) {
            String[] p = k.split(":");
            World w = ctx.server().getWorld(p[0]);
            if (w != null) w.removePluginChunkTicket(Integer.parseInt(p[1]), Integer.parseInt(p[2]), ctx.plugin());
            HELD.remove(k);
        }
        lastHeld = HELD.size();
        releaseEmpty(ctx, 200);
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status")) {
            List<Spot> here = spots(ctx);
            int loaded = 0;
            JsonArray cold = new JsonArray();
            Set<String> chunks = new HashSet<String>();
            for (Spot s : here) {
                chunks.add(key(s.world, s.cx, s.cz));
                if (s.loaded) loaded++;
                else {
                    JsonObject o = new JsonObject();
                    o.addProperty("npcId", s.npcId);
                    o.addProperty("chunk", s.cx + "," + s.cz);
                    cold.add(o);
                }
            }
            // how much of the neighbourhood around each cluster is actually loaded -
            // this is what decides how big a patch of map appears around a group
            int probe = args.has("probeRadius") ? args.get("probeRadius").getAsInt() : 4;
            JsonArray coverage = new JsonArray();
            Set<String> seen = new HashSet<String>();
            for (Spot s : here) {
                String ck = key(s.world, s.cx, s.cz);
                if (!seen.add(ck)) continue;
                World w = ctx.server().getWorld(s.world);
                if (w == null) continue;
                int on = 0;
                int total = 0;
                for (int dx = -probe; dx <= probe; dx++) {
                    for (int dz = -probe; dz <= probe; dz++) {
                        total++;
                        if (w.isChunkLoaded(s.cx + dx, s.cz + dz)) on++;
                    }
                }
                JsonObject o = new JsonObject();
                o.addProperty("npcId", s.npcId);
                o.addProperty("chunk", s.cx + "," + s.cz);
                o.addProperty("loadedNearby", on + "/" + total);
                coverage.add(o);
            }

            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("npcs", here.size());
            out.addProperty("distinctChunks", chunks.size());
            out.addProperty("probeRadiusChunks", probe);
            out.add("neighbourhoodCoverage", coverage);
            out.addProperty("npcsInLoadedChunks", loaded);
            out.addProperty("npcsInUnloadedChunks", here.size() - loaded);
            out.addProperty("ticketsHeld", HELD.size());
            out.add("unloaded", cold);
            // holding chunks open costs the server, so report what it is costing
            int loadedTotal = 0;
            for (World w : ctx.server().getWorlds()) loadedTotal += w.getLoadedChunks().length;
            out.addProperty("loadedChunksAllWorlds", loadedTotal);
            double[] tps = ctx.server().getTPS();
            out.addProperty("tps1m", Math.round(tps[0] * 100.0) / 100.0);
            out.addProperty("tps15m", Math.round(tps[2] * 100.0) / 100.0);
            return out;
        }

        if (action.equals("stop")) {
            generation(ctx, true);   // any timer still running is now stale and will self-cancel
            int killed = reap(ctx);
            if (TASK_ID != null) { ctx.cancelTask(TASK_ID.intValue()); TASK_ID = null; }
            for (String k : new ArrayList<String>(HELD)) {
                String[] p = k.split(":");
                World w = ctx.server().getWorld(p[0]);
                if (w != null) w.removePluginChunkTicket(Integer.parseInt(p[1]), Integer.parseInt(p[2]), ctx.plugin());
            }
            HELD.clear();
            JsonObject out = new JsonObject();
            out.addProperty("stopped", true);
            out.addProperty("staleTimersCancelled", killed);
            return out;
        }

        // starting always supersedes whatever was running, including an orphan left by
        // an earlier definition of this gadget
        final int myGen = generation(ctx, true);
        int killed = reap(ctx);
        if (TASK_ID != null) { ctx.cancelTask(TASK_ID.intValue()); TASK_ID = null; }
        radius = args.has("radius") ? args.get("radius").getAsInt() : 1;
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 100;
        refresh(ctx);
        TASK_ID = Integer.valueOf(ctx.runTimer(period, new Runnable() {
            public void run() {
                try {
                    if (generation(ctx, false) != myGen) { reap(ctx); return; }
                    refresh(ctx);
                } catch (Throwable ignored) { }
            }
        }));
        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("generation", myGen);
        out.addProperty("staleTimersCancelled", killed);
        out.addProperty("radiusChunks", radius);
        out.addProperty("periodTicks", period);
        out.addProperty("ticketsHeld", lastHeld);
        return out;
    }
}
