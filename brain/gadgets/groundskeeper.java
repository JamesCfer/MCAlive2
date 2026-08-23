package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Self-healing for NPCs that end up somewhere they cannot stand.
 *
 * The console's world model reports "off-ground" NPCs but nothing ever acts on it: those
 * diagnostics are built for the human map and are deliberately kept out of the director's
 * prompt, and no event is raised for them, so a buried NPC stays buried forever. This is
 * the missing repair loop.
 *
 * Detection uses Bukkit's own {@code isPassable()} rather than guessing from a material
 * name, so an NPC standing in a grass tuft or a flower is NOT a fault - only one actually
 * encased in rock, or floating with nothing under it, gets moved. Repair re-seats it with
 * NpcManager.safeStanding (the same resolver a spawn uses), falling back to its recorded
 * home and then to the surface above it.
 *
 * Actions: "start", "stop", "status" (a dry report - finds faults, changes nothing),
 * "sweep" (one repair pass now), "places" (lift buried place records onto the surface).
 */
public class Groundskeeper implements GadgetContract {

    private static Integer TASK_ID = null;
    private static int repaired = 0;
    private static int sweeps = 0;
    private static final List<String> LOG = new ArrayList<String>();

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("groundskeeper-generation");
        Integer cur = pdc.get(k, org.bukkit.persistence.PersistentDataType.INTEGER);
        int g = cur == null ? 0 : cur.intValue();
        if (bump) {
            g = g + 1;
            pdc.set(k, org.bukkit.persistence.PersistentDataType.INTEGER, Integer.valueOf(g));
        }
        return g;
    }

    private static int reap(GadgetContext ctx) {
        int killed = 0;
        for (org.bukkit.scheduler.BukkitTask t : ctx.server().getScheduler().getPendingTasks()) {
            if (t.getOwner() != ctx.plugin()) continue;
            Object inner = runnableOf(t);
            if (inner == null) continue;
            if (inner.getClass().getName().contains("Groundskeeper")) { t.cancel(); killed++; }
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

    /**
     * How far the real GROUND of this column sits above the NPC. Uses the OCEAN_FLOOR
     * heightmap, not the default one: the default counts leaves, so every NPC standing
     * happily under a forest canopy would read as eight blocks underground.
     */
    private static int depthBelowSurface(World w, Location loc) {
        int ground = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ(),
                org.bukkit.HeightMap.OCEAN_FLOOR);
        return ground - loc.getBlockY();
    }

    /** null when the NPC is fine, otherwise why it cannot stand where it is. */
    private static String fault(World w, Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        // isPassable() is the authority: grass tufts, flowers and torches are passable and
        // are NOT a fault, however solid their material name looks
        if (!feet.isPassable() && !head.isPassable()) return "encased";
        if (!feet.isPassable()) return "feet_in_block";
        boolean grounded = false;
        for (int d = 1; d <= 4; d++) {
            if (!w.getBlockAt(x, y - d, z).isPassable()) { grounded = true; break; }
        }
        if (!grounded) return "floating";
        // Standing safely, but in a cave well under its own ground: the step-walker can
        // descend three blocks at a time and only climb one, so an NPC that slips
        // underground can never walk back out. Left alone it just sinks further.
        //
        // Depth alone is not enough - an NPC at the foot of a cliff has solid hill in the
        // column above it and would read as buried. A real cave has no daylight, so
        // require both.
        if (depthBelowSurface(w, loc) > 6 && head.getLightFromSky() == 0) return "stranded_underground";
        return null;
    }

    /** True while this NPC is on a mining shift, where being underground is the point. */
    private static boolean isMining(GadgetContext ctx, String npcId) {
        try {
            JsonObject a = new JsonObject();
            a.addProperty("npcId", npcId);
            a.addProperty("action", "status");
            JsonObject call = new JsonObject();
            call.addProperty("id", "mine");
            call.add("args", a);
            JsonObject r = ctx.invoke("gadget_run", call);
            return r.has("running") && r.get("running").getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final java.util.Map<String, Integer> OFFENDERS = new java.util.HashMap<String, Integer>();

    /**
     * Plug the hole an NPC keeps falling down. Rescuing alone only treadmills: its patrol
     * walks it back to the same cave mouth every time, so after a few repeats we fill the
     * open column it fell through, from where it was found back up to daylight. Repeated
     * over a few falls this closes the mouth for good and the repairs stop.
     */
    private static int sealHole(World w, Location at) {
        int x = at.getBlockX();
        int z = at.getBlockZ();
        int ground = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.OCEAN_FLOOR);
        int filled = 0;
        for (int y = at.getBlockY(); y < ground && filled < 24; y++) {
            Block b = w.getBlockAt(x, y, z);
            if (!b.isPassable()) continue;
            if (b.getType() == org.bukkit.Material.WATER || b.getType() == org.bukkit.Material.LAVA) continue;
            b.setType(org.bukkit.Material.STONE);
            filled++;
        }
        return filled;
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

    /**
     * Dig out, the way a player would: break the column overhead until daylight, then
     * walk home. Nobody is lifted out of a hole by the hand of god any more.
     */
    private static int digOut(World w, Location loc) {
        int x = loc.getBlockX(), z = loc.getBlockZ();
        int ground = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.OCEAN_FLOOR);
        int broke = 0;
        for (int y = loc.getBlockY() + 1; y <= ground + 1 && broke < 200; y++) {
            Block b = w.getBlockAt(x, y, z);
            if (b.isPassable()) continue;
            if (b.getType() == org.bukkit.Material.BEDROCK) break;
            b.setType(org.bukkit.Material.AIR);
            broke++;
        }
        return broke;
    }

    private static void note(String s) {
        LOG.add(s);
        while (LOG.size() > 15) LOG.remove(0);
    }

    /** One pass. When dryRun, only reports what it would move. */
    private static JsonArray pass(GadgetContext ctx, boolean dryRun) {
        JsonArray found = new JsonArray();
        NpcManager npcs = ctx.plugin().npcManager();
        List<NpcData> all = npcs.all();
        for (NpcData d : all) {
            if (d.dead) continue;
            Entity e = npcs.resolveEntity(d);
            if (e == null) continue;
            Location loc = e.getLocation();
            World w = loc.getWorld();
            if (w == null) continue;
            String why = fault(w, loc);
            if (why == null) continue;
            // a miner down its own shaft is working, not lost
            if (isMining(ctx, d.id)) continue;

            JsonObject o = new JsonObject();
            o.addProperty("npcId", d.id);
            o.addProperty("name", d.name);
            o.addProperty("fault", why);
            o.addProperty("at", loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());

            if (!dryRun) {
                // Lost badly (buried, or stranded in a cave it cannot climb out of) -> go
                // home, because the nearest standable spot is usually just deeper in the
                // same cave. Minor faults are corrected in place.
                boolean lost = why.equals("encased") || why.equals("stranded_underground");
                Location target = null;
                if (lost && d.home != null && d.home.getWorld() != null) {
                    try {
                        Location h = npcs.safeStanding(d.home.clone());
                        if (fault(h.getWorld(), h) == null) target = h;
                    } catch (Throwable ignored) { }
                }
                if (target == null) {
                    try {
                        Location s = npcs.safeStanding(loc);
                        if (fault(w, s) == null) target = s;
                    } catch (Throwable ignored) { }
                }
                if (target == null && d.home != null && d.home.getWorld() != null) {
                    try {
                        Location h = npcs.safeStanding(d.home.clone());
                        if (fault(h.getWorld(), h) == null) target = h;
                    } catch (Throwable ignored) { }
                }
                if (target == null) {
                    // last resort: straight up onto the surface of this column
                    int top = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ(), org.bukkit.HeightMap.OCEAN_FLOOR);
                    target = new Location(w, loc.getBlockX() + 0.5, top + 1, loc.getBlockZ() + 0.5,
                            loc.getYaw(), 0f);
                }
                Integer prev = OFFENDERS.get(d.id);
                int times = (prev == null ? 0 : prev.intValue()) + 1;
                OFFENDERS.put(d.id, Integer.valueOf(times));
                if (lost && times >= 3) {
                    int filled = sealHole(w, loc);
                    if (filled > 0) {
                        o.addProperty("sealedBlocks", filled);
                        note("sealed the hole " + d.name + " kept falling down (" + filled + " blocks)");
                    }
                }

                // walk out if there is a way; otherwise cut a shaft to the surface and
                // then walk - a rescue an actual player could have performed
                // Un-clip first. Being embedded in a block is not somewhere a player can
                // walk out of either - the server pushes them clear - so lift straight up
                // to the nearest spot that can be stood in. It is a nudge of a few blocks
                // in place, not a journey.
                if (why.equals("feet_in_block") || why.equals("floating")) {
                    for (int lift = 0; lift <= 4; lift++) {
                        Location try_ = loc.clone();
                        try_.setY(loc.getY() + lift);
                        if (fault(w, try_) == null) { e.teleport(try_); o.addProperty("unclipped", lift); break; }
                        Location down = loc.clone();
                        down.setY(loc.getY() - lift);
                        if (fault(w, down) == null) { e.teleport(down); o.addProperty("settled", -lift); break; }
                    }
                }
                boolean walked = walkTo(ctx, d.id, target.getBlockX(), target.getBlockY(), target.getBlockZ());
                if (!walked) {
                    int cut = digOut(w, loc);
                    o.addProperty("dugOut", cut);
                    walked = walkTo(ctx, d.id, target.getBlockX(), target.getBlockY(), target.getBlockZ());
                }
                o.addProperty("walkedOut", walked);
                repaired++;
                o.addProperty("timesRepaired", times);
                o.addProperty("movedTo", target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ());
                note(d.name + " (" + why + ") -> " + target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ());
                JsonObject ev = new JsonObject();
                ev.addProperty("npcId", d.id);
                ev.addProperty("fault", why);
                ev.addProperty("movedTo", target.getBlockX() + "," + target.getBlockY() + "," + target.getBlockZ());
                ctx.plugin().bridge().broadcastEvent("npc_reseated", ev);
            }
            found.add(o);
        }
        if (!dryRun) {
            sweeps++;
            npcs.save();
            // no longer nudges behavior programs: every resume re-raised
            // behavior_blocked, which woke the director and spent tokens
        }
        return found;
    }

    private static final java.util.Map<String, Integer> RESUMES = new java.util.HashMap<String, Integer>();
    private static final int MAX_RESUMES = 3;

    /**
     * Retry paused behavior programs. Most blocks are transient - an NPC that was stuck
     * has just been re-seated, and the walk succeeds on the next try.
     *
     * Strictly capped: every block re-raises behavior_blocked, which WAKES THE DIRECTOR
     * and spends tokens, so a program that will not recover is left alone after a few
     * attempts rather than spamming the brain forever.
     */
    private static void nudgeBlockedPrograms(GadgetContext ctx) {
        try {
            JsonObject st = ctx.invoke("behavior_status", new JsonObject());
            JsonArray programs = st.getAsJsonArray("programs");
            for (JsonElement el : programs) {
                JsonObject p = el.getAsJsonObject();
                if (!p.has("paused") || !p.get("paused").getAsBoolean()) continue;
                String id = p.get("id").getAsString();
                Integer prev = RESUMES.get(id);
                int tries = prev == null ? 0 : prev.intValue();
                if (tries >= MAX_RESUMES) continue;
                RESUMES.put(id, Integer.valueOf(tries + 1));
                JsonObject a = new JsonObject();
                a.addProperty("id", id);
                ctx.invoke("behavior_resume", a);
                note("resumed blocked program " + id + " (attempt " + (tries + 1) + "/" + MAX_RESUMES + ")");
            }
        } catch (Throwable ignored) { }
    }

    /** Lift place records whose origin sits below the real surface back onto it. */
    private JsonArray fixPlaces(GadgetContext ctx, boolean dryRun) throws Exception {
        JsonArray out = new JsonArray();
        JsonObject q = new JsonObject();
        q.addProperty("collection", "places");
        JsonObject res = ctx.invoke("ledger_query", q);
        JsonArray records = res.getAsJsonArray("records");
        for (JsonElement el : records) {
            JsonObject rec = el.getAsJsonObject();
            if (!rec.has("origin") || !rec.get("origin").isJsonObject()) continue;
            JsonObject origin = rec.getAsJsonObject("origin");
            if (!origin.has("x") || !origin.has("y") || !origin.has("z")) continue;
            int x = origin.get("x").getAsInt();
            int y = origin.get("y").getAsInt();
            int z = origin.get("z").getAsInt();
            World w = ctx.world(origin.has("world") && !origin.get("world").isJsonNull()
                    ? origin.get("world").getAsString() : null);
            w.getChunkAt(x >> 4, z >> 4).load();
            int surface = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.OCEAN_FLOOR);
            if (y >= surface - 2) continue;   // sitting at or near the surface: fine
            JsonObject o = new JsonObject();
            o.addProperty("place", rec.has("id") ? rec.get("id").getAsString() : "?");
            o.addProperty("was", y);
            o.addProperty("surface", surface);
            if (!dryRun) {
                origin.addProperty("y", surface + 1);
                JsonObject put = new JsonObject();
                put.addProperty("collection", "places");
                put.add("record", rec);
                ctx.invoke("ledger_put", put);
                o.addProperty("liftedTo", surface + 1);
            }
            out.add(o);
        }
        return out;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("sweeps", sweeps);
            out.addProperty("repairedTotal", repaired);
            out.add("faultsRightNow", pass(ctx, true));
            JsonArray l = new JsonArray();
            for (String s : LOG) l.add(s);
            out.add("recent", l);
            return out;
        }
        if (action.equals("rehome")) {
            // A home that is itself under an overhang or in a cave makes rescue a
            // treadmill: every sweep sends the NPC back to a spot that is still at
            // fault. Lift such homes onto the real surface of their own column.
            boolean dry = args.has("dryRun") && args.get("dryRun").getAsBoolean();
            JsonArray out = new JsonArray();
            NpcManager npcs = ctx.plugin().npcManager();
            for (NpcData d : npcs.all()) {
                if (d.home == null || d.home.getWorld() == null) continue;
                World w = d.home.getWorld();
                w.getChunkAt(d.home.getBlockX() >> 4, d.home.getBlockZ() >> 4).load();
                int depth = depthBelowSurface(w, d.home);
                if (depth <= 6) continue;
                int ground = w.getHighestBlockYAt(d.home.getBlockX(), d.home.getBlockZ(),
                        org.bukkit.HeightMap.OCEAN_FLOOR);
                JsonObject o = new JsonObject();
                o.addProperty("npcId", d.id);
                o.addProperty("homeWas", d.home.getBlockY());
                o.addProperty("groundHere", ground);
                if (!dry) {
                    Location lifted = new Location(w, d.home.getBlockX() + 0.5, ground + 1,
                            d.home.getBlockZ() + 0.5);
                    d.home = lifted.clone();
                    if (d.work != null && depthBelowSurface(w, d.work) > 6) d.work = lifted.clone();
                    o.addProperty("homeNow", ground + 1);
                }
                out.add(o);
            }
            if (!dry) npcs.save();
            JsonObject res = new JsonObject();
            res.add("rehomed", out);
            return res;
        }
        if (action.equals("places")) {
            boolean dry = args.has("dryRun") && args.get("dryRun").getAsBoolean();
            JsonObject out = new JsonObject();
            out.add("places", fixPlaces(ctx, dry));
            return out;
        }
        if (action.equals("sweep")) {
            JsonObject out = new JsonObject();
            out.add("repaired", pass(ctx, false));
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
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 600;
        JsonArray first = pass(ctx, false);
        TASK_ID = Integer.valueOf(ctx.runTimer(period, new Runnable() {
            public void run() {
                try {
                    if (generation(ctx, false) != myGen) { reap(ctx); return; }
                    pass(ctx, false);
                } catch (Throwable ignored) { }
            }
        }));
        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("generation", myGen);
        out.addProperty("staleTimersCancelled", killed);
        out.addProperty("periodTicks", period);
        out.add("repairedOnFirstPass", first);
        return out;
    }
}
