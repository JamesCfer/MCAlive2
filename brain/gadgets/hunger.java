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
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Hunger. Every NPC carries a fed level 0-20; it falls with time, is topped up by eating
 * out of its own line's store, and when a line has nothing left to eat its people begin
 * to starve and can die of it.
 *
 * The point is not bookkeeping - it is pressure. A line that never sends anyone foraging
 * runs its store down and starts losing people, and because death here is permanent that
 * is a real consequence rather than a status effect.
 *
 * The fed level is written back into the ledger so the console can show it, and each
 * NPC's current state feeds the activity line too ("eating", "starving").
 */
public class Hunger implements GadgetContract {

    private static Integer TASK_ID = null;
    private static int beats = 0;
    private static int meals = 0;
    private static int deaths = 0;
    private static final Map<String, JsonObject> STORES = new HashMap<String, JsonObject>();
    private static final Map<String, String> FORAGERS = new HashMap<String, String>();
    private static final java.util.Set<String> NEEDS_FOOD = new java.util.HashSet<String>();
    private static boolean lethal = true;
    private static int drainPerBeat = 1;

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("hunger-generation");
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
            if (inner != null && inner.getClass().getName().contains("Hunger")) { t.cancel(); killed++; }
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

    /** How much each food restores. Cooked food is worth the firewood. */
    private static int nutrition(Material m) {
        switch (m) {
            case COOKED_BEEF: case COOKED_PORKCHOP: return 8;
            case COOKED_MUTTON: case COOKED_SALMON: return 6;
            case COOKED_CHICKEN: case COOKED_COD: case COOKED_RABBIT: return 6;
            case BREAD: case BAKED_POTATO: return 5;
            case APPLE: case CARROT: case MELON_SLICE: return 4;
            case BEEF: case PORKCHOP: case MUTTON: return 3;
            case CHICKEN: case RABBIT: case COD: case SALMON: return 2;
            case SWEET_BERRIES: case BEETROOT: case DRIED_KELP: return 2;
            case POTATO: return 1;
            default: return 0;
        }
    }

    private static Inventory chestAt(World w, JsonObject c) {
        if (c == null) return null;
        Block b = w.getBlockAt(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    /** Take the least valuable adequate meal from the store, so feasts are not wasted. */
    private static Material takeMeal(Inventory inv, int need) {
        int bestSlot = -1, bestScore = Integer.MAX_VALUE;
        Material best = null;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null) continue;
            int n = nutrition(s.getType());
            if (n <= 0) continue;
            int waste = Math.max(0, n - need);
            if (waste < bestScore) { bestScore = waste; bestSlot = i; best = s.getType(); }
        }
        if (bestSlot < 0) return null;
        ItemStack s = inv.getItem(bestSlot);
        s.setAmount(s.getAmount() - 1);
        inv.setItem(bestSlot, s.getAmount() <= 0 ? null : s);
        return best;
    }

