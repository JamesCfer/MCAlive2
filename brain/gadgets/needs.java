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
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What every NPC wants, as numbers.
 *
 * Eight drives on the same 0-20 scale hunger already uses, written onto each ledger
 * record so the map can show them and the chooser can weigh them. They come in two
 * kinds, and the difference matters:
 *
 *   DRIVES accumulate. Fatigue, purpose and curiosity fall with time and are refilled by
 *   doing something about them - they carry memory of how the day has gone.
 *
 *   CONDITIONS are read from the world every beat. Safety, shelter, belonging and wealth
 *   are not stored moods but facts: how dark it is here, whether there is a roof, who
 *   else is standing nearby, what the store holds. Reading them fresh means they can
 *   never drift out of step with the world.
 *
 * Nothing here decides anything. This is the sense organ; the chooser is separate.
 */
public class Needs implements GadgetContract {

    private static Integer TASK_ID = null;
    private static int beats = 0;
    private static final Map<String, JsonObject> STORES = new HashMap<String, JsonObject>();
    /** Chunks each NPC has already seen, so curiosity is fed by new ground only. */
    private static final Map<String, Set<Long>> SEEN = new HashMap<String, Set<Long>>();

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("needs-generation");
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
            if (inner != null && inner.getClass().getName().contains("Needs")) { t.cancel(); killed++; }
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

    private static int clamp(double v) {
        if (v < 0) return 0;
        if (v > 20) return 20;
        return (int) Math.round(v);
    }

    private static Inventory store(GadgetContext ctx, String faction) {
        JsonObject c = faction == null ? null : STORES.get(faction);
        if (c == null) return null;
        World w = ctx.world(null);
        Block b = w.getBlockAt(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    /** A roof overhead and walls near enough to matter. */
    private static boolean sheltered(World w, Location at) {
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        boolean roof = false;
        for (int dy = 1; dy <= 5; dy++) {
            if (!w.getBlockAt(x, y + dy, z).isPassable()) { roof = true; break; }
        }
        if (!roof) return false;
        int walls = 0;
        for (int[] d : new int[][]{{3,0},{-3,0},{0,3},{0,-3}}) {
            if (!w.getBlockAt(x + d[0], y + 1, z + d[1]).isPassable()) walls++;
        }
        return walls >= 2;
    }

    private static int countFood(Inventory inv) {
        if (inv == null) return 0;
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null) continue;
            String t = s.getType().name();
            if (t.startsWith("COOKED_") || t.equals("BEEF") || t.equals("PORKCHOP") || t.equals("MUTTON")
                    || t.equals("CHICKEN") || t.equals("RABBIT") || t.equals("BREAD")
                    || t.equals("SWEET_BERRIES") || t.equals("CARROT") || t.equals("POTATO")) n += s.getAmount();
        }
        return n;
    }

