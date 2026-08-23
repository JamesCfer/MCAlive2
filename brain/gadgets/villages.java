package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.StructureSearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Villages: the bookkeeping side. Who found what, who founded what, who belongs where.
 * The walking, building, paying and sleeping are jobs in gadget:people; this gadget
 * keeps the map honest and hands people reasons.
 *
 *   FINDING    A person within sight of a generated village (the empty ones - nobody
 *              lives in them, by the world's rules) discovers it. It becomes a known
 *              place, and the explorer's curiosity is fed.
 *   FOUNDING   Two ways. Claim a found ruin by standing in it with a bench and a
 *              neighbour; or, out in the open, a person with a field and a bench and two
 *              neighbours within earshot founds one where they stand. Founders name it.
 *   JOINING    Anyone who lingers near a village for a couple of minutes is a member and
 *              calls it home. Members are what the village counts as its people.
 *   INN        A village without an inn wants one. A member with the timber and some
 *              building skill is told so, and builds it. The inn's chest is the village
 *              store - its market and its till.
 *
 * Records live in the places ledger as kind:"village":
 *   { id, name, kind, origin{x,y,z}, founder, members[], founded, inn{x,y,z}|null,
 *     store{x,y,z}|null, beds }
 */
public class Villages implements GadgetContract {

    private static Integer TASK_ID = null;
    private static int beats = 0;
    private static long seed = 777L;
    private static String lastError = null;
    /** beats each person has spent near each village, for joining */
    private static final Map<String, Integer> LINGER = new HashMap<String, Integer>();

    private static int rand(int n) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        int v = (int) ((seed >>> 33) % n);
        return v < 0 ? -v : v;
    }

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("villages-generation");
        Integer cur = pdc.get(k, org.bukkit.persistence.PersistentDataType.INTEGER);
        int g = cur == null ? 0 : cur.intValue();
        if (bump) { g = g + 1; pdc.set(k, org.bukkit.persistence.PersistentDataType.INTEGER, Integer.valueOf(g)); }
        return g;
    }

    private static int reap(GadgetContext ctx) {
        int killed = 0;
        for (org.bukkit.scheduler.BukkitTask t : ctx.server().getScheduler().getPendingTasks()) {
            if (t.getOwner() != ctx.plugin()) continue;
            Object inner = runnableOf(t);
            if (inner != null && inner.getClass().getName().contains("Villages")) { t.cancel(); killed++; }
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

    // ------------------------------------------------------------------ ledger

    private static List<JsonObject> query(GadgetContext ctx, String collection) throws Exception {
        JsonObject q = new JsonObject();
        q.addProperty("collection", collection);
        JsonArray recs = ctx.invoke("ledger_query", q).getAsJsonArray("records");
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonElement e : recs) out.add(e.getAsJsonObject());
        return out;
    }

    private static void put(GadgetContext ctx, String collection, JsonObject rec) throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("collection", collection);
        p.add("record", rec);
        ctx.invoke("ledger_put", p);
    }

    private static String gets(JsonObject o, String k, String d) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
    }

    private static int geti(JsonObject o, String k, int d) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : d;
    }

    private static JsonArray arr(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) o.add(k, new JsonArray());
        return o.getAsJsonArray(k);
    }

    private static boolean alive(JsonObject rec) {
        return !rec.has("alive") || rec.get("alive").getAsBoolean();
    }

    private static JsonObject xyz(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    private static double dist(JsonObject origin, Location at) {
        return Math.sqrt(Math.pow(geti(origin, "x", 0) - at.getX(), 2) + Math.pow(geti(origin, "z", 0) - at.getZ(), 2));
    }

    private static boolean hasMember(JsonObject v, String id) {
        for (JsonElement e : arr(v, "members")) if (id.equals(e.getAsString())) return true;
        return false;
    }

    private static int count(JsonObject rec, String item) {
        int n = 0;
        for (JsonElement e : arr(rec, "inventory")) {
            JsonObject s = e.getAsJsonObject();
            if (item.equals(gets(s, "item", ""))) n += geti(s, "count", 0);
        }
        return n;
    }

    private static int countEnding(JsonObject rec, String suffix) {
        int n = 0;
        for (JsonElement e : arr(rec, "inventory")) {
            JsonObject s = e.getAsJsonObject();
            if (gets(s, "item", "").endsWith(suffix)) n += geti(s, "count", 0);
        }
        return n;
    }

    private static int skill(JsonObject rec, String name) {
        if (!rec.has("skills") || !rec.get("skills").isJsonObject()) return 0;
        JsonObject s = rec.getAsJsonObject("skills");
        if (!s.has(name) || !s.get(name).isJsonObject()) return 0;
        return geti(s.getAsJsonObject(name), "points", 0);
    }

    // ------------------------------------------------------------------ names

    private static final String[] SUFFIX = { "ford", "stead", "hollow", "rest", "cross", "field", "wick", "moor", "dale", "bridge" };

    private static String nameFor(String founder, List<JsonObject> places) {
        for (int i = 0; i < 20; i++) {
            String n = founder + SUFFIX[rand(SUFFIX.length)];
            n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
            boolean taken = false;
            for (JsonObject p : places) if (n.equalsIgnoreCase(gets(p, "name", ""))) taken = true;
            if (!taken) return n;
        }
        return founder + "'s camp";
    }

    // ------------------------------------------------------------------ the work

    private static JsonObject villageAt(List<JsonObject> places, Location at, double within) {
        JsonObject best = null;
        double bestD = within;
        for (JsonObject p : places) {
            if (!"village".equals(gets(p, "kind", ""))) continue;
            double d = dist(p.getAsJsonObject("origin"), at);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private JsonObject found(GadgetContext ctx, List<JsonObject> places, JsonObject rec, Location at, String how) throws Exception {
        String founder = gets(rec, "id", "someone");
        String name = nameFor(gets(rec, "name", founder), places);
        JsonObject v = new JsonObject();
        v.addProperty("id", "village-" + name.toLowerCase().replaceAll("[^a-z0-9]", ""));
        v.addProperty("name", name);
        v.addProperty("kind", "village");
        int y = at.getWorld().getHighestBlockYAt(at.getBlockX(), at.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        v.add("origin", xyz(at.getBlockX(), y, at.getBlockZ()));
        v.addProperty("founder", founder);
        JsonArray members = new JsonArray();
        members.add(founder);
        v.add("members", members);
        v.addProperty("founded", java.time.Instant.now().toString());
        v.addProperty("how", how);
        v.addProperty("builtBy", "npc");
        v.addProperty("description", name + ", founded by " + gets(rec, "name", founder) + " (" + how + ").");
        v.addProperty("beds", 0);
        put(ctx, "places", v);
        rec.addProperty("village", v.get("id").getAsString());
        JsonObject ev = new JsonObject();
        ev.addProperty("village", v.get("id").getAsString());
        ev.addProperty("name", name);
        ev.addProperty("founder", founder);
        ev.addProperty("how", how);
        ctx.plugin().bridge().broadcastEvent("village_founded", ev);
        return v;
    }

    private void beat(GadgetContext ctx) {
        beats++;
        try {
            NpcManager npcs = ctx.plugin().npcManager();
            List<JsonObject> people = query(ctx, "npcs");
            List<JsonObject> places = query(ctx, "places");
            World w = ctx.world(null);

            // where everyone is
            Map<String, Location> where = new HashMap<String, Location>();
            for (JsonObject rec : people) {
                if (!rec.has("id") || !alive(rec)) continue;
                NpcData d = npcs.get(rec.get("id").getAsString());
                Entity e = d == null || d.dead ? null : npcs.resolveEntity(d);
                if (e != null) where.put(d.id, e.getLocation());
            }

            for (JsonObject rec : people) {
                String id = gets(rec, "id", null);
                Location at = id == null ? null : where.get(id);
                if (at == null) continue;
                boolean changed = false;

                // --- finding: a generated village within sight
                if (beats % 6 == 0) {
                    StructureSearchResult r = null;
                    Structure[] kinds = { Structure.VILLAGE_PLAINS, Structure.VILLAGE_DESERT, Structure.VILLAGE_SAVANNA, Structure.VILLAGE_SNOWY, Structure.VILLAGE_TAIGA };
                    for (Structure kind : kinds) {
                        try {
                            StructureSearchResult rr = w.locateNearestStructure(at, kind, 6, false);
                            if (rr != null && rr.getLocation() != null && (r == null || rr.getLocation().distance(at) < r.getLocation().distance(at))) r = rr;
                        } catch (Throwable ignored) { }
                    }
                    if (r != null && r.getLocation() != null && r.getLocation().distance(at) <= 96) {
                        Location vl = r.getLocation();
                        String rid = "ruin-" + (vl.getBlockX() >> 4) + "-" + (vl.getBlockZ() >> 4);
                        boolean known = false;
                        for (JsonObject p : places) if (rid.equals(gets(p, "id", ""))) known = true;
                        if (!known) {
                            JsonObject ruin = new JsonObject();
                            ruin.addProperty("id", rid);
                            ruin.addProperty("name", "the empty village near " + vl.getBlockX() + "," + vl.getBlockZ());
                            ruin.addProperty("kind", "ruin");
                            ruin.add("origin", xyz(vl.getBlockX(), vl.getBlockY(), vl.getBlockZ()));
                            ruin.addProperty("discoveredBy", id);
                            ruin.addProperty("builtBy", "world");
                            ruin.addProperty("description", "Houses and lanes with nobody in them. Found by " + gets(rec, "name", id) + ".");
                            put(ctx, "places", ruin);
                            places.add(ruin);
                            JsonObject need = rec.has("need") && rec.get("need").isJsonObject() ? rec.getAsJsonObject("need") : null;
                            if (need != null && "explore".equals(gets(need, "kind", ""))) {
                                need.addProperty("value", Math.min(20, geti(need, "value", 10) + 8));
                                changed = true;
                            }
                            JsonObject ev = new JsonObject();
                            ev.addProperty("npcId", id);
                            ev.addProperty("place", rid);
                            ev.addProperty("x", vl.getBlockX());
                            ev.addProperty("z", vl.getBlockZ());
                            ctx.plugin().bridge().broadcastEvent("village_found", ev);
                        }
                    }
                }

                JsonObject village = villageAt(places, at, 48);

                // --- founding
                if (village == null && beats % 6 == 3) {
                    int neighbours = 0;
                    for (Map.Entry<String, Location> o : where.entrySet()) {
                        if (o.getKey().equals(id)) continue;
                        if (o.getValue().distance(at) <= 32) neighbours++;
                    }
                    boolean bench = count(rec, "CRAFTING_TABLE") > 0 || benchNear(at);
                    boolean field = rec.has("field");
                    JsonObject ruin = null;
                    for (JsonObject p : places) {
                        if (!"ruin".equals(gets(p, "kind", ""))) continue;
                        if (dist(p.getAsJsonObject("origin"), at) <= 40) ruin = p;
                    }
                    if (ruin != null && bench && neighbours >= 1) {
                        village = found(ctx, places, rec, at, "claimed the empty village");
                        ruin.addProperty("kind", "claimed");
                        ruin.addProperty("claimedBy", village.get("id").getAsString());
                        put(ctx, "places", ruin);
                        places.add(village);
                        changed = true;
                    } else if (field && bench && neighbours >= 2) {
                        village = found(ctx, places, rec, at, "settled the open land");
                        places.add(village);
                        changed = true;
                    }
                }

                // --- joining: linger and you belong
                if (village != null && !hasMember(village, id)) {
                    String key = id + "@" + gets(village, "id", "");
                    Integer n = LINGER.get(key);
                    int v = (n == null ? 0 : n.intValue()) + 1;
                    LINGER.put(key, Integer.valueOf(v));
                    if (v >= 24) {                       // two minutes at a 5 s beat
                        arr(village, "members").add(id);
                        put(ctx, "places", village);
                        rec.addProperty("village", gets(village, "id", ""));
                        JsonObject home = new JsonObject();
                        JsonObject o = village.getAsJsonObject("origin");
                        home.addProperty("world", w.getName());
                        home.addProperty("x", geti(o, "x", 0));
                        home.addProperty("y", geti(o, "y", 64) + 1);
                        home.addProperty("z", geti(o, "z", 0));
                        rec.add("home", home);
                        NpcData d = npcs.get(id);
                        if (d != null) { d.home = new Location(w, geti(o, "x", 0), geti(o, "y", 64) + 1, geti(o, "z", 0)); npcs.save(); }
                        changed = true;
                        JsonObject ev = new JsonObject();
                        ev.addProperty("npcId", id);
                        ev.addProperty("village", gets(village, "id", ""));
                        ctx.plugin().bridge().broadcastEvent("village_joined", ev);
                        LINGER.remove(key);
                    }
                }

                // --- an inn wanted: tell a capable member, via a standing request on their sheet
                if (village != null && hasMember(village, id) && !village.has("inn")) {
                    int timber = countEnding(rec, "_LOG") * 4 + countEnding(rec, "_PLANKS");
                    boolean builder = skill(rec, "building") >= 1 || timber >= 60;
                    long noBuildUntil = rec.has("noBuildUntil") && !rec.get("noBuildUntil").isJsonNull() ? rec.get("noBuildUntil").getAsLong() : 0L;
                    if (builder && timber >= 85 && !rec.has("asked") && System.currentTimeMillis() > noBuildUntil) {
                        JsonObject ask = new JsonObject();
                        ask.addProperty("kind", "build_inn");
                        ask.addProperty("village", gets(village, "id", ""));
                        JsonObject o = village.getAsJsonObject("origin");
                        ask.add("at", xyz(geti(o, "x", 0) + 6, geti(o, "y", 64), geti(o, "z", 0)));
                        rec.add("asked", ask);
                        changed = true;
                    }
                }

                if (changed) put(ctx, "npcs", rec);
            }
            lastError = null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
    }

    private static boolean benchNear(Location at) {
        World w = at.getWorld();
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) for (int dy = -2; dy <= 2; dy++) {
            if (w.getBlockAt(x + dx, y + dy, z + dz).getType() == Material.CRAFTING_TABLE) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ entry point

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("beats", beats);
            out.addProperty("lastError", lastError);
            JsonArray vs = new JsonArray();
            for (JsonObject p : query(ctx, "places")) {
                String kind = gets(p, "kind", "");
                if (!kind.equals("village") && !kind.equals("ruin") && !kind.equals("claimed")) continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", gets(p, "id", ""));
                o.addProperty("name", gets(p, "name", ""));
                o.addProperty("kind", kind);
                o.add("origin", p.get("origin"));
                if (p.has("members")) o.addProperty("members", p.getAsJsonArray("members").size());
                o.addProperty("inn", p.has("inn"));
                o.addProperty("beds", geti(p, "beds", 0));
                vs.add(o);
            }
            out.add("places", vs);
            return out;
        }

        // Mark an inn as built (called by people's build job when the last block is placed).
        if (action.equals("inn_built")) {
            String vid = args.get("village").getAsString();
            for (JsonObject p : query(ctx, "places")) {
                if (!vid.equals(gets(p, "id", ""))) continue;
                p.add("inn", args.getAsJsonObject("at"));
                p.add("store", args.getAsJsonObject("chest"));
                p.addProperty("beds", geti(args, "beds", 2));
                put(ctx, "places", p);
                JsonObject ev = new JsonObject();
                ev.addProperty("village", vid);
                ev.addProperty("builder", gets(args, "builder", "?"));
                ctx.plugin().bridge().broadcastEvent("inn_built", ev);
                JsonObject out = new JsonObject();
                out.addProperty("ok", true);
                return out;
            }
            throw new IllegalArgumentException("no village " + vid);
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
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 100;
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
        out.addProperty("periodTicks", period);
        return out;
    }
}