    private static void beat(GadgetContext ctx) {
        beats++;
        NpcManager npcs = ctx.plugin().npcManager();
        try {
            JsonObject q = new JsonObject();
            q.addProperty("collection", "npcs");
            JsonObject res = ctx.invoke("ledger_query", q);
            JsonArray records = res.getAsJsonArray("records");
            for (JsonElement el : records) {
                JsonObject rec = el.getAsJsonObject();
                if (!rec.has("id")) continue;
                String id = rec.get("id").getAsString();
                if (rec.has("alive") && !rec.get("alive").getAsBoolean()) continue;
                NpcData d = npcs.get(id);
                if (d == null || d.dead) continue;

                int fed = rec.has("hunger") && !rec.get("hunger").isJsonNull()
                        ? rec.get("hunger").getAsInt() : 20;
                fed -= drainPerBeat;

                String faction = rec.has("faction") && !rec.get("faction").isJsonNull()
                        ? rec.get("faction").getAsString() : null;
                JsonObject store = faction == null ? null : STORES.get(faction);
                String ate = null;
                if (fed <= 12 && store != null) {
                    World w = ctx.world(null);
                    Inventory inv = chestAt(w, store);
                    if (inv != null) {
                        Material meal = takeMeal(inv, 20 - fed);
                        if (meal != null) {
                            fed = Math.min(20, fed + nutrition(meal));
                            ate = meal.name();
                            meals++;
                        } else if (faction != null) {
                            NEEDS_FOOD.add(faction);   // nothing edible left in this store
                        }
                    }
                }

                if (fed <= 0) {
                    fed = 0;
                    Entity e = npcs.resolveEntity(d);
                    if (lethal && e instanceof LivingEntity) {
                        LivingEntity le = (LivingEntity) e;
                        // starvation bites slowly - a line has several minutes to feed
                        // someone before it loses them for good
                        le.damage(2.0);
                        if (le.isDead() || le.getHealth() <= 0.0) deaths++;
                        JsonObject ev = new JsonObject();
                        ev.addProperty("npcId", id);
                        ev.addProperty("name", d.name);
                        if (faction != null) ev.addProperty("faction", faction);
                        ctx.plugin().bridge().broadcastEvent("npc_starving", ev);
                    }
                }

                rec.addProperty("hunger", fed);
                rec.addProperty("fedState", fed <= 0 ? "starving" : (fed <= 6 ? "hungry" : "fed"));
                if (ate != null) rec.addProperty("lastMeal", ate);
                JsonObject put = new JsonObject();
                put.addProperty("collection", "npcs");
                put.add("record", rec);
                ctx.invoke("ledger_put", put);
            }
        } catch (Throwable ignored) { }
        sendForagers(ctx);
    }

    /**
     * A line whose store ran dry sends its forager out. Without this, hunger is just a
     * countdown to a wipe: the world has no natural spawns, so food only exists if
     * somebody goes and finds it.
     */
    private static void sendForagers(GadgetContext ctx) {
        for (String faction : new java.util.ArrayList<String>(NEEDS_FOOD)) {
            NEEDS_FOOD.remove(faction);
            String npcId = FORAGERS.get(faction);
            JsonObject store = STORES.get(faction);
            if (npcId == null || store == null) continue;
            try {
                JsonObject q = new JsonObject();
                q.addProperty("npcId", npcId);
                q.addProperty("action", "status");
                JsonObject call = new JsonObject();
                call.addProperty("id", "forage");
                call.add("args", q);
                JsonObject cur = ctx.invoke("gadget_run", call);
                if (cur.has("running") && cur.get("running").getAsBoolean()) continue;

                NpcData d = ctx.plugin().npcManager().get(npcId);
                JsonObject a = new JsonObject();
                a.addProperty("npcId", npcId);
                a.add("chest", store);
                if (d != null && d.home != null) {
                    JsonObject h = new JsonObject();
                    h.addProperty("x", d.home.getBlockX());
                    h.addProperty("y", d.home.getBlockY());
                    h.addProperty("z", d.home.getBlockZ());
                    a.add("home", h);
                }
                a.addProperty("want", 16);
                a.addProperty("stride", 24);
                a.addProperty("maxSteps", 20);
                JsonObject go = new JsonObject();
                go.addProperty("id", "forage");
                go.add("args", a);
                ctx.invoke("gadget_run", go);
                JsonObject ev = new JsonObject();
                ev.addProperty("faction", faction);
                ev.addProperty("npcId", npcId);
                ctx.plugin().bridge().broadcastEvent("line_sent_forager", ev);
            } catch (Throwable ignored) { }
        }
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("beats", beats);
            out.addProperty("mealsEaten", meals);
            out.addProperty("starvationDeaths", deaths);
            out.addProperty("lethal", lethal);
            JsonObject st = new JsonObject();
            for (Map.Entry<String, JsonObject> e : STORES.entrySet()) st.add(e.getKey(), e.getValue());
            out.add("stores", st);
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
        FORAGERS.clear();
        if (args.has("foragers") && args.get("foragers").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : args.getAsJsonObject("foragers").entrySet()) {
                FORAGERS.put(e.getKey(), e.getValue().getAsString());
            }
        }
        lethal = !args.has("lethal") || args.get("lethal").getAsBoolean();
        drainPerBeat = args.has("drainPerBeat") ? args.get("drainPerBeat").getAsInt() : 1;
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 2400;

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
        out.addProperty("lethal", lethal);
        out.addProperty("lines", STORES.size());
        return out;
    }
}