    private static int countAll(Inventory inv) {
        if (inv == null) return 0;
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null) n += s.getAmount();
        }
        return n;
    }

    private void beat(GadgetContext ctx) throws Exception {
        beats++;
        NpcManager npcs = ctx.plugin().npcManager();
        World world = ctx.world(null);
        long time = world.getTime();
        boolean night = time > 13000 && time < 23000;

        JsonObject q = new JsonObject();
        q.addProperty("collection", "npcs");
        JsonArray records = ctx.invoke("ledger_query", q).getAsJsonArray("records");

        // who is standing near whom, for belonging
        Map<String, Location> where = new HashMap<String, Location>();
        for (JsonElement el : records) {
            JsonObject rec = el.getAsJsonObject();
            if (!rec.has("id")) continue;
            NpcData d = npcs.get(rec.get("id").getAsString());
            if (d == null || d.dead) continue;
            Entity e = npcs.resolveEntity(d);
            if (e != null) where.put(d.id, e.getLocation());
        }

        for (JsonElement el : records) {
            JsonObject rec = el.getAsJsonObject();
            if (!rec.has("id")) continue;
            String id = rec.get("id").getAsString();
            if (rec.has("alive") && !rec.get("alive").getAsBoolean()) continue;
            NpcData d = npcs.get(id);
            if (d == null || d.dead) continue;
            Location at = where.get(id);
            if (at == null) continue;
            String faction = rec.has("faction") && !rec.get("faction").isJsonNull()
                    ? rec.get("faction").getAsString() : null;
            Inventory inv = store(ctx, faction);

            JsonObject n = rec.has("needs") && rec.get("needs").isJsonObject()
                    ? rec.getAsJsonObject("needs") : new JsonObject();

            // ---- drives: they remember ----
            double fatigue = n.has("fatigue") ? n.get("fatigue").getAsDouble() : 20;
            double purpose = n.has("purpose") ? n.get("purpose").getAsDouble() : 14;
            double curiosity = n.has("curiosity") ? n.get("curiosity").getAsDouble() : 14;

            boolean resting = rec.has("activity") && !rec.get("activity").isJsonNull()
                    && rec.get("activity").getAsString().indexOf("rest") >= 0;
            boolean underRoof = sheltered(world, at);
            fatigue -= 1;
            if (resting) fatigue += underRoof ? 6 : 3;
            if (night && underRoof) fatigue += 1;

            purpose -= 0.5;                          // an idle day is a wasted one
            curiosity -= 0.5;

            // curiosity is fed by ground this NPC has not stood on before
            Set<Long> seen = SEEN.get(id);
            if (seen == null) { seen = new HashSet<Long>(); SEEN.put(id, seen); }
            long chunk = (((long) (at.getBlockX() >> 4)) << 32) ^ ((at.getBlockZ() >> 4) & 0xFFFFFFFFL);
            if (seen.add(chunk)) curiosity += 3;
            if (seen.size() > 4000) seen.clear();

            // ---- conditions: read from the world, never stored as mood ----
            int light = world.getBlockAt(at.getBlockX(), at.getBlockY() + 1, at.getBlockZ()).getLightLevel();
            int depth = world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ(), HeightMap.OCEAN_FLOOR) - at.getBlockY();
            double safety = 6 + light * 0.7;
            if (underRoof) safety += 5;
            if (depth > 6) safety -= 6;              // caves are not comforting
            if (night && light < 6) safety -= 4;

            int kin = 0;
            for (Map.Entry<String, Location> o : where.entrySet()) {
                if (o.getKey().equals(id)) continue;
                if (faction == null) break;
                if (!o.getValue().getWorld().equals(at.getWorld())) continue;
                if (o.getValue().distance(at) <= 28) kin++;
            }
            double belonging = Math.min(20, 4 + kin * 4.5);

            double shelterNeed = underRoof ? 20 : 6;
            if (!underRoof && d.home != null && at.distance(d.home) < 20) shelterNeed = 11;

            int stock = countAll(inv), food = countFood(inv);
            double wealth = Math.min(20, stock / 40.0);
            // a larder is not wealth, but an empty one is felt
            if (food == 0) wealth = Math.min(wealth, 8);

            JsonObject out = new JsonObject();
            out.addProperty("fatigue", clamp(fatigue));
            out.addProperty("purpose", clamp(purpose));
            out.addProperty("curiosity", clamp(curiosity));
            out.addProperty("safety", clamp(safety));
            out.addProperty("belonging", clamp(belonging));
            out.addProperty("shelter", clamp(shelterNeed));
            out.addProperty("wealth", clamp(wealth));
            out.addProperty("hunger", rec.has("hunger") && !rec.get("hunger").isJsonNull()
                    ? rec.get("hunger").getAsInt() : 20);
            rec.add("needs", out);

            JsonObject put = new JsonObject();
            put.addProperty("collection", "npcs");
            put.add("record", rec);
            ctx.invoke("ledger_put", put);
        }
    }

    /** Credit a drive when an action that serves it completes. Called by the chooser. */
    public static void credit(JsonObject rec, String need, double amount) {
        if (!rec.has("needs") || !rec.get("needs").isJsonObject()) return;
        JsonObject n = rec.getAsJsonObject("needs");
        double v = n.has(need) ? n.get(need).getAsDouble() : 10;
        n.addProperty(need, clamp(v + amount));
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("beats", beats);
            out.addProperty("lines", STORES.size());
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
        STORES.clear();
        if (args.has("stores") && args.get("stores").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : args.getAsJsonObject("stores").entrySet()) {
                STORES.put(e.getKey(), e.getValue().getAsJsonObject());
            }
        }
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 1200;
        beat(ctx);
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
