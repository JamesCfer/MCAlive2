package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * People. Every NPC is a player: 20 hp, 20 hunger, a 36-stack inventory, real tools with
 * real durability, vanilla recipes and vanilla drop rules, and feet. They start at world
 * spawn with nothing and get everything else the way a player does.
 *
 * What makes one person different from another:
 *
 *   ABILITIES  six scores, -3..+3, fixed for life. str dex con wis int cha.
 *   SKILLS     ten, start at 0, earned by doing. Minutes spent at a skill accumulate and
 *              point n arrives at 2^n - 1 total minutes (1, 3, 7, 15, 31 ...). Each skill
 *              leans on one ability. Skill and ability together weight both WHICH job a
 *              person picks and HOW FAST they do it.
 *   NEEDS      three. Full hp, full hunger, and one that is theirs alone - to explore, to
 *              be around people, to own things, to make things. Happiness is simply how
 *              full the three are.
 *
 * A need that is low has several answers and the person picks among them by what they
 * are good at: the hungry can hunt, fish, farm, or trade for food. Trading is dynamic -
 * you value an item by how much it serves your own unmet needs, and a deal happens only
 * when both sides gain by their own reckoning. Nothing worth offering means no deal, and
 * the person goes and solves it another way.
 *
 * All state lives in the ledger record. The only static is the timer. Redefine this
 * gadget freely - nobody loses anything, and a half-done job resumes where it was.
 *
 * Jobs are meant to be added over time. Each is one static method that advances a small
 * persisted state machine by one beat and returns true when finished.
 */
public class People implements GadgetContract {

    // ------------------------------------------------------------------ tables

    private static final String[] SKILLS = {
        "farming", "hunting", "mining", "building", "fishing",
        "swimming", "exploring", "treechopping", "crafting", "trading"
    };

    /** Which ability each skill leans on. Change freely. */
    private static final Map<String, String> SKILL_STAT = new HashMap<String, String>();
    static {
        SKILL_STAT.put("farming", "wis");
        SKILL_STAT.put("hunting", "dex");
        SKILL_STAT.put("mining", "str");
        SKILL_STAT.put("building", "con");
        SKILL_STAT.put("fishing", "wis");
        SKILL_STAT.put("swimming", "con");
        SKILL_STAT.put("exploring", "wis");
        SKILL_STAT.put("treechopping", "str");
        SKILL_STAT.put("crafting", "int");
        SKILL_STAT.put("trading", "cha");
    }

    private static final int BEAT_TICKS = 20;              // one beat = one second
    private static final double BEAT_MIN = BEAT_TICKS / 1200.0;
    private static final int MAX_STACKS = 36;

    // hunger, in the units a player would recognise
    // Vanilla hunger, 1:1. Walking costs nothing. Exhaustion comes from doing things,
    // at the game's own prices; 4 exhaustion takes a point of saturation, or of hunger
    // once saturation is gone. Healing costs 6 per hp. (Minecraft wiki, Hunger.)
    private static final double EX_JUMP = 0.05;          // per block climbed
    private static final double EX_SWIM = 0.01;          // per metre swum
    private static final double EX_BREAK = 0.005;        // per block broken
    private static final double EX_ATTACK = 0.1;         // per swing
    private static final double EX_HURT = 0.1;           // per hit taken
    private static final double EX_REGEN = 6.0;          // per hp healed
    private static final int EAT_AT = 14;                // a sensible player eats around here

    private static Integer TASK_ID = null;
    private static int beats = 0;
    private static long seed = 4242L;

    private static int rand(int n) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        int v = (int) ((seed >>> 33) % n);
        return v < 0 ? -v : v;
    }

    // ------------------------------------------------------------------ timer plumbing

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("people-generation");
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
            if (inner != null && inner.getClass().getName().contains("People")) { t.cancel(); killed++; }
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

    private static List<JsonObject> roster(GadgetContext ctx) throws Exception {
        JsonObject q = new JsonObject();
        q.addProperty("collection", "npcs");
        JsonArray recs = ctx.invoke("ledger_query", q).getAsJsonArray("records");
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonElement e : recs) out.add(e.getAsJsonObject());
        return out;
    }

    private static void save(GadgetContext ctx, JsonObject rec) throws Exception {
        JsonObject p = new JsonObject();
        p.addProperty("collection", "npcs");
        p.add("record", rec);
        ctx.invoke("ledger_put", p);
    }

    private static int geti(JsonObject o, String k, int dflt) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : dflt;
    }

    private static double getd(JsonObject o, String k, double dflt) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : dflt;
    }

    private static String gets(JsonObject o, String k, String dflt) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : dflt;
    }

    private static JsonObject obj(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonObject()) o.add(k, new JsonObject());
        return o.getAsJsonObject(k);
    }

    private static JsonArray arr(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) o.add(k, new JsonArray());
        return o.getAsJsonArray(k);
    }

    /** Fill in anything a record is missing, so a hand-written roster entry is enough. */
    private static void normalise(JsonObject rec) {
        JsonObject ab = obj(rec, "abilities");
        for (String s : new String[]{ "str", "dex", "con", "wis", "int", "cha" }) {
            if (!ab.has(s)) ab.addProperty(s, 0);
        }
        JsonObject sk = obj(rec, "skills");
        for (int i = 0; i < SKILLS.length; i++) {
            JsonObject s = obj(sk, SKILLS[i]);
            if (!s.has("minutes")) s.addProperty("minutes", 0.0);
            if (!s.has("points")) s.addProperty("points", pointsFor(getd(s, "minutes", 0)));
        }
        if (!rec.has("hunger")) rec.addProperty("hunger", 20);
        if (!rec.has("saturation")) rec.addProperty("saturation", 5.0);
        if (!rec.has("exhaustion")) rec.addProperty("exhaustion", 0.0);
        JsonObject need = obj(rec, "need");
        if (!need.has("kind")) need.addProperty("kind", "explore");
        if (!need.has("value")) need.addProperty("value", 10);
        arr(rec, "inventory");
        if (!rec.has("alive")) rec.addProperty("alive", true);
    }

    private static int ability(JsonObject rec, String name) {
        return geti(obj(rec, "abilities"), name, 0);
    }

    // ------------------------------------------------------------------ skills

    /** Point n at 2^n - 1 minutes: 1, 3, 7, 15, 31 ... */
    private static int pointsFor(double minutes) {
        int n = 0;
        while (Math.pow(2, n + 1) - 1 <= minutes) n++;
        return n;
    }

    private static int skill(JsonObject rec, String name) {
        return geti(obj(obj(rec, "skills"), name), "points", 0);
    }

    private static void practise(JsonObject rec, String name, double minutes) {
        JsonObject s = obj(obj(rec, "skills"), name);
        double m = getd(s, "minutes", 0) + minutes;
        s.addProperty("minutes", m);
        s.addProperty("points", pointsFor(m));
    }

    /** Skill plus the ability it leans on: the number that weights choice and speed. */
    private static int aptitude(JsonObject rec, String skillName) {
        return skill(rec, skillName) + ability(rec, SKILL_STAT.get(skillName));
    }

    /** Beats between actions for a job, shortened by aptitude. */
    private static int pace(JsonObject rec, String skillName, int baseBeats) {
        double f = 1.0 + 0.15 * Math.max(-4, aptitude(rec, skillName));
        return Math.max(1, (int) Math.round(baseBeats / f));
    }

    // ------------------------------------------------------------------ inventory

    private static int count(JsonObject rec, Material m) {
        int n = 0;
        for (JsonElement e : arr(rec, "inventory")) {
            JsonObject s = e.getAsJsonObject();
            if (m.name().equals(gets(s, "item", ""))) n += geti(s, "count", 0);
        }
        return n;
    }

    private static int stacksUsed(JsonObject rec) {
        return arr(rec, "inventory").size();
    }

    /** Add like a player picking up: merge into stacks, open new ones while there is room. Returns what would not fit. */
    private static int give(JsonObject rec, Material m, int n) {
        if (n <= 0) return 0;
        JsonArray inv = arr(rec, "inventory");
        int max = Math.max(1, m.getMaxStackSize());
        if (max > 1) {
            for (JsonElement e : inv) {
                JsonObject s = e.getAsJsonObject();
                if (!m.name().equals(gets(s, "item", ""))) continue;
                int c = geti(s, "count", 0);
                int room = max - c;
                if (room <= 0) continue;
                int take = Math.min(room, n);
                s.addProperty("count", c + take);
                n -= take;
                if (n == 0) return 0;
            }
        }
        while (n > 0 && inv.size() < MAX_STACKS) {
            JsonObject s = new JsonObject();
            s.addProperty("item", m.name());
            int take = Math.min(max, n);
            s.addProperty("count", take);
            if (m.getMaxDurability() > 0) s.addProperty("damage", 0);
            inv.add(s);
            n -= take;
        }
        return n;
    }

    private static boolean take(JsonObject rec, Material m, int n) {
        if (count(rec, m) < n) return false;
        JsonArray inv = arr(rec, "inventory");
        for (int i = inv.size() - 1; i >= 0 && n > 0; i--) {
            JsonObject s = inv.get(i).getAsJsonObject();
            if (!m.name().equals(gets(s, "item", ""))) continue;
            int c = geti(s, "count", 0);
            int t = Math.min(c, n);
            n -= t;
            if (c - t <= 0) inv.remove(i); else s.addProperty("count", c - t);
        }
        return true;
    }

    private static boolean isFull(JsonObject rec) {
        return stacksUsed(rec) >= MAX_STACKS;
    }

    /** The best tool of a kind in the bag (AXE, PICKAXE, HOE, SWORD, ROD), or null. */
    private static JsonObject bestTool(JsonObject rec, String kind) {
        JsonObject best = null;
        int bestRank = -1;
        for (JsonElement e : arr(rec, "inventory")) {
            JsonObject s = e.getAsJsonObject();
            String n = gets(s, "item", "");
            if (!n.endsWith("_" + kind) && !(kind.equals("ROD") && n.equals("FISHING_ROD"))) continue;
            int rank = n.startsWith("NETHERITE") ? 5 : n.startsWith("DIAMOND") ? 4 : n.startsWith("IRON") ? 3
                    : n.startsWith("STONE") ? 2 : n.startsWith("GOLDEN") ? 1 : n.startsWith("WOODEN") ? 1 : 1;
            if (rank > bestRank) { bestRank = rank; best = s; }
        }
        return best;
    }

    private static ItemStack stackOf(JsonObject s) {
        if (s == null) return null;
        Material m = Material.matchMaterial(gets(s, "item", "AIR"));
        if (m == null) return null;
        ItemStack it = new ItemStack(m, 1);
        if (s.has("damage")) {
            ItemMeta meta = it.getItemMeta();
            if (meta instanceof Damageable) {
                ((Damageable) meta).setDamage(geti(s, "damage", 0));
                it.setItemMeta(meta);
            }
        }
        return it;
    }

    /** One use of a tool. Returns true if it just broke (and removes it). */
    private static boolean wear(JsonObject rec, JsonObject tool) {
        if (tool == null) return false;
        Material m = Material.matchMaterial(gets(tool, "item", "AIR"));
        if (m == null || m.getMaxDurability() <= 0) return false;
        int d = geti(tool, "damage", 0) + 1;
        if (d >= m.getMaxDurability()) {
            arr(rec, "inventory").remove(tool);
            return true;
        }
        tool.addProperty("damage", d);
        return false;
    }

    private static void hold(Entity e, JsonObject tool) {
        if (!(e instanceof LivingEntity)) return;
        EntityEquipment eq = ((LivingEntity) e).getEquipment();
        if (eq == null) return;
        ItemStack it = tool == null ? new ItemStack(Material.AIR) : stackOf(tool);
        eq.setItemInMainHand(it == null ? new ItemStack(Material.AIR) : it);
    }

    // ------------------------------------------------------------------ food and value

    /** Vanilla food points. */
    private static int nutrition(Material m) {
        switch (m) {
            case COOKED_BEEF: case COOKED_PORKCHOP: return 8;
            case COOKED_MUTTON: case COOKED_SALMON: case COOKED_CHICKEN: return 6;
            case COOKED_COD: case COOKED_RABBIT: case BREAD: case BAKED_POTATO: return 5;
            case APPLE: return 4;
            case BEEF: case PORKCHOP: case RABBIT: case CARROT: return 3;
            case MUTTON: case CHICKEN: case COD: case SALMON: case SWEET_BERRIES: case MELON_SLICE: return 2;
            case POTATO: case BEETROOT: case DRIED_KELP: return 1;
            default: return 0;
        }
    }

    /** Vanilla saturation restored. */
    private static double saturationOf(Material m) {
        switch (m) {
            case COOKED_BEEF: case COOKED_PORKCHOP: return 12.8;
            case COOKED_MUTTON: case COOKED_SALMON: return 9.6;
            case COOKED_CHICKEN: return 7.2;
            case COOKED_COD: case COOKED_RABBIT: case BREAD: case BAKED_POTATO: return 6.0;
            case CARROT: return 3.6;
            case APPLE: return 2.4;
            case BEEF: case PORKCHOP: case RABBIT: return 1.8;
            case MUTTON: case CHICKEN: case MELON_SLICE: case BEETROOT: return 1.2;
            case POTATO: case DRIED_KELP: return 0.6;
            case COD: case SALMON: case SWEET_BERRIES: return 0.4;
            default: return 0;
        }
    }

    /** Spend exhaustion the way the game does. */
    private static void exhaust(JsonObject rec, double amount) {
        rec.addProperty("exhaustion", getd(rec, "exhaustion", 0) + amount);
    }

    private static int foodInBag(JsonObject rec) {
        int n = 0;
        for (JsonElement e : arr(rec, "inventory")) {
            JsonObject s = e.getAsJsonObject();
            Material m = Material.matchMaterial(gets(s, "item", ""));
            if (m != null) n += nutrition(m) * geti(s, "count", 0);
        }
        return n;
    }

    /** A flat sense of what things are worth, before anyone's needs colour it. */
    private static double baseValue(Material m) {
        String n = m.name();
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_HOE") || n.endsWith("_SWORD") || n.endsWith("_SHOVEL")) {
            return n.startsWith("WOODEN") ? 6 : n.startsWith("STONE") ? 10 : 20;
        }
        if (n.equals("FISHING_ROD")) return 8;
        if (n.endsWith("_INGOT")) return 8;
        if (n.startsWith("RAW_")) return 5;
        if (n.equals("COAL")) return 3;
        if (n.endsWith("_LOG")) return 1.5;
        if (n.endsWith("_PLANKS")) return 0.5;
        if (n.equals("STICK")) return 0.25;
        if (n.equals("COBBLESTONE")) return 0.2;
        if (n.equals("WHEAT_SEEDS")) return 0.5;
        if (n.equals("WHEAT")) return 1.5;
        int food = nutrition(m);
        if (food > 0) return food * 0.6;
        return 1.0;
    }

    /**
     * What one unit of this is worth TO THIS PERSON right now. Food climbs steeply with
     * hunger; a tool is worth far more to someone who has none of that kind; anything
     * is worth a little more to someone who wants to own things.
     */
    private static double valueTo(JsonObject rec, Material m) {
        double v = baseValue(m);
        int food = nutrition(m);
        if (food > 0) {
            int hunger = geti(rec, "hunger", 20);
            v *= 1.0 + Math.pow(Math.max(0, 20 - hunger), 2) / 60.0;
            if (foodInBag(rec) == 0) v *= 1.5;
        }
        String n = m.name();
        for (String kind : new String[]{ "PICKAXE", "AXE", "HOE", "SWORD" }) {
            if (n.endsWith("_" + kind) && bestTool(rec, kind) == null) v *= 2.5;
        }
        if (n.equals("FISHING_ROD") && bestTool(rec, "ROD") == null) v *= 2.5;
        if ("wealth".equals(gets(obj(rec, "need"), "kind", ""))) v *= 1.2;
        return v;
    }

    private static double wealthOf(JsonObject rec) {
        double v = 0;
        for (JsonElement e : arr(rec, "inventory")) {
            JsonObject s = e.getAsJsonObject();
            Material m = Material.matchMaterial(gets(s, "item", ""));
            if (m != null) v += baseValue(m) * geti(s, "count", 0);
        }
        return v;
    }

    // ------------------------------------------------------------------ needs

    private static double hp(Entity e) {
        return e instanceof LivingEntity ? ((LivingEntity) e).getHealth() : 20;
    }

    private static int thirdNeed(JsonObject rec) {
        JsonObject need = obj(rec, "need");
        if ("wealth".equals(gets(need, "kind", ""))) {
            int v = (int) Math.min(20, Math.round(wealthOf(rec) / 8.0));
            need.addProperty("value", v);
            return v;
        }
        return geti(need, "value", 10);
    }

    private static void feedNeed(JsonObject rec, String kind, double amount) {
        JsonObject need = obj(rec, "need");
        if (!kind.equals(gets(need, "kind", ""))) return;
        need.addProperty("value", Math.max(0, Math.min(20, getd(need, "value", 10) + amount)));
    }

    private static int happiness(JsonObject rec, Entity e) {
        double total = hp(e) + geti(rec, "hunger", 20) + thirdNeed(rec);
        return (int) Math.round(total / 60.0 * 100.0);
    }

    // ------------------------------------------------------------------ walking

    private static JsonObject walkStatus(GadgetContext ctx, String id) throws Exception {
        JsonObject a = new JsonObject();
        a.addProperty("action", "status");
        a.addProperty("npcId", id);
        return ctx.invoke("gadget:navigate", a);
    }

    private static boolean walking(GadgetContext ctx, String id) {
        try { return walkStatus(ctx, id).get("walking").getAsBoolean(); } catch (Throwable t) { return false; }
    }

    /** Start walking toward a spot, in a leg of at most 48 blocks so the pathfinder stays cheap. */
    private static boolean walk(GadgetContext ctx, Entity e, String id, int x, int y, int z, boolean underground) {
        Location at = e.getLocation();
        double dx = x - at.getX(), dz = z - at.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        int tx = x, ty = y, tz = z;
        if (dist > 48) {
            tx = (int) Math.round(at.getX() + dx / dist * 48);
            tz = (int) Math.round(at.getZ() + dz / dist * 48);
            ty = e.getWorld().getHighestBlockYAt(tx, tz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        }
        JsonObject a = new JsonObject();
        a.addProperty("npcId", id);
        JsonObject to = new JsonObject();
        to.addProperty("x", tx);
        to.addProperty("y", ty);
        to.addProperty("z", tz);
        a.add("to", to);
        a.addProperty("maxNodes", 6000);
        if (underground) a.addProperty("underground", true);
        try {
            JsonObject r = ctx.invoke("gadget:navigate", a);
            return r.has("started") && r.get("started").getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static double flatDist(Entity e, int x, int z) {
        Location at = e.getLocation();
        return Math.sqrt(Math.pow(at.getX() - (x + 0.5), 2) + Math.pow(at.getZ() - (z + 0.5), 2));
    }

    /**
     * Shared "get there" phase for every job. job.target = {x,y,z}. Returns
     * 1 when within reach, 0 while still travelling, -1 when it has given up.
     */
    private static int travel(GadgetContext ctx, Entity e, String id, JsonObject job, double reach, boolean underground) {
        JsonObject t = obj(job, "target");
        int x = geti(t, "x", 0), y = geti(t, "y", 64), z = geti(t, "z", 0);
        if (flatDist(e, x, z) <= reach && Math.abs(e.getLocation().getY() - y) <= 3) return 1;
        // Still walking? Hand over the next leg BEFORE this one runs out, so a long
        // journey is one continuous walk instead of a march of 48-block stops.
        try {
            JsonObject st = walkStatus(ctx, id);
            if (st.get("walking").getAsBoolean()) {
                int left = geti(st, "legsLeft", 99);
                if (left > 3 || flatDist(e, x, z) <= 48) return 0;
                walk(ctx, e, id, x, y, z, underground);
                return 0;
            }
        } catch (Throwable ignored) { }
        int tries = geti(job, "legs", 0);
        if (tries >= 12) return -1;
        job.addProperty("legs", tries + 1);
        // did the last leg get us anywhere? if not twice running, give up
        double last = getd(job, "lastDist", -1);
        double now = flatDist(e, x, z);
        if (last >= 0 && now > last - 2) {
            int stuck = geti(job, "stuck", 0) + 1;
            job.addProperty("stuck", stuck);
            if (stuck >= 3) return -1;
        } else {
            job.addProperty("stuck", 0);
        }
        job.addProperty("lastDist", now);
        return walk(ctx, e, id, x, y, z, underground) ? 0 : -1;
    }

    private static void setTarget(JsonObject job, int x, int y, int z) {
        JsonObject t = new JsonObject();
        t.addProperty("x", x);
        t.addProperty("y", y);
        t.addProperty("z", z);
        job.add("target", t);
        job.remove("legs");
        job.remove("stuck");
        job.remove("lastDist");
    }

    // ------------------------------------------------------------------ world helpers

    private static Material meatOf(EntityType t) {
        switch (t) {
            case COW: return Material.BEEF;
            case PIG: return Material.PORKCHOP;
            case SHEEP: return Material.MUTTON;
            case CHICKEN: return Material.CHICKEN;
            case RABBIT: return Material.RABBIT;
            default: return null;
        }
    }

    private static boolean isLog(Material m) {
        return m.name().endsWith("_LOG") && !m.name().startsWith("STRIPPED");
    }

    private static boolean isStoneLike(Material m) {
        return m == Material.STONE || m == Material.COBBLESTONE || m == Material.ANDESITE
                || m == Material.GRANITE || m == Material.DIORITE || m == Material.DEEPSLATE
                || m.name().endsWith("_ORE");
    }

    /** Nearest block matching a test within r of the entity, searching surface-ish heights. */
    private static Block nearestBlock(Entity e, int r, int dyDown, int dyUp, BlockTest test) {
        World w = e.getWorld();
        Location at = e.getLocation();
        int cx = at.getBlockX(), cy = at.getBlockY(), cz = at.getBlockZ();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -dyDown; dy <= dyUp; dy++) {
                    Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!test.ok(b)) continue;
                    double d = dx * dx + dz * dz + dy * dy * 0.5;
                    if (d < bestD) { bestD = d; best = b; }
                }
            }
        }
        return best;
    }

    private interface BlockTest { boolean ok(Block b); }

    /** The most worthwhile dropped item nearby, or null. Food only when hungry. */
    private static org.bukkit.entity.Item nearestDrop(JsonObject rec, Entity e, double r, boolean hungry) {
        org.bukkit.entity.Item best = null;
        double bestScore = 0;
        for (Entity n : e.getNearbyEntities(r, 6, r)) {
            if (!(n instanceof org.bukkit.entity.Item)) continue;
            ItemStack s = ((org.bukkit.entity.Item) n).getItemStack();
            Material m = s.getType();
            double v = valueTo(rec, m) * s.getAmount();
            if (nutrition(m) > 0 && !hungry) v *= 0.3;
            if (v < 3) continue;
            double score = v / (1 + n.getLocation().distance(e.getLocation()) / 8.0);
            if (score > bestScore) { bestScore = score; best = (org.bukkit.entity.Item) n; }
        }
        return best;
    }

    // Where each person has been, kept in its own collection so the character sheet the
    // actor reads is not buried under four hundred chunk keys.
    private static JsonObject explored(GadgetContext ctx, String id) {
        try {
            JsonObject q = new JsonObject();
            q.addProperty("collection", "explored");
            q.addProperty("id", id);
            return ctx.invoke("ledger_get", q);
        } catch (Throwable t) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.add("chunks", new JsonArray());
            return o;
        }
    }

    private static boolean hasSeen(JsonObject ex, int cx, int cz) {
        String key = cx + "," + cz;
        for (JsonElement s : arr(ex, "chunks")) if (key.equals(s.getAsString())) return true;
        return false;
    }

    /** Note the chunk under this location. Returns true if it was new. */
    private static boolean markSeen(GadgetContext ctx, String id, Location at) {
        JsonObject ex = explored(ctx, id);
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        if (hasSeen(ex, cx, cz)) return false;
        JsonArray chunks = arr(ex, "chunks");
        chunks.add(cx + "," + cz);
        while (chunks.size() > 600) chunks.remove(0);
        try {
            JsonObject p = new JsonObject();
            p.addProperty("collection", "explored");
            p.add("record", ex);
            ctx.invoke("ledger_put", p);
        } catch (Throwable ignored) { }
        return true;
    }

    /** Break a block the way a player with this tool would, and pocket the drops. */
    private static void breakBlock(JsonObject rec, Block b, JsonObject tool) {
        ItemStack t = stackOf(tool);
        Collection<ItemStack> drops = t == null ? b.getDrops() : b.getDrops(t);
        for (ItemStack d : drops) give(rec, d.getType(), d.getAmount());
        b.getWorld().playSound(b.getLocation(), org.bukkit.Sound.BLOCK_WOOD_BREAK, 0.6f, 1.0f);
        b.setType(Material.AIR);
        wear(rec, tool);
        exhaust(rec, EX_BREAK);
    }

    // ------------------------------------------------------------------ crafting

    private static List<RecipeChoice> choicesOf(Recipe r) {
        List<RecipeChoice> out = new ArrayList<RecipeChoice>();
        if (r instanceof ShapedRecipe) {
            ShapedRecipe sr = (ShapedRecipe) r;
            Map<Character, RecipeChoice> map = sr.getChoiceMap();
            String[] shape = sr.getShape();
            for (int i = 0; i < shape.length; i++) {
                for (int j = 0; j < shape[i].length(); j++) {
                    RecipeChoice ch = map.get(Character.valueOf(shape[i].charAt(j)));
                    if (ch != null) out.add(ch);
                }
            }
            return out;
        }
        if (r instanceof ShapelessRecipe) {
            List<RecipeChoice> l = ((ShapelessRecipe) r).getChoiceList();
            for (int i = 0; i < l.size(); i++) if (l.get(i) != null) out.add(l.get(i));
            return out;
        }
        return null;
    }

    private static boolean needsTable(Recipe r) {
        if (r instanceof ShapedRecipe) {
            String[] shape = ((ShapedRecipe) r).getShape();
            int w = 0;
            for (int i = 0; i < shape.length; i++) w = Math.max(w, shape[i].length());
            return w > 2 || shape.length > 2;
        }
        if (r instanceof ShapelessRecipe) return ((ShapelessRecipe) r).getChoiceList().size() > 4;
        return true;
    }

    /**
     * Which material to satisfy a recipe slot with. What is in the bag first; failing that,
     * whichever alternative the bag could MAKE - a table wants "any planks", and someone
     * holding birch logs should reach for birch planks, not go looking for an oak tree.
     */
    private static Material pick(RecipeChoice ch, JsonObject rec) {
        if (ch instanceof RecipeChoice.MaterialChoice) {
            List<Material> ms = ((RecipeChoice.MaterialChoice) ch).getChoices();
            for (Material m : ms) if (count(rec, m) > 0) return m;
            for (Material m : ms) {
                List<Recipe> rs = Bukkit.getRecipesFor(new ItemStack(m));
                for (Recipe r : rs) {
                    List<RecipeChoice> needs = choicesOf(r);
                    if (needs == null) continue;
                    boolean all = true;
                    for (RecipeChoice n : needs) {
                        boolean any = false;
                        if (n instanceof RecipeChoice.MaterialChoice) {
                            for (Material x : ((RecipeChoice.MaterialChoice) n).getChoices()) if (count(rec, x) > 0) { any = true; break; }
                        } else if (n instanceof RecipeChoice.ExactChoice) {
                            for (ItemStack x : ((RecipeChoice.ExactChoice) n).getChoices()) if (count(rec, x.getType()) > 0) { any = true; break; }
                        }
                        if (!any) { all = false; break; }
                    }
                    if (all) return m;
                }
            }
            return ms.isEmpty() ? null : ms.get(0);
        }
        if (ch instanceof RecipeChoice.ExactChoice) {
            List<ItemStack> ss = ((RecipeChoice.ExactChoice) ch).getChoices();
            return ss.isEmpty() ? null : ss.get(0).getType();
        }
        return null;
    }

    /** Materials still missing for the cheapest known recipe of want, or null if no recipe. */
    private static Map<Material, Integer> missingFor(JsonObject rec, Material want, boolean[] tableOut) {
        List<Recipe> recipes = Bukkit.getRecipesFor(new ItemStack(want));
        Map<Material, Integer> bestMissing = null;
        int bestShort = Integer.MAX_VALUE;
        for (Recipe r : recipes) {
            List<RecipeChoice> needs = choicesOf(r);
            if (needs == null) continue;
            Map<Material, Integer> req = new HashMap<Material, Integer>();
            for (RecipeChoice ch : needs) {
                Material m = pick(ch, rec);
                if (m == null) { req = null; break; }
                Integer p = req.get(m);
                req.put(m, (p == null ? 0 : p.intValue()) + 1);
            }
            if (req == null) continue;
            Map<Material, Integer> missing = new HashMap<Material, Integer>();
            int shortBy = 0;
            for (Map.Entry<Material, Integer> e : req.entrySet()) {
                int have = count(rec, e.getKey());
                if (have < e.getValue().intValue()) {
                    missing.put(e.getKey(), e.getValue().intValue() - have);
                    shortBy += e.getValue().intValue() - have;
                }
            }
            if (shortBy < bestShort) {
                bestShort = shortBy;
                bestMissing = missing;
                tableOut[0] = needsTable(r);
            }
        }
        return bestMissing;
    }

    /** Actually make one of want from the bag. Assumes nothing is missing. */
    private static boolean craftNow(JsonObject rec, Material want) {
        List<Recipe> recipes = Bukkit.getRecipesFor(new ItemStack(want));
        for (Recipe r : recipes) {
            List<RecipeChoice> needs = choicesOf(r);
            if (needs == null) continue;
            Map<Material, Integer> req = new HashMap<Material, Integer>();
            boolean ok = true;
            for (RecipeChoice ch : needs) {
                Material m = pick(ch, rec);
                if (m == null) { ok = false; break; }
                Integer p = req.get(m);
                req.put(m, (p == null ? 0 : p.intValue()) + 1);
            }
            if (!ok) continue;
            for (Map.Entry<Material, Integer> e : req.entrySet()) {
                if (count(rec, e.getKey()) < e.getValue().intValue()) { ok = false; break; }
            }
            if (!ok) continue;
            for (Map.Entry<Material, Integer> e : req.entrySet()) take(rec, e.getKey(), e.getValue().intValue());
            ItemStack res = r.getResult();
            give(rec, res.getType(), res.getAmount());
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ jobs

    private static final String[] GATHER_JOBS = { "hunt", "fish", "farm", "chop", "mine", "explore" };

    private static String skillOf(String job) {
        if (job.equals("hunt")) return "hunting";
        if (job.equals("fish")) return "fishing";
        if (job.equals("farm")) return "farming";
        if (job.equals("chop")) return "treechopping";
        if (job.equals("mine")) return "mining";
        if (job.equals("explore")) return "exploring";
        if (job.equals("craft")) return "crafting";
        if (job.equals("trade")) return "trading";
        if (job.equals("build")) return "building";
        return null;
    }

    /** Can this person even attempt the job right now? */
    private static boolean possible(JsonObject rec, String job, List<JsonObject> everyone) {
        if (isFull(rec) && (job.equals("hunt") || job.equals("chop") || job.equals("mine") || job.equals("fish"))) return false;
        if (job.equals("fish")) return bestTool(rec, "ROD") != null;
        if (job.equals("build")) return rec.has("asked") && System.currentTimeMillis() > (long) getd(rec, "noBuildUntil", 0);
        if (job.equals("market")) {
            long until = (long) getd(rec, "noMarketUntil", 0);
            return System.currentTimeMillis() > until && rec.has("village");
        }
        if (job.equals("trade")) {
            if (everyone.size() < 2 || arr(rec, "inventory").size() == 0) return false;
            long until = (long) getd(rec, "noTradeUntil", 0);
            return System.currentTimeMillis() > until;
        }
        return true;
    }

    /** How much a job serves a need, 0..1. */
    private static double serves(String job, String need, JsonObject rec) {
        if (need.equals("hunger")) {
            if (job.equals("hunt")) return 1.0;
            if (job.equals("fish")) return 1.0;
            if (job.equals("farm")) {
                if (rec.has("ripeNear") && rec.get("ripeNear").getAsBoolean()) return 1.0;
                return bestTool(rec, "HOE") != null || count(rec, Material.WHEAT_SEEDS) > 0 ? 0.8 : 0.5;
            }
            if (job.equals("trade")) return 0.9;
            if (job.equals("market")) return 0.95;
            return 0;
        }
        if (need.equals("hp")) return job.equals("rest") ? 1.0 : 0;
        if (job.equals("build")) return need.equals("third") && "craft".equals(gets(obj(rec, "need"), "kind", "")) ? 1.0 : 0;
        String kind = gets(obj(rec, "need"), "kind", "explore");
        if (kind.equals("explore")) return job.equals("explore") ? 1.0 : 0;
        if (kind.equals("social")) return job.equals("visit") ? 1.0 : (job.equals("trade") ? 0.6 : 0);
        if (kind.equals("wealth")) {
            if (job.equals("mine")) return 1.0;
            if (job.equals("chop")) return 0.7;
            if (job.equals("hunt")) return 0.5;
            if (job.equals("trade")) return 0.6;
            return 0;
        }
        if (kind.equals("craft")) return job.equals("craft") ? 1.0 : (job.equals("chop") ? 0.4 : 0);
        return 0;
    }

    private static double aptitudeWeight(JsonObject rec, String job) {
        String s = skillOf(job);
        if (s == null) return 1.0;
        return Math.max(0.3, 1.0 + 0.25 * skill(rec, s) + 0.15 * ability(rec, SKILL_STAT.get(s)));
    }

    /**
     * Pick what to do next. Each need pulls with the square of its shortfall, each job
     * answers some needs and not others, and skill makes its own jobs louder. When
     * nothing is short, people do what they are good at, which is how Bob ends up
     * mining on a full stomach.
     */
    private static String choose(JsonObject rec, Entity e, List<JsonObject> everyone) {
        double hungerGap = 20 - geti(rec, "hunger", 20);
        double hpGap = 20 - hp(e);
        double thirdGap = 20 - thirdNeed(rec);
        String[] jobs = { "hunt", "fish", "farm", "chop", "mine", "explore", "craft", "trade", "visit", "rest", "build", "market" };
        String[] needs = { "hunger", "hp", "third" };
        double[] gaps = { hungerGap, hpGap, thirdGap };
        List<String> ids = new ArrayList<String>();
        List<Double> scores = new ArrayList<Double>();
        boolean anyShort = hungerGap >= 4 || hpGap >= 6 || thirdGap >= 6;
        // Genuinely hungry? Then the only question is how to get food - by whatever you are
        // best at. Nobody goes down the mine on an empty stomach to feel rich.
        boolean starving = hungerGap >= 13;
        for (String job : jobs) {
            if (!possible(rec, job, everyone)) continue;
            if (starving && serves(job, "hunger", rec) <= 0) continue;
            double s = 0;
            for (int i = 0; i < needs.length; i++) {
                if (gaps[i] <= 0) continue;
                s += gaps[i] * gaps[i] * serves(job, needs[i], rec);
            }
            if (!anyShort) {
                // idle hands: weight purely by what they like, among the gathering jobs -
                // and a village that has asked for its inn comes first
                boolean gather = job.equals("build");
                for (String g : GATHER_JOBS) if (g.equals(job)) gather = true;
                if (!gather) continue;
                s = job.equals("build") ? 40 : 10;
            }
            s *= aptitudeWeight(rec, job);
            if (s <= 0) continue;
            ids.add(job);
            scores.add(Double.valueOf(s));
        }
        if (ids.isEmpty()) return "rest";
        // weighted pick among the top three
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < ids.size(); i++) order.add(Integer.valueOf(i));
        java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) { return Double.compare(scores.get(b), scores.get(a)); }
        });
        int pool = Math.min(3, order.size());
        double sum = 0;
        for (int i = 0; i < pool; i++) sum += scores.get(order.get(i));
        double roll = rand(10000) / 10000.0 * sum;
        for (int i = 0; i < pool; i++) {
            roll -= scores.get(order.get(i));
            if (roll <= 0) return ids.get(order.get(i));
        }
        return ids.get(order.get(0));
    }

    private static JsonObject startJob(JsonObject rec, String kind) {
        JsonObject job = new JsonObject();
        job.addProperty("kind", kind);
        job.addProperty("phase", "start");
        job.addProperty("beats", 0);
        job.addProperty("wait", 0);
        rec.add("job", job);
        return job;
    }

    /**
     * End the current job. If it was a step on the way to something else - chopping for
     * the planks for a table - go back to that, whatever happened here. The caller
     * decides whether the chain has gone on too long.
     */
    private static void finishJob(JsonObject rec, Entity e, String why) {
        JsonObject job = rec.has("job") && rec.get("job").isJsonObject() ? rec.getAsJsonObject("job") : null;
        String then = job == null ? null : gets(job, "then", null);
        String thenWant = job == null ? null : gets(job, "thenWant", null);
        int depth = job == null ? 0 : geti(job, "depth", 0);
        rec.remove("job");
        rec.remove("assigned");
        rec.addProperty("lastJobEnd", why);
        hold(e, null);
        if (then != null) {
            JsonObject next = startJob(rec, then);
            if (thenWant != null) next.addProperty("want", thenWant);
            next.addProperty("depth", depth);
        }
    }

    // ---- hunt: walk up to an animal and kill it with whatever is in hand

    private static boolean jobHunt(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        String phase = gets(job, "phase", "start");
        if (phase.equals("start")) {
            int radius = 32 + 6 * Math.max(0, aptitude(rec, "hunting"));
            LivingEntity best = null;
            double bestD = Double.MAX_VALUE;
            for (Entity n : e.getNearbyEntities(radius, 24, radius)) {
                if (!(n instanceof LivingEntity) || meatOf(n.getType()) == null) continue;
                double d = n.getLocation().distanceSquared(e.getLocation());
                if (d < bestD) { bestD = d; best = (LivingEntity) n; }
            }
            if (best == null) {
                // nothing in sight: wander a way off in a random direction and look again
                int tries = geti(job, "searches", 0);
                if (tries >= 4) { finishJob(rec, e, "no game"); return true; }
                job.addProperty("searches", tries + 1);
                double th = rand(360) * Math.PI / 180;
                int x = e.getLocation().getBlockX() + (int) (Math.cos(th) * 40);
                int z = e.getLocation().getBlockZ() + (int) (Math.sin(th) * 40);
                setTarget(job, x, e.getWorld().getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z);
                job.addProperty("phase", "search");
                rec.addProperty("activity", "looking for game");
                return false;
            }
            job.addProperty("prey", best.getUniqueId().toString());
            Location l = best.getLocation();
            setTarget(job, l.getBlockX(), l.getBlockY(), l.getBlockZ());
            job.addProperty("phase", "stalk");
            rec.addProperty("activity", "hunting a " + best.getType().name().toLowerCase());
            JsonObject sword = bestTool(rec, "SWORD");
            hold(e, sword != null ? sword : bestTool(rec, "AXE"));
            return false;
        }
        if (phase.equals("search")) {
            int t = travel(ctx, e, id, job, 3, false);
            if (t != 0) job.addProperty("phase", "start");
            return false;
        }
        // stalk
        if (phase.equals("loot")) {
            // Walk onto each thing the kill dropped until nothing is left lying there.
            // Drops scatter a few blocks from where the animal stood, and the first
            // version of this finished the moment the hunter was "near enough" to that
            // spot - so nobody ever actually picked up the meat, and everyone starved.
            int stay = geti(job, "stay", 0) + 1;
            job.addProperty("stay", stay);
            org.bukkit.entity.Item drop = null;
            double best = 8.0;
            for (Entity n : e.getNearbyEntities(8, 4, 8)) {
                if (!(n instanceof org.bukkit.entity.Item)) continue;
                double d = n.getLocation().distance(e.getLocation());
                if (d < best) { best = d; drop = (org.bukkit.entity.Item) n; }
            }
            if (drop == null || stay > 40 || isFull(rec)) { finishJob(rec, e, gets(job, "kill", "killed something")); return true; }
            Location l = drop.getLocation();
            JsonObject t = obj(job, "target");
            if (Math.abs(geti(t, "x", 0) - l.getBlockX()) > 0 || Math.abs(geti(t, "z", 0) - l.getBlockZ()) > 0) {
                setTarget(job, l.getBlockX(), l.getBlockY(), l.getBlockZ());
            }
            travel(ctx, e, id, job, 0.8, false);
            return false;
        }
        Entity preyE = null;
        try { preyE = Bukkit.getEntity(java.util.UUID.fromString(gets(job, "prey", ""))); } catch (Throwable ignored) { }
        if (!(preyE instanceof LivingEntity) || preyE.isDead()) { job.addProperty("phase", "start"); return false; }
        LivingEntity prey = (LivingEntity) preyE;
        Location pl = prey.getLocation();
        double d = pl.distance(e.getLocation());
        int chase = geti(job, "chase", 0) + 1;
        job.addProperty("chase", chase);
        if (chase > 90) { finishJob(rec, e, "lost it"); return true; }      // a minute and a half is enough
        if (d > 3.0) {
            // re-aim only when it has really moved off, and only between legs, or the
            // walk is restarted every second and the hunter stutters after its prey
            JsonObject t = obj(job, "target");
            boolean moved = Math.abs(geti(t, "x", 0) - pl.getBlockX()) > 1 || Math.abs(geti(t, "z", 0) - pl.getBlockZ()) > 1;
            if (moved && !walking(ctx, id)) setTarget(job, pl.getBlockX(), pl.getBlockY(), pl.getBlockZ());
            int tr = travel(ctx, e, id, job, 2.0, false);
            if (tr < 0) { finishJob(rec, e, "lost it"); return true; }
            if (tr > 0 && !walking(ctx, id)) {
                // standing where it was, and it is not here: close the last gap directly
                walk(ctx, e, id, pl.getBlockX(), pl.getBlockY(), pl.getBlockZ(), false);
            }
            return false;
        }
        // in reach: one swing a second, like a charged hit. Attributed to the hunter so
        // the animal flinches, is knocked back, and drops what it really drops.
        JsonObject weapon = bestTool(rec, "SWORD");
        if (weapon == null) weapon = bestTool(rec, "AXE");
        double dmg = 1.0;
        if (weapon != null) {
            String n = gets(weapon, "item", "");
            dmg = n.endsWith("_SWORD") ? (n.startsWith("WOODEN") ? 4 : n.startsWith("STONE") ? 5 : 6)
                    : (n.startsWith("WOODEN") ? 7 : n.startsWith("STONE") ? 9 : 9);
        }
        if (e instanceof LivingEntity) {
            ((LivingEntity) e).swingMainHand();
            Location look = e.getLocation();
            look.setDirection(pl.toVector().subtract(look.toVector()));
            e.setRotation(look.getYaw(), look.getPitch());
            prey.damage(dmg, e);
        } else {
            prey.damage(dmg);
        }
        wear(rec, weapon);
        exhaust(rec, EX_ATTACK);
        if (prey.isDead() || prey.getHealth() <= 0) {
            practise(rec, "hunting", 0.5);
            job.addProperty("kill", "killed a " + prey.getType().name().toLowerCase());
            setTarget(job, pl.getBlockX(), pl.getBlockY(), pl.getBlockZ());
            job.addProperty("phase", "loot");
            job.addProperty("stay", 0);
            rec.addProperty("activity", "gathering the kill");
            return false;
        }
        return false;
    }

    // ---- chop: nearest log, break it with axe or hands, take the wood

    private static boolean jobChop(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        String phase = gets(job, "phase", "start");
        if (phase.equals("start")) {
            Block b = nearestBlock(e, 24, 4, 8, new BlockTest() { public boolean ok(Block x) { return isLog(x.getType()); } });
            if (b == null) {
                int tries = geti(job, "searches", 0);
                if (tries >= 3) { finishJob(rec, e, "no trees"); return true; }
                job.addProperty("searches", tries + 1);
                double th = rand(360) * Math.PI / 180;
                int x = e.getLocation().getBlockX() + (int) (Math.cos(th) * 40);
                int z = e.getLocation().getBlockZ() + (int) (Math.sin(th) * 40);
                setTarget(job, x, e.getWorld().getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z);
                job.addProperty("phase", "search");
                rec.addProperty("activity", "looking for trees");
                return false;
            }
            // stand at the foot of the trunk
            int fy = b.getY();
            while (fy > e.getWorld().getMinHeight() && isLog(e.getWorld().getBlockAt(b.getX(), fy - 1, b.getZ()).getType())) fy--;
            setTarget(job, b.getX(), fy, b.getZ());
            job.addProperty("phase", "go");
            rec.addProperty("activity", "walking to a tree");
            return false;
        }
        if (phase.equals("search")) {
            int t = travel(ctx, e, id, job, 3, false);
            if (t != 0) job.addProperty("phase", "start");
            return false;
        }
        if (phase.equals("go")) {
            int t = travel(ctx, e, id, job, 2.5, false);
            if (t < 0) {
                // could not reach that one; try another, but not forever
                int fails = geti(job, "fails", 0) + 1;
                job.addProperty("fails", fails);
                if (fails >= 3) { finishJob(rec, e, "could not reach a tree"); return true; }
                job.addProperty("phase", "start");
                return false;
            }
            if (t == 0) return false;
            job.addProperty("phase", "fell");
            job.addProperty("wait", 0);
            rec.addProperty("activity", "felling a tree");
            hold(e, bestTool(rec, "AXE"));
            return false;
        }
        // fell: one log per pace, from the bottom up, reaching up like a player
        JsonObject t = obj(job, "target");
        int x = geti(t, "x", 0), z = geti(t, "z", 0);
        JsonObject axe = bestTool(rec, "AXE");
        int need = pace(rec, "treechopping", axe == null ? 4 : 1);
        int w = geti(job, "wait", 0) + 1;
        if (w < need) { job.addProperty("wait", w); return false; }
        job.addProperty("wait", 0);
        int y = geti(t, "y", 64);
        Block b = e.getWorld().getBlockAt(x, y, z);
        if (!isLog(b.getType())) {
            // trunk is gone - anything left floating above is leaves, which a player leaves too
            finishJob(rec, e, "felled a tree");
            return true;
        }
        if (isFull(rec)) { finishJob(rec, e, "bag full"); return true; }
        breakBlock(rec, b, axe);
        t.addProperty("y", y + 1);
        if (y + 1 - geti(job, "foot", y) > 6) { finishJob(rec, e, "felled a tree"); return true; }
        if (!job.has("foot")) job.addProperty("foot", y);
        return false;
    }

    // ---- mine: needs a pickaxe; dig into the nearest stone and take what drops

    private static boolean jobMine(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        JsonObject pick = bestTool(rec, "PICKAXE");
        if (pick == null) {
            // a player would make one first
            JsonObject j = startJob(rec, "craft");
            j.addProperty("want", count(rec, Material.COBBLESTONE) >= 3 ? "STONE_PICKAXE" : "WOODEN_PICKAXE");
            j.addProperty("then", "mine");
            return false;
        }
        String phase = gets(job, "phase", "start");
        if (phase.equals("start")) {
            Block b = nearestBlock(e, 16, 6, 3, new BlockTest() {
                public boolean ok(Block x) {
                    if (!isStoneLike(x.getType())) return false;
                    // exposed to air somewhere, so a person could swing at it
                    return x.getRelative(0, 1, 0).getType().isAir() || x.getRelative(1, 0, 0).getType().isAir()
                            || x.getRelative(-1, 0, 0).getType().isAir() || x.getRelative(0, 0, 1).getType().isAir()
                            || x.getRelative(0, 0, -1).getType().isAir();
                }
            });
            if (b == null) {
                // no rock showing: dig a stair down right here, like a player would
                Location at = e.getLocation();
                b = e.getWorld().getBlockAt(at.getBlockX() + 1, at.getBlockY() - 1, at.getBlockZ());
            }
            setTarget(job, b.getX(), b.getY() + 1, b.getZ());
            job.addProperty("phase", "go");
            rec.addProperty("activity", "walking to the rock");
            return false;
        }
        if (phase.equals("go")) {
            int t = travel(ctx, e, id, job, 2.5, true);
            if (t < 0) { finishJob(rec, e, "could not reach the rock"); return true; }
            if (t == 0) return false;
            job.addProperty("phase", "dig");
            job.addProperty("dug", 0);
            hold(e, pick);
            rec.addProperty("activity", "mining");
            return false;
        }
        // dig: break the nearest stone within arm's reach each pace, up to a load
        int need = pace(rec, "mining", 2);
        int w = geti(job, "wait", 0) + 1;
        if (w < need) { job.addProperty("wait", w); return false; }
        job.addProperty("wait", 0);
        if (isFull(rec)) { finishJob(rec, e, "bag full"); return true; }
        Block b = nearestBlock(e, 2, 2, 2, new BlockTest() { public boolean ok(Block x) { return isStoneLike(x.getType()); } });
        if (b == null) { job.addProperty("phase", "start"); return false; }
        // never dig the block under your own feet into nothing
        Location at = e.getLocation();
        if (b.getX() == at.getBlockX() && b.getZ() == at.getBlockZ() && b.getY() < at.getBlockY()) {
            Block side = e.getWorld().getBlockAt(at.getBlockX() + 1, at.getBlockY(), at.getBlockZ());
            if (!isStoneLike(side.getType())) { job.addProperty("phase", "start"); return false; }
            b = side;
        }
        breakBlock(rec, b, pick);
        int dug = geti(job, "dug", 0) + 1;
        job.addProperty("dug", dug);
        if (bestTool(rec, "PICKAXE") == null) { finishJob(rec, e, "pickaxe broke"); return true; }
        if (dug >= 24) { finishJob(rec, e, "brought up a load"); return true; }
        return false;
    }

    // ---- fish: stand by water with a rod and wait

    private static boolean jobFish(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        JsonObject rod = bestTool(rec, "ROD");
        if (rod == null) { finishJob(rec, e, "no rod"); return true; }
        String phase = gets(job, "phase", "start");
        if (phase.equals("start")) {
            Block b = nearestBlock(e, 32, 4, 2, new BlockTest() {
                public boolean ok(Block x) { return x.getType() == Material.WATER && x.getRelative(0, 1, 0).getType().isAir(); }
            });
            if (b == null) { finishJob(rec, e, "no water"); return true; }
            setTarget(job, b.getX(), b.getY() + 1, b.getZ());
            job.addProperty("phase", "go");
            rec.addProperty("activity", "walking to the water");
            return false;
        }
        if (phase.equals("go")) {
            int t = travel(ctx, e, id, job, 3, false);
            if (t < 0) { finishJob(rec, e, "could not reach water"); return true; }
            if (t == 0) return false;
            job.addProperty("phase", "cast");
            job.addProperty("caught", 0);
            hold(e, rod);
            rec.addProperty("activity", "fishing");
            return false;
        }
        // cast: a bite roughly every 20-30 s for a beginner, faster with skill
        int need = pace(rec, "fishing", 25);
        int w = geti(job, "wait", 0) + 1;
        if (w < need) { job.addProperty("wait", w); return false; }
        job.addProperty("wait", 0);
        give(rec, rand(3) == 0 ? Material.SALMON : Material.COD, 1);
        if (wear(rec, rod)) { finishJob(rec, e, "rod broke"); return true; }
        int c = geti(job, "caught", 0) + 1;
        job.addProperty("caught", c);
        if (c >= 5 || isFull(rec)) { finishJob(rec, e, "caught " + c + " fish"); return true; }
        return false;
    }

    // ---- farm: seeds from grass, a hoe, a tilled row by water, wheat in time

    /** Nearest farmland to this person, or null. Anyone's field is a field. */
    private static Block farmlandNear(Entity e, int r) {
        return nearestBlock(e, r, 6, 4, new BlockTest() { public boolean ok(Block x) { return x.getType() == Material.FARMLAND; } });
    }

    /** Ripe wheat on a field this person knows, wherever they are standing now. */
    private static Block ripeAtField(World w, JsonObject rec) {
        if (!rec.has("field") || !rec.get("field").isJsonObject()) return null;
        JsonObject f = rec.getAsJsonObject("field");
        int fx = geti(f, "x", 0), fy = geti(f, "y", 64), fz = geti(f, "z", 0);
        if (!w.isChunkLoaded(fx >> 4, fz >> 4)) return null;
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) for (int dy = -2; dy <= 3; dy++) {
            Block b = w.getBlockAt(fx + dx, fy + dy, fz + dz);
            if (b.getType() == Material.WHEAT && b.getBlockData() instanceof Ageable && ((Ageable) b.getBlockData()).getAge() >= 7) return b;
        }
        return null;
    }

    private static Block ripeNear(Entity e, int r) {
        return nearestBlock(e, r, 6, 4, new BlockTest() {
            public boolean ok(Block x) {
                return x.getType() == Material.WHEAT && x.getBlockData() instanceof Ageable && ((Ageable) x.getBlockData()).getAge() >= 7;
            }
        });
    }

    private static boolean jobFarm(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        String phase = gets(job, "phase", "start");
        World w = e.getWorld();
        if (phase.equals("start")) {
            boolean haveHoe = bestTool(rec, "HOE") != null;
            int seeds = count(rec, Material.WHEAT_SEEDS);

            // Ripe wheat anywhere near is food on the stalk. Take it, whoever planted it -
            // the farmland at spawn was standing ready while people walked past it to hunt.
            Block ripe = ripeNear(e, 48);
            if (ripe == null) ripe = ripeAtField(w, rec);
            if (ripe != null) { setTarget(job, ripe.getX(), ripe.getY(), ripe.getZ()); job.addProperty("phase", "reap"); rec.addProperty("activity", "going to harvest"); return false; }

            // The field: farmland already near (yours or anyone's) beats breaking new ground.
            JsonObject field = rec.has("field") && rec.get("field").isJsonObject() ? rec.getAsJsonObject("field") : null;
            Block near = farmlandNear(e, 48);
            if (near != null && (field == null || distTo(field, e) > 96)) {
                field = new JsonObject();
                field.addProperty("x", near.getX());
                field.addProperty("y", near.getY());
                field.addProperty("z", near.getZ());
                rec.add("field", field);
            }
            if (field == null) {
                Block water = nearestBlock(e, 40, 4, 2, new BlockTest() { public boolean ok(Block x) { return x.getType() == Material.WATER; } });
                if (water == null) { finishJob(rec, e, "no water for a field"); return true; }
                field = new JsonObject();
                field.addProperty("x", water.getX() + 2);
                field.addProperty("y", w.getHighestBlockYAt(water.getX() + 2, water.getZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES));
                field.addProperty("z", water.getZ());
                rec.add("field", field);
            }
            int fx = geti(field, "x", 0), fy = geti(field, "y", 64), fz = geti(field, "z", 0);
            // walk to the field first if it is far; everything below is judged from there
            if (flatDist(e, fx, fz) > 12) {
                setTarget(job, fx, fy + 1, fz);
                job.addProperty("phase", "goField");
                rec.addProperty("activity", "walking to the field");
                return false;
            }

            Block empty = null, untilled = null;
            int growing = 0;
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Block ground = w.getBlockAt(fx + dx, fy, fz + dz);
                    Block crop = ground.getRelative(0, 1, 0);
                    if (ground.getType() == Material.FARMLAND) {
                        if (crop.getType() == Material.WHEAT) growing++;
                        else if (crop.getType().isAir() && empty == null) empty = ground;
                    } else if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2
                            && (ground.getType() == Material.GRASS_BLOCK || ground.getType() == Material.DIRT) && untilled == null
                            && ground.getRelative(0, 1, 0).isPassable()) {
                        untilled = ground;
                    }
                }
            }
            if (empty != null && seeds > 0) { setTarget(job, empty.getX(), empty.getY() + 1, empty.getZ()); job.addProperty("phase", "sow"); rec.addProperty("activity", "going to sow"); return false; }
            if (untilled != null && haveHoe) { setTarget(job, untilled.getX(), untilled.getY() + 1, untilled.getZ()); job.addProperty("phase", "till"); rec.addProperty("activity", "going to till"); return false; }
            if (seeds == 0 && empty != null) {
                Block grass = nearestBlock(e, 24, 3, 3, new BlockTest() { public boolean ok(Block x) { return x.getType() == Material.SHORT_GRASS || x.getType() == Material.TALL_GRASS; } });
                if (grass == null) { finishJob(rec, e, "no grass for seed"); return true; }
                setTarget(job, grass.getX(), grass.getY(), grass.getZ());
                job.addProperty("phase", "seed");
                rec.addProperty("activity", "beating grass for seed");
                return false;
            }
            if (!haveHoe && untilled != null) {
                JsonObject j = startJob(rec, "craft");
                j.addProperty("want", count(rec, Material.COBBLESTONE) >= 2 ? "STONE_HOE" : "WOODEN_HOE");
                j.addProperty("then", "farm");
                return false;
            }
            if (growing > 0) {
                // Planted and growing. Stay with it a while - a crop only ripens in a loaded
                // chunk and a farmer who walks off to hunt comes back to seedlings.
                job.addProperty("phase", "tend");
                if (!job.has("tended")) job.addProperty("tended", 0);
                rec.addProperty("activity", "tending the field (" + growing + " growing)");
                return false;
            }
            finishJob(rec, e, "nothing to do at the field");
            return true;
        }
        if (phase.equals("goField")) {
            int t = travel(ctx, e, id, job, 6, false);
            if (t < 0) { finishJob(rec, e, "could not reach the field"); return true; }
            if (t > 0) job.addProperty("phase", "start");
            return false;
        }
        if (phase.equals("tend")) {
            int n = geti(job, "tended", 0) + 1;
            job.addProperty("tended", n);
            // check for ripe every few beats; leave after a couple of minutes
            if (n % 5 == 0) { job.addProperty("phase", "start"); return false; }
            if (n > 150) { finishJob(rec, e, "field is growing"); return true; }
            return false;
        }
        int t = travel(ctx, e, id, job, 2.5, false);
        if (t < 0) { finishJob(rec, e, "could not reach the field"); return true; }
        if (t == 0) return false;
        int need = pace(rec, "farming", 2);
        int wt = geti(job, "wait", 0) + 1;
        if (wt < need) { job.addProperty("wait", wt); return false; }
        job.addProperty("wait", 0);
        JsonObject tg = obj(job, "target");
        int x = geti(tg, "x", 0), y = geti(tg, "y", 64), z = geti(tg, "z", 0);
        if (phase.equals("seed")) {
            Block g = w.getBlockAt(x, y, z);
            if (g.getType() == Material.SHORT_GRASS || g.getType() == Material.TALL_GRASS) breakBlock(rec, g, null);
            int got = geti(job, "got", 0) + 1;
            job.addProperty("got", got);
            if (count(rec, Material.WHEAT_SEEDS) >= 4 || got >= 12) job.addProperty("phase", "start");
            else {
                Block grass = nearestBlock(e, 12, 3, 3, new BlockTest() { public boolean ok(Block b) { return b.getType() == Material.SHORT_GRASS || b.getType() == Material.TALL_GRASS; } });
                if (grass == null) job.addProperty("phase", "start");
                else setTarget(job, grass.getX(), grass.getY(), grass.getZ());
            }
            return false;
        }
        if (phase.equals("till")) {
            Block g = w.getBlockAt(x, y - 1, z);
            JsonObject hoe = bestTool(rec, "HOE");
            hold(e, hoe);
            if (g.getType() == Material.GRASS_BLOCK || g.getType() == Material.DIRT) { g.setType(Material.FARMLAND); wear(rec, hoe); }
            job.addProperty("phase", "start");
            return false;
        }
        if (phase.equals("sow")) {
            Block g = w.getBlockAt(x, y - 1, z);
            Block crop = w.getBlockAt(x, y, z);
            if (g.getType() == Material.FARMLAND && crop.getType().isAir() && take(rec, Material.WHEAT_SEEDS, 1)) crop.setType(Material.WHEAT);
            job.addProperty("phase", "start");
            return false;
        }
        if (phase.equals("reap")) {
            Block crop = w.getBlockAt(x, y, z);
            if (crop.getType() == Material.WHEAT) {
                breakBlock(rec, crop, null);
                practise(rec, "farming", 0.5);
                // a farmer replants the row as they go
                if (count(rec, Material.WHEAT_SEEDS) > 0 && crop.getRelative(0, -1, 0).getType() == Material.FARMLAND && take(rec, Material.WHEAT_SEEDS, 1)) {
                    crop.setType(Material.WHEAT);
                }
            }
            job.addProperty("phase", "start");
            return false;
        }
        job.addProperty("phase", "start");
        return false;
    }

    // ---- explore: walk somewhere you have not been

    private static boolean jobExplore(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        String phase = gets(job, "phase", "start");
        if (phase.equals("start")) {
            int reach = 32 + 8 * Math.max(0, aptitude(rec, "exploring"));
            JsonObject ex = explored(ctx, id);
            // try a few bearings, prefer one whose far end is an unseen chunk
            int bx = 0, bz = 0;
            boolean found = false;
            for (int i = 0; i < 8 && !found; i++) {
                double th = rand(360) * Math.PI / 180;
                int x = e.getLocation().getBlockX() + (int) (Math.cos(th) * reach);
                int z = e.getLocation().getBlockZ() + (int) (Math.sin(th) * reach);
                bx = x; bz = z;
                if (!hasSeen(ex, x >> 4, z >> 4)) found = true;
            }
            setTarget(job, bx, e.getWorld().getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, bz);
            job.addProperty("phase", "go");
            rec.addProperty("activity", "exploring");
            return false;
        }
        int t = travel(ctx, e, id, job, 4, false);
        if (t == 0) return false;
        finishJob(rec, e, t < 0 ? "turned back" : "explored");
        return true;
    }

    // ---- craft: make a thing, gathering what it takes first

    private static boolean jobCraft(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        String wantName = gets(job, "want", null);
        if (wantName == null) {
            // nothing in particular: make the most useful thing you lack
            String[] wish = { "CRAFTING_TABLE", "WOODEN_PICKAXE", "WOODEN_AXE", "STONE_PICKAXE", "STONE_AXE", "WOODEN_HOE", "WOODEN_SWORD" };
            for (String wname : wish) {
                Material m = Material.matchMaterial(wname);
                if (m == null || count(rec, m) > 0) continue;
                String kind = wname.substring(wname.indexOf('_') + 1);
                if (!wname.equals("CRAFTING_TABLE") && bestTool(rec, kind) != null) continue;
                wantName = wname;
                break;
            }
            if (wantName == null) { finishJob(rec, e, "nothing worth making"); return true; }
            job.addProperty("want", wantName);
        }
        Material want = Material.matchMaterial(wantName);
        if (want == null) { finishJob(rec, e, "unknown item"); return true; }
        int depth = geti(job, "depth", 0);
        if (depth > 6) { finishJob(rec, e, "gave up on " + wantName.toLowerCase()); return true; }

        boolean[] table = new boolean[1];
        Map<Material, Integer> missing = missingFor(rec, want, table);
        if (missing == null) { finishJob(rec, e, "no recipe for " + wantName.toLowerCase()); return true; }

        if (!missing.isEmpty()) {
            // go get the first missing thing, then come back to this
            Material m = missing.keySet().iterator().next();
            int n = missing.get(m).intValue();
            JsonObject next = null;
            if (isLog(m)) {
                next = startJob(rec, "chop");
            } else if (m == Material.COBBLESTONE) {
                if (bestTool(rec, "PICKAXE") == null) {
                    next = startJob(rec, "craft");
                    next.addProperty("want", "WOODEN_PICKAXE");
                } else {
                    next = startJob(rec, "mine");
                }
            } else if (m == Material.WHEAT_SEEDS || m == Material.WHEAT) {
                next = startJob(rec, "farm");
            } else if (m == Material.STRING || m == Material.LEATHER || m == Material.FEATHER) {
                finishJob(rec, e, "cannot get " + m.name().toLowerCase());
                return true;
            } else {
                Map<Material, Integer> sub = missingFor(rec, m, new boolean[1]);
                if (sub == null) { finishJob(rec, e, "cannot get " + m.name().toLowerCase()); return true; }
                next = startJob(rec, "craft");
                next.addProperty("want", m.name());
            }
            next.addProperty("then", "craft");
            next.addProperty("thenWant", wantName);
            next.addProperty("depth", depth + 1);
            rec.addProperty("activity", "needs " + n + " " + m.name().toLowerCase().replace('_', ' ') + " for a " + wantName.toLowerCase().replace('_', ' '));
            return false;
        }

        if (table[0]) {
            // needs a bench within reach. Place one from the bag, or make one.
            Block bench = nearestBlock(e, 4, 2, 2, new BlockTest() { public boolean ok(Block b) { return b.getType() == Material.CRAFTING_TABLE; } });
            if (bench == null) {
                if (count(rec, Material.CRAFTING_TABLE) == 0) {
                    JsonObject next = startJob(rec, "craft");
                    next.addProperty("want", "CRAFTING_TABLE");
                    next.addProperty("then", "craft");
                    next.addProperty("thenWant", wantName);
                    next.addProperty("depth", depth + 1);
                    return false;
                }
                Location at = e.getLocation();
                Block spot = e.getWorld().getBlockAt(at.getBlockX() + 1, at.getBlockY(), at.getBlockZ());
                if (!spot.getType().isAir()) spot = e.getWorld().getBlockAt(at.getBlockX() - 1, at.getBlockY(), at.getBlockZ());
                if (spot.getType().isAir() && take(rec, Material.CRAFTING_TABLE, 1)) {
                    spot.setType(Material.CRAFTING_TABLE);
                    practise(rec, "building", 0.5);
                }
            }
        }
        int need = pace(rec, "crafting", 3);
        int w = geti(job, "wait", 0) + 1;
        if (w < need) { job.addProperty("wait", w); rec.addProperty("activity", "making a " + wantName.toLowerCase().replace('_', ' ')); return false; }
        if (craftNow(rec, want)) {
            feedNeed(rec, "craft", 5);
            finishJob(rec, e, "made a " + wantName.toLowerCase().replace('_', ' '));
            return true;
        }
        finishJob(rec, e, "could not make " + wantName.toLowerCase());
        return true;
    }

    // ---- villages: the places ledger, read each beat it is needed

    private static List<JsonObject> places(GadgetContext ctx) {
        try {
            JsonObject q = new JsonObject();
            q.addProperty("collection", "places");
            JsonArray recs = ctx.invoke("ledger_query", q).getAsJsonArray("records");
            List<JsonObject> out = new ArrayList<JsonObject>();
            for (JsonElement el : recs) out.add(el.getAsJsonObject());
            return out;
        } catch (Throwable t) {
            return new ArrayList<JsonObject>();
        }
    }

    private static double distTo(JsonObject xyz, Entity e) {
        Location at = e.getLocation();
        return Math.sqrt(Math.pow(geti(xyz, "x", 0) - at.getX(), 2) + Math.pow(geti(xyz, "z", 0) - at.getZ(), 2));
    }

    /** Nearest village with an inn within range, or null. */
    private static JsonObject nearestInn(List<JsonObject> places, Entity e, double within) {
        JsonObject best = null;
        double bestD = within;
        for (JsonObject p : places) {
            if (!"village".equals(gets(p, "kind", "")) || !p.has("inn") || !p.get("inn").isJsonObject()) continue;
            double d = distTo(p.getAsJsonObject("inn"), e);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private static boolean isNight(World w) {
        long t = w.getTime();
        return t >= 13000 && t < 23000;
    }

    private static boolean memberOf(JsonObject village, String id) {
        for (JsonElement m : arr(village, "members")) if (id.equals(m.getAsString())) return true;
        return false;
    }

    private static org.bukkit.inventory.Inventory chestAt(World w, JsonObject c) {
        if (c == null) return null;
        Block b = w.getBlockAt(geti(c, "x", 0), geti(c, "y", 64), geti(c, "z", 0));
        b.getChunk().load();
        org.bukkit.block.BlockState st = b.getState();
        if (st instanceof org.bukkit.block.Container) return ((org.bukkit.block.Container) st).getInventory();
        return null;
    }

    /** The cheapest thing in the bag worth at least {@code min}, that is not a tool. */
    private static JsonObject cheapest(JsonObject rec, double min) {
        JsonObject best = null;
        double bestV = Double.MAX_VALUE;
        for (JsonElement el : arr(rec, "inventory")) {
            JsonObject s = el.getAsJsonObject();
            Material m = Material.matchMaterial(gets(s, "item", ""));
            if (m == null || m == Material.PLAYER_HEAD || m.getMaxDurability() > 0) continue;
            double v = baseValue(m);
            if (v < min) continue;
            if (v < bestV) { bestV = v; best = s; }
        }
        return best;
    }

    // ---- build: raise the village inn, block by block, from the bag

    private static final int INN_W = 5, INN_D = 5, INN_H = 3;

    /** The i-th block of the inn: {dx, dy, dz, what} with what 0=floor 1=wall 2=roof, or null past the end. */
    private static int[] innBlock(int i) {
        int n = 0;
        for (int dz = 0; dz < INN_D; dz++) for (int dx = 0; dx < INN_W; dx++) { if (n == i) return new int[]{ dx, 0, dz, 0 }; n++; }
        for (int dy = 1; dy <= INN_H; dy++) {
            for (int dz = 0; dz < INN_D; dz++) for (int dx = 0; dx < INN_W; dx++) {
                boolean edge = dx == 0 || dz == 0 || dx == INN_W - 1 || dz == INN_D - 1;
                if (!edge) continue;
                boolean door = dz == 0 && dx == INN_W / 2 && dy <= 2;
                if (door) continue;
                if (n == i) return new int[]{ dx, dy, dz, 1 };
                n++;
            }
        }
        for (int dz = 0; dz < INN_D; dz++) for (int dx = 0; dx < INN_W; dx++) { if (n == i) return new int[]{ dx, INN_H + 1, dz, 2 }; n++; }
        return null;
    }

    private static Material plankOf(JsonObject rec) {
        for (JsonElement el : arr(rec, "inventory")) {
            String n = gets(el.getAsJsonObject(), "item", "");
            if (n.endsWith("_PLANKS")) return Material.matchMaterial(n);
        }
        // split a log into planks, as you would at the bench
        for (JsonElement el : arr(rec, "inventory")) {
            String n = gets(el.getAsJsonObject(), "item", "");
            if (isLog(Material.matchMaterial(n))) {
                Material planks = Material.matchMaterial(n.replace("_LOG", "_PLANKS"));
                if (planks != null && take(rec, Material.matchMaterial(n), 1)) { give(rec, planks, 4); return planks; }
            }
        }
        return null;
    }

    /** Is this footprint (plus a border) open, dry, level ground? */
    private static boolean plotOk(World w, int x, int z, int pw, int pd) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = -1; dx <= pw; dx++) for (int dz = -1; dz <= pd; dz++) {
            int y = w.getHighestBlockYAt(x + dx, z + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Material m = w.getBlockAt(x + dx, y, z + dz).getType();
            if (m == Material.WATER || m == Material.LAVA || isLog(m) || m == Material.FARMLAND
                    || m == Material.CRAFTING_TABLE || m == Material.CHEST || m == Material.PLAYER_HEAD) return false;
            if (y < min) min = y;
            if (y > max) max = y;
        }
        return max - min <= 1;
    }

    /** Is nobody else's claim under this footprint? (Absent claims gadget: land is free.) */
    private static boolean plotUnclaimed(GadgetContext ctx, String owner, int x, int z, int pw, int pd) {
        try {
            JsonObject q = new JsonObject();
            q.addProperty("action", "check_rect");
            q.addProperty("owner", owner);
            q.addProperty("x1", x - 1);
            q.addProperty("z1", z - 1);
            q.addProperty("x2", x + pw);
            q.addProperty("z2", z + pd);
            return ctx.invoke("gadget:claims", q).get("ok").getAsBoolean();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Nearest usable, unclaimed plot to the centre, searching outward; null if none within 32. */
    private static int[] findPlot(GadgetContext ctx, String owner, World w, int cx, int cz, int pw, int pd) {
        for (int r = 4; r <= 32; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) for (int dz = -r; dz <= r; dz += 2) {
                if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                if (plotOk(w, cx + dx, cz + dz, pw, pd) && plotUnclaimed(ctx, owner, cx + dx, cz + dz, pw, pd))
                    return new int[]{ cx + dx, cz + dz };
            }
        }
        return null;
    }

    /** Stake the land under a plot for its owner - the 3-block barrier around every
     *  block of the house, drawn once, up front, where everyone can see the line. */
    private static void stakePlot(GadgetContext ctx, String owner, int x, int z, int pw, int pd) {
        try {
            JsonObject q = new JsonObject();
            q.addProperty("action", "stake");
            q.addProperty("owner", owner);
            q.addProperty("x1", x);
            q.addProperty("z1", z);
            q.addProperty("x2", x + pw - 1);
            q.addProperty("z2", z + pd - 1);
            ctx.invoke("gadget:claims", q);
        } catch (Throwable ignored) { }
    }

    /** Give a plot back when a build is abandoned, so failed sites don't clutter the map. */
    private static void unstakePlot(GadgetContext ctx, String owner, int x, int z, int pw, int pd) {
        try {
            JsonObject q = new JsonObject();
            q.addProperty("action", "unstake");
            q.addProperty("owner", owner);
            q.addProperty("x1", x);
            q.addProperty("z1", z);
            q.addProperty("x2", x + pw - 1);
            q.addProperty("z2", z + pd - 1);
            ctx.invoke("gadget:claims", q);
        } catch (Throwable ignored) { }
    }

    // The house library, fetched from the ledger and kept warm. scripts/blueprints.mjs
    // seeds and pushes it; villages picks what a member can afford; this raises it.
    private static final Map<String, JsonObject> BP_CACHE = new HashMap<String, JsonObject>();
    private static long bpCacheAt = 0;

    private static JsonObject blueprint(GadgetContext ctx, String bpId) {
        if (bpId == null || bpId.isEmpty()) return null;
        long now = System.currentTimeMillis();
        if (now - bpCacheAt > 600_000) { BP_CACHE.clear(); bpCacheAt = now; }
        JsonObject bp = BP_CACHE.get(bpId);
        if (bp != null) return bp;
        try {
            JsonObject q = new JsonObject();
            q.addProperty("action", "get");
            q.addProperty("id", bpId);
            bp = ctx.invoke("gadget:blueprints", q);
            if (bp != null && bp.has("blocks")) BP_CACHE.put(bpId, bp);
            return bp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Resolve one blueprint material token against the bag: $PLANKS/$LOG bend to
     *  whatever wood they gathered, anything else is taken literally. Null: bag empty. */
    private static Material matOf(JsonObject rec, String token) {
        if (token.equals("$PLANKS") || token.equals("$SLAB") || token.equals("$FENCE")) return plankOf(rec);
        if (token.equals("$LOG")) {
            for (JsonElement el : arr(rec, "inventory")) {
                Material m = Material.matchMaterial(gets(el.getAsJsonObject(), "item", ""));
                if (m != null && isLog(m)) return m;
            }
            return plankOf(rec);   // out of whole logs: a plank corner beats no corner
        }
        Material m = Material.matchMaterial(token);
        return m != null && count(rec, m) > 0 ? m : null;
    }

    private static boolean jobBuild(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        JsonObject ask = rec.has("asked") && rec.get("asked").isJsonObject() ? rec.getAsJsonObject("asked") : null;
        if (ask == null) { finishJob(rec, e, "nothing to build"); return true; }
        World w = e.getWorld();
        JsonObject at = ask.getAsJsonObject("at");
        boolean isHouse = "build_house".equals(gets(ask, "kind", "build_inn"));
        JsonObject bp = isHouse ? blueprint(ctx, gets(ask, "blueprint", null)) : null;
        if (isHouse && (bp == null || !bp.has("blocks") || !bp.get("blocks").isJsonArray())) {
            rec.remove("asked");
            rec.addProperty("noBuildUntil", System.currentTimeMillis() + 600000);
            finishJob(rec, e, "lost the drawings for the house");
            return true;
        }
        int pw = isHouse ? geti(bp, "w", 5) : INN_W;
        int pd = isHouse ? geti(bp, "d", 5) : INN_D;
        String claimOwner = isHouse ? "npc:" + id : "village:" + gets(ask, "village", "");
        String what = isHouse ? gets(bp, "name", "a house") : "the inn";
        if (!ask.has("plotOk")) {
            // the village named a spot; the builder chooses the actual ground, like
            // anyone would - open, level, and on nobody else's land
            int[] plot = findPlot(ctx, claimOwner, w, geti(at, "x", 0), geti(at, "z", 0), pw, pd);
            if (plot == null) {
                rec.remove("asked");
                rec.addProperty("noBuildUntil", System.currentTimeMillis() + 600000);
                finishJob(rec, e, "no clear ground for " + what);
                return true;
            }
            at.addProperty("x", plot[0]);
            at.addProperty("z", plot[1]);
            ask.addProperty("plotOk", true);
            // the line is drawn the moment the first block is coming: the plot and the
            // three blocks around every edge of it belong to the builder now
            stakePlot(ctx, claimOwner, plot[0], plot[1], pw, pd);
        }
        int ox = geti(at, "x", 0), oz = geti(at, "z", 0);
        int oy = w.getHighestBlockYAt(ox + pw / 2, oz + pd / 2, HeightMap.MOTION_BLOCKING_NO_LEAVES);   // the plot's level
        int i = geti(job, "i", 0);
        JsonArray bpBlocks = isHouse ? bp.getAsJsonArray("blocks") : null;
        int[] b;
        String token = null;
        if (isHouse) {
            if (i < bpBlocks.size()) {
                JsonObject blk = bpBlocks.get(i).getAsJsonObject();
                b = new int[]{ geti(blk, "dx", 0), geti(blk, "dy", 0), geti(blk, "dz", 0), geti(blk, "dy", 0) == 0 ? 0 : 1 };
                token = gets(blk, "m", "$PLANKS");
            } else {
                b = null;
            }
        } else {
            b = innBlock(i);
        }
        if (b == null && isHouse) {
            // finished: it is home now. A bed if there is wool for one.
            if (count(rec, Material.WHITE_WOOL) >= 3 && plankOf(rec) != null) {
                Material pl = plankOf(rec);
                take(rec, Material.WHITE_WOOL, 3);
                take(rec, pl, 3);
                w.getBlockAt(ox + 1, oy + 1, oz + pd - 2).setType(Material.WHITE_BED);
            }
            JsonObject house = xyzOf(ox, oy + 1, oz);
            rec.add("house", house);
            JsonObject home = new JsonObject();
            home.addProperty("world", w.getName());
            home.addProperty("x", ox + pw / 2);
            home.addProperty("y", oy + 1);
            home.addProperty("z", oz + pd / 2);
            rec.add("home", home);
            try {
                NpcData d = ctx.plugin().npcManager().get(id);
                if (d != null) { d.home = new Location(w, ox + pw / 2, oy + 1, oz + pd / 2); ctx.plugin().npcManager().save(); }
            } catch (Throwable ignored) { }
            JsonObject ev = new JsonObject();
            ev.addProperty("npcId", id);
            ev.addProperty("blueprint", gets(bp, "id", ""));
            ev.addProperty("village", gets(ask, "village", ""));
            ev.add("at", house);
            ctx.plugin().bridge().broadcastEvent("house_built", ev);
            rec.remove("asked");
            practise(rec, "building", 3.0);
            feedNeed(rec, "craft", 8);
            finishJob(rec, e, "raised " + what);
            return true;
        }
        if (b == null) {
            // finished: furnish it. A chest (the village store), a bench, and beds if there is wool.
            int cx = ox + 1, cz = oz + INN_D - 2;
            w.getBlockAt(cx, oy + 1, cz).setType(Material.CHEST);
            w.getBlockAt(ox + INN_W - 2, oy + 1, cz).setType(Material.CRAFTING_TABLE);
            int beds = 0;
            for (int k = 0; k < 2; k++) {
                if (count(rec, Material.WHITE_WOOL) >= 3 && plankOf(rec) != null) {
                    Material pl = plankOf(rec);
                    take(rec, Material.WHITE_WOOL, 3);
                    take(rec, pl, 3);
                    Block bed = w.getBlockAt(ox + 1 + k * 2, oy + 1, oz + 1);
                    bed.setType(Material.WHITE_BED);
                    beds++;
                }
            }
            JsonObject done = new JsonObject();
            done.addProperty("action", "inn_built");
            done.addProperty("village", gets(ask, "village", ""));
            done.add("at", xyzOf(ox, oy + 1, oz));
            done.add("chest", xyzOf(cx, oy + 1, cz));
            done.addProperty("beds", beds);
            done.addProperty("builder", id);
            try { ctx.invoke("gadget:villages", done); } catch (Throwable ignored) { }
            rec.remove("asked");
            practise(rec, "building", 3.0);
            feedNeed(rec, "craft", 8);
            finishJob(rec, e, "raised the inn");
            return true;
        }
        int bx = ox + b[0], by = oy + b[1], bz = oz + b[2];
        // stand within reach of the block
        JsonObject t = obj(job, "target");
        if (Math.abs(geti(t, "x", 0) - bx) > 3 || Math.abs(geti(t, "z", 0) - bz) > 3) {
            int sx = bx + (b[0] < pw / 2 ? -2 : 2), sz = bz + (b[2] < pd / 2 ? -2 : 2);
            setTarget(job, sx, w.getHighestBlockYAt(sx, sz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, sz);
        }
        int tr = travel(ctx, e, id, job, 4.5, false);
        if (tr < 0) {
            int fails = geti(ask, "fails", 0) + 1;
            ask.addProperty("fails", fails);
            if (fails >= 3) {
                unstakePlot(ctx, claimOwner, ox, oz, pw, pd);
                rec.remove("asked");
                rec.addProperty("noBuildUntil", System.currentTimeMillis() + 600000);
                finishJob(rec, e, "gave up on the plot for now");
            } else {
                finishJob(rec, e, "could not reach the plot");
            }
            return true;
        }
        if (tr == 0) return false;
        int need = pace(rec, "building", 2);
        int wt = geti(job, "wait", 0) + 1;
        if (wt < need) { job.addProperty("wait", wt); return false; }
        job.addProperty("wait", 0);
        Block target = w.getBlockAt(bx, by, bz);
        if (b[3] == 0 && !target.getType().isAir() && !target.isPassable()) {
            // floor on existing ground: nothing to place
            job.addProperty("i", i + 1);
            return false;
        }
        Material pl = isHouse ? matOf(rec, token) : plankOf(rec);
        // an empty bag pauses the build - the ask and the claim stay, and the job
        // resumes past the blocks already standing once they have gathered more
        if (pl == null) { finishJob(rec, e, "ran out of materials"); return true; }
        for (Entity n : target.getWorld().getNearbyEntities(target.getLocation().add(0.5, 0.5, 0.5), 0.6, 1.2, 0.6)) {
            if (n instanceof LivingEntity) { job.addProperty("i", i + 1); return false; }    // never wall anyone in
        }
        if (!target.getType().isAir() && target.isPassable()) target.setType(Material.AIR);
        if (target.getType().isAir()) {
            take(rec, pl, 1);
            target.setType(pl);
            String pn = pl.name();
            boolean stone = pn.contains("COBBLE") || pn.contains("STONE") || pn.contains("BRICK");
            w.playSound(target.getLocation(), stone ? org.bukkit.Sound.BLOCK_STONE_PLACE : org.bukkit.Sound.BLOCK_WOOD_PLACE, 0.7f, 1.0f);
            if (e instanceof LivingEntity) ((LivingEntity) e).swingMainHand();
        }
        job.addProperty("i", i + 1);
        rec.addProperty("activity", "building " + what + " (" + (i + 1) + " blocks)");
        return false;
    }

    // ---- confront: walk over to whoever crossed your line and say so to their face.
    // gadget:claims raises the alert; the walk, the look and the words happen here. No
    // violence - the memory of it lives in the claims hostility ledger, and what grows
    // out of a third or fourth crossing is the director's story to tell.

    private static boolean jobConfront(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        JsonObject alert = rec.has("alert") && rec.get("alert").isJsonObject() ? rec.getAsJsonObject("alert") : null;
        if (alert == null) { finishJob(rec, e, "nothing to confront"); return true; }
        String who = gets(alert, "who", "someone");
        if (geti(job, "beats", 0) > 90) {
            rec.remove("alert");
            finishJob(rec, e, "let it go");
            return true;
        }
        // if the trespasser is still around, walk at them, not at where they were
        Entity them = null;
        for (Entity n : e.getNearbyEntities(48, 24, 48)) {
            String nn = n instanceof org.bukkit.entity.Player ? ((org.bukkit.entity.Player) n).getName() : n.getCustomName() == null ? null : org.bukkit.ChatColor.stripColor(n.getCustomName());
            if (who.equals(nn)) { them = n; break; }
        }
        if (them != null) {
            JsonObject t = obj(job, "target");
            Location l = them.getLocation();
            if (Math.abs(geti(t, "x", 0) - l.getBlockX()) > 4 || Math.abs(geti(t, "z", 0) - l.getBlockZ()) > 4) {
                setTarget(job, l.getBlockX(), l.getBlockY(), l.getBlockZ());
            }
        }
        int tr = travel(ctx, e, id, job, 3.0, false);
        if (tr == 0) return false;
        if (tr > 0 && them != null) {
            Location look = e.getLocation();
            look.setDirection(them.getLocation().toVector().subtract(look.toVector()));
            e.setRotation(look.getYaw(), look.getPitch());
        }
        if (tr > 0) {
            try {
                JsonObject a = new JsonObject();
                a.addProperty("id", id);
                a.addProperty("text", them != null
                        ? "I saw you cross my line, " + who + ". This ground is mine. Don't do it again."
                        : "Whoever was on my land - I know, and I remember.");
                ctx.invoke("npc_say", a);
            } catch (Throwable ignored) { }
            feedNeed(rec, "social", 2);
        }
        rec.remove("alert");
        finishJob(rec, e, tr > 0 ? "had words with " + who : "could not catch the trespasser");
        return true;
    }

    private static JsonObject xyzOf(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    // ---- lodge: a bed for the night, paid for

    private static boolean jobLodge(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        World w = e.getWorld();
        if (!isNight(w)) { finishJob(rec, e, "slept at the inn"); return true; }
        String phase = gets(job, "phase", "start");
        if (phase.equals("start")) {
            JsonObject v = nearestInn(places(ctx), e, 240);
            if (v == null) { finishJob(rec, e, "no inn near"); return true; }
            JsonObject inn = v.getAsJsonObject("inn");
            setTarget(job, geti(inn, "x", 0) + 2, geti(inn, "y", 64), geti(inn, "z", 0) + 2);
            job.addProperty("village", gets(v, "id", ""));
            job.addProperty("phase", "go");
            rec.addProperty("activity", "heading to the inn at " + gets(v, "name", "the village"));
            return false;
        }
        if (phase.equals("go")) {
            int tr = travel(ctx, e, id, job, 3, false);
            if (tr < 0) { finishJob(rec, e, "could not reach the inn"); return true; }
            if (tr == 0) return false;
            // pay the house. Members sleep free; a stranger leaves something worth having.
            JsonObject v = null;
            for (JsonObject p : places(ctx)) if (gets(job, "village", "").equals(gets(p, "id", ""))) v = p;
            if (v != null && !memberOf(v, id)) {
                JsonObject coin = cheapest(rec, 0.5);
                org.bukkit.inventory.Inventory till = chestAt(w, v.has("store") ? v.getAsJsonObject("store") : null);
                if (coin == null || till == null) { finishJob(rec, e, "could not pay for a bed"); return true; }
                Material m = Material.matchMaterial(gets(coin, "item", ""));
                take(rec, m, 1);
                till.addItem(new ItemStack(m, 1));
                JsonObject ev = new JsonObject();
                ev.addProperty("npcId", id);
                ev.addProperty("village", gets(v, "id", ""));
                ev.addProperty("paid", m.name());
                ctx.plugin().bridge().broadcastEvent("inn_stay", ev);
            }
            job.addProperty("phase", "sleep");
            rec.addProperty("activity", "asleep at the inn");
            try {
                JsonObject pose = new JsonObject();
                pose.addProperty("npcId", id);
                pose.addProperty("pose", "sleeping");
                ctx.invoke("npc_pose", pose);
            } catch (Throwable ignored) { }
            return false;
        }
        // sleep: a full night's rest heals faster than the road does
        if (e instanceof LivingEntity && beats % 2 == 0) {
            LivingEntity le = (LivingEntity) e;
            if (le.getHealth() < 20 && geti(rec, "hunger", 20) >= 6) le.setHealth(Math.min(20, le.getHealth() + 1));
        }
        return false;
    }

    // ---- market: deal with the village store

    private static boolean jobMarket(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job) {
        World w = e.getWorld();
        String phase = gets(job, "phase", "start");
        if (phase.equals("start")) {
            JsonObject v = nearestInn(places(ctx), e, 240);
            if (v == null || !v.has("store")) { finishJob(rec, e, "no market near"); return true; }
            JsonObject st = v.getAsJsonObject("store");
            setTarget(job, geti(st, "x", 0), geti(st, "y", 64), geti(st, "z", 0));
            job.addProperty("village", gets(v, "id", ""));
            job.addProperty("phase", "go");
            rec.addProperty("activity", "going to market at " + gets(v, "name", "the village"));
            return false;
        }
        int tr = travel(ctx, e, id, job, 3, false);
        if (tr < 0) { finishJob(rec, e, "could not reach the market"); return true; }
        if (tr == 0) return false;
        JsonObject v = null;
        for (JsonObject p : places(ctx)) if (gets(job, "village", "").equals(gets(p, "id", ""))) v = p;
        org.bukkit.inventory.Inventory store = v == null ? null : chestAt(w, v.getAsJsonObject("store"));
        if (store == null) { finishJob(rec, e, "the store is gone"); return true; }
        double markup = memberOf(v, id) ? 1.0 : 1.25;
        int hunger = geti(rec, "hunger", 20);
        int bought = 0, sold = 0;

        // buy what I need, paying with what I value least, at the store's flat prices
        if (hunger < 16) {
            for (int slot = 0; slot < store.getSize() && bought < 3; slot++) {
                ItemStack s = store.getItem(slot);
                if (s == null || nutrition(s.getType()) <= 0) continue;
                double price = baseValue(s.getType()) * markup;
                double paid = 0;
                List<JsonObject> coins = new ArrayList<JsonObject>();
                while (paid < price) {
                    JsonObject coin = cheapest(rec, 0.2);
                    if (coin == null || nutrition(Material.matchMaterial(gets(coin, "item", ""))) > 0) break;
                    Material cm = Material.matchMaterial(gets(coin, "item", ""));
                    take(rec, cm, 1);
                    store.addItem(new ItemStack(cm, 1));
                    paid += baseValue(cm);
                }
                if (paid < price) break;                 // could not afford it; what was put down stays paid
                s.setAmount(s.getAmount() - 1);
                store.setItem(slot, s.getAmount() <= 0 ? null : s);
                give(rec, s.getType(), 1);
                bought++;
            }
        }
        // sell surplus the store is short of: cheap bulk I am carrying a lot of
        for (JsonElement el : new ArrayList<JsonElement>()) { }
        JsonArray inv = arr(rec, "inventory");
        for (int k = inv.size() - 1; k >= 0 && sold < 16; k--) {
            JsonObject s = inv.get(k).getAsJsonObject();
            Material m = Material.matchMaterial(gets(s, "item", ""));
            if (m == null || m.getMaxDurability() > 0 || m == Material.PLAYER_HEAD) continue;
            if (count(rec, m) < 24 || nutrition(m) > 0) continue;
            if (store.containsAtLeast(new ItemStack(m), 32)) continue;
            int n = Math.min(8, count(rec, m) - 16);
            if (n <= 0) continue;
            if (!store.addItem(new ItemStack(m, n)).isEmpty()) break;
            take(rec, m, n);
            sold += n;
            // the store pays in kind: the most useful thing it holds that I lack
            for (int slot = 0; slot < store.getSize(); slot++) {
                ItemStack pay = store.getItem(slot);
                if (pay == null) continue;
                if (baseValue(pay.getType()) > baseValue(m) * n) continue;
                if (count(rec, pay.getType()) > 0) continue;
                pay.setAmount(pay.getAmount() - 1);
                store.setItem(slot, pay.getAmount() <= 0 ? null : pay);
                give(rec, pay.getType(), 1);
                break;
            }
        }
        if (bought + sold > 0) practise(rec, "trading", 1.0);
        rec.addProperty("noMarketUntil", System.currentTimeMillis() + 240000);
        finishJob(rec, e, bought + sold > 0 ? "bought " + bought + ", sold " + sold : "nothing to do at market");
        return true;
    }

    // ---- visit: go stand near somebody

    private static JsonObject nearestOther(JsonObject rec, Entity e, List<JsonObject> everyone, NpcManager npcs, double within) {
        JsonObject best = null;
        double bestD = within;
        for (JsonObject o : everyone) {
            String oid = gets(o, "id", "");
            if (oid.equals(gets(rec, "id", "")) || !o.get("alive").getAsBoolean()) continue;
            NpcData od = npcs.get(oid);
            Entity oe = od == null || od.dead ? null : npcs.resolveEntity(od);
            if (oe == null || !oe.getWorld().equals(e.getWorld())) continue;
            double d = oe.getLocation().distance(e.getLocation());
            if (d < bestD) { bestD = d; best = o; }
        }
        return best;
    }

    private static boolean jobVisit(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job, List<JsonObject> everyone) {
        NpcManager npcs = ctx.plugin().npcManager();
        String phase = gets(job, "phase", "start");
        if (phase.equals("start")) {
            JsonObject other = nearestOther(rec, e, everyone, npcs, 160);
            if (other == null) { finishJob(rec, e, "nobody around"); return true; }
            Entity oe = npcs.resolveEntity(npcs.get(gets(other, "id", "")));
            Location l = oe.getLocation();
            setTarget(job, l.getBlockX(), l.getBlockY(), l.getBlockZ());
            job.addProperty("who", gets(other, "id", ""));
            job.addProperty("phase", "go");
            rec.addProperty("activity", "going to see " + gets(other, "name", "someone"));
            return false;
        }
        int t = travel(ctx, e, id, job, 4, false);
        if (t < 0) { finishJob(rec, e, "could not find them"); return true; }
        if (t == 0) return false;
        int stay = geti(job, "stay", 0) + 1;
        job.addProperty("stay", stay);
        rec.addProperty("activity", "with " + gets(job, "who", "someone"));
        if (stay >= 30) { finishJob(rec, e, "spent time with " + gets(job, "who", "someone")); return true; }
        return false;
    }

    // ---- trade: find someone, work out if there is a deal, make it

    private static boolean jobTrade(GadgetContext ctx, JsonObject rec, Entity e, String id, JsonObject job, List<JsonObject> everyone) {
        NpcManager npcs = ctx.plugin().npcManager();
        String phase = gets(job, "phase", "start");
        if (phase.equals("start")) {
            // Go to whoever actually has what I am short of, not whoever is closest. A
            // hungry person walks past the woodcutter to reach the one with the meat.
            int hunger = geti(rec, "hunger", 20);
            JsonObject other = null;
            double bestScore = 0;
            for (JsonObject o : everyone) {
                String oid = gets(o, "id", "");
                if (oid.equals(id) || !o.get("alive").getAsBoolean()) continue;
                NpcData od = npcs.get(oid);
                Entity oe = od == null || od.dead ? null : npcs.resolveEntity(od);
                if (oe == null || !oe.getWorld().equals(e.getWorld())) continue;
                double dist = oe.getLocation().distance(e.getLocation());
                if (dist > 160) continue;
                double worth = 0;
                for (JsonElement el : arr(o, "inventory")) {
                    JsonObject s = el.getAsJsonObject();
                    Material m = Material.matchMaterial(gets(s, "item", ""));
                    if (m == null) continue;
                    if (hunger < 16 && nutrition(m) <= 0) continue;
                    worth = Math.max(worth, valueTo(rec, m));
                }
                if (worth <= 0) continue;
                double score = worth / (1 + dist / 40.0);
                if (score > bestScore) { bestScore = score; other = o; }
            }
            if (other == null) { finishJob(rec, e, "nobody has what I need"); rec.addProperty("noTradeUntil", System.currentTimeMillis() + 300000); return true; }
            Entity oe = npcs.resolveEntity(npcs.get(gets(other, "id", "")));
            Location l = oe.getLocation();
            setTarget(job, l.getBlockX(), l.getBlockY(), l.getBlockZ());
            job.addProperty("who", gets(other, "id", ""));
            job.addProperty("phase", "go");
            rec.addProperty("activity", "going to trade with " + gets(other, "name", "someone"));
            return false;
        }
        int t = travel(ctx, e, id, job, 4, false);
        if (t < 0) { finishJob(rec, e, "could not reach them"); return true; }
        if (t == 0) return false;

        JsonObject other = null;
        for (JsonObject o : everyone) if (gets(o, "id", "").equals(gets(job, "who", ""))) other = o;
        if (other == null) { finishJob(rec, e, "they left"); return true; }

        // What I want most: the thing that serves my worst need. Food if hungry; a tool I
        // lack if not; otherwise nothing in particular and this is a social call.
        int hunger = geti(rec, "hunger", 20);
        Material want = null;
        double wantV = 0;
        for (JsonElement el : arr(other, "inventory")) {
            JsonObject s = el.getAsJsonObject();
            Material m = Material.matchMaterial(gets(s, "item", ""));
            if (m == null) continue;
            if (hunger < 16 && nutrition(m) <= 0) continue;
            double v = valueTo(rec, m);
            if (v > wantV) { wantV = v; want = m; }
        }
        if (want == null) {
            finishJob(rec, e, "they have nothing I need");
            rec.addProperty("noTradeUntil", System.currentTimeMillis() + 300000);
            return true;
        }
        // What they would accept: anything of mine they value at least as much as what I
        // take - by THEIR valuation - minus what my trading skill can talk off.
        double talk = 1.0 - 0.05 * Math.max(0, aptitude(rec, "trading")) + 0.05 * Math.max(0, aptitude(other, "trading"));
        talk = Math.max(0.6, Math.min(1.4, talk));
        double askV = valueTo(other, want) * talk;
        Material offer = null;
        double offerCost = Double.MAX_VALUE;
        int offerN = 0;
        for (JsonElement el : arr(rec, "inventory")) {
            JsonObject s = el.getAsJsonObject();
            Material m = Material.matchMaterial(gets(s, "item", ""));
            if (m == null || m == want) continue;
            int have = geti(s, "count", 0);
            double theirs = valueTo(other, m);
            if (theirs <= 0) continue;
            int n = (int) Math.ceil(askV / theirs);
            if (n > have) continue;
            double myCost = valueTo(rec, m) * n;
            if (myCost >= wantV) continue;              // not worth it to me
            if (myCost < offerCost) { offerCost = myCost; offer = m; offerN = n; }
        }
        if (offer == null) {
            finishJob(rec, e, "nothing they want from me");
            rec.addProperty("noTradeUntil", System.currentTimeMillis() + 300000);
            return true;
        }
        // deal
        take(rec, offer, offerN);
        take(other, want, 1);
        give(rec, want, 1);
        give(other, offer, offerN);
        practise(rec, "trading", 1.0);
        practise(other, "trading", 1.0);
        feedNeed(rec, "social", 3);
        feedNeed(other, "social", 3);
        // a deal done is a deal done; nobody haggles with the same neighbour all afternoon
        rec.addProperty("noTradeUntil", System.currentTimeMillis() + 300000);
        other.addProperty("noTradeUntil", System.currentTimeMillis() + 300000);
        try { save(ctx, other); } catch (Throwable ignored) { }
        JsonObject ev = new JsonObject();
        ev.addProperty("from", gets(rec, "id", ""));
        ev.addProperty("to", gets(other, "id", ""));
        ev.addProperty("gave", offerN + " " + offer.name());
        ev.addProperty("got", "1 " + want.name());
        ctx.plugin().bridge().broadcastEvent("npc_trade", ev);
        finishJob(rec, e, "traded " + offerN + " " + offer.name().toLowerCase() + " for " + want.name().toLowerCase());
        return true;
    }

    // ------------------------------------------------------------------ arrivals

    private static final String[] NAMES = {
        "Ada", "Bram", "Cass", "Dorn", "Edda", "Finn", "Greta", "Hal", "Ilse", "Jory", "Kit", "Lena",
        "Mott", "Nell", "Osk", "Pim", "Quill", "Rue", "Sten", "Tova", "Ulf", "Vey", "Wil", "Yara", "Zed",
        "Arno", "Bea", "Cole", "Dale", "Eryn", "Fay", "Gus", "Hoyt", "Ida", "Jem", "Kai", "Liv", "Mab",
        "Ned", "Orla", "Pell", "Rook", "Sol", "Tam", "Una", "Vik", "Wyn", "Yve", "Ash", "Bel"
    };
    private static final String[] NEED_KINDS = { "explore", "social", "wealth", "craft" };

    /** Real accounts with distinctive skins, so nobody is a default Steve. */
    private static final String[] SKINS = {
        "Notch", "jeb_", "Dinnerbone", "Grumm", "Searge", "xisumavoid", "Mumbo", "Grian", "Iskall85", "Etho",
        "Docm77", "Zisteau", "Keralis", "BdoubleO100", "Tango", "impulseSV", "Cubfan135", "falsesymmetry",
        "Stressmonster101", "ZombieCleo", "GoodTimesWithScar", "PearlescentMoon", "GeminiTay", "Rendog",
        "Joehills", "Hypnotizd", "VintageBeef", "Welsknight", "xBCrafted", "Zedaph", "Smallishbeans", "fWhip",
        "LDShadowLady", "Solidarity", "Skizzleman", "Kryticalc", "EthosLab"
    };

    private static String pickSkin(List<JsonObject> everyone) {
        for (int i = 0; i < 30; i++) {
            String s = SKINS[rand(SKINS.length)];
            boolean used = false;
            for (JsonObject o : everyone) if (s.equalsIgnoreCase(gets(o, "skin", ""))) used = true;
            if (!used) return s;
        }
        return SKINS[rand(SKINS.length)];
    }

    /** Two dice, centred: most people are ordinary, a few are remarkable either way. */
    private static int rollAbility() {
        int v = rand(4) + rand(4) - 3;      // -3..3, peaked at 0
        return Math.max(-3, Math.min(3, v));
    }

    private static boolean nameTaken(List<JsonObject> everyone, String name) {
        for (JsonObject o : everyone) if (name.equalsIgnoreCase(gets(o, "name", ""))) return true;
        return false;
    }

    /** Make up a person and spawn them at world spawn. */
    private JsonObject arrive(GadgetContext ctx, List<JsonObject> everyone) throws Exception {
        String name = null;
        for (int i = 0; i < 40 && name == null; i++) {
            String n = NAMES[rand(NAMES.length)];
            if (!nameTaken(everyone, n)) name = n;
        }
        if (name == null) name = NAMES[rand(NAMES.length)] + (rand(90) + 10);
        String id = name.toLowerCase();
        for (JsonObject o : everyone) if (id.equals(gets(o, "id", ""))) id = id + (rand(900) + 100);

        JsonObject args = new JsonObject();
        args.addProperty("action", "spawn");
        args.addProperty("id", id);
        args.addProperty("name", name);
        JsonObject ab = new JsonObject();
        for (String s : new String[]{ "str", "dex", "con", "wis", "int", "cha" }) ab.addProperty(s, rollAbility());
        args.add("abilities", ab);
        args.addProperty("skill", SKILLS[rand(SKILLS.length)]);
        args.addProperty("skin", pickSkin(everyone));
        JsonObject need = new JsonObject();
        need.addProperty("kind", NEED_KINDS[rand(NEED_KINDS.length)]);
        need.addProperty("value", 10);
        args.add("need", need);
        JsonObject rec = run(args, ctx);
        JsonObject ev = new JsonObject();
        ev.addProperty("npcId", id);
        ev.addProperty("name", name);
        ctx.plugin().bridge().broadcastEvent("npc_arrived", ev);
        return rec;
    }

    /** At five in the morning, up to five strangers walk in from the edge of the world. */
    private void dawn(GadgetContext ctx, List<JsonObject> everyone) {
        try {
            World w = ctx.world(null);
            long t = w.getTime();
            long day = w.getFullTime() / 24000L;
            org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
            org.bukkit.NamespacedKey k = ctx.key("people-last-dawn");
            Long last = pdc.get(k, org.bukkit.persistence.PersistentDataType.LONG);
            if (t < 23000 || (last != null && last.longValue() == day)) return;
            pdc.set(k, org.bukkit.persistence.PersistentDataType.LONG, Long.valueOf(day));
            int alive = 0;
            for (JsonObject o : everyone) if (o.has("alive") && o.get("alive").getAsBoolean()) alive++;
            int n = rand(6);                                   // 0..5
            n = Math.min(n, Math.max(0, populationCap - alive));
            for (int i = 0; i < n; i++) arrive(ctx, everyone);
        } catch (Throwable ignored) { }
    }

    private static int populationCap = 40;

    // ------------------------------------------------------------------ the beat

    private void tickOne(GadgetContext ctx, JsonObject rec, List<JsonObject> everyone) throws Exception {
        NpcManager npcs = ctx.plugin().npcManager();
        String id = gets(rec, "id", "");
        NpcData d = npcs.get(id);
        if (d == null) return;
        if (d.dead) {
            if (rec.get("alive").getAsBoolean()) {
                rec.addProperty("alive", false);
                rec.remove("job");
                rec.addProperty("activity", "dead");
                try { grave(ctx, rec, d); } catch (Throwable t) { rec.addProperty("grave", "failed: " + String.valueOf(t.getMessage())); }
                save(ctx, rec);
            }
            return;
        }
        Entity e = npcs.resolveEntity(d);
        if (e == null) {
            // The registry has lost the body but it may well still be standing in the
            // world - this happened to Wren, next to Mara, in full view. Find it by its
            // tag and hand it back, rather than leave a living person untickable.
            org.bukkit.NamespacedKey key = npcs.key();
            for (World w : ctx.server().getWorlds()) {
                for (Entity cand : w.getEntities()) {
                    if (!cand.isValid()) continue;
                    String tag = cand.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
                    if (id.equals(tag)) { e = cand; break; }
                }
                if (e != null) break;
            }
            if (e == null) { rec.addProperty("activity", "missing"); save(ctx, rec); return; }
            d.entityUuid = e.getUniqueId();
            d.lastLocation = e.getLocation().clone();
            npcs.save();
            rec.addProperty("activity", "found again");
        }
        normalise(rec);

        // --- gravity, when nothing else is moving them. The walker handles falls on the
        // move; standing still over nothing (a block mined out from underfoot, a ledge
        // that was not there a moment ago) must still end on the ground, and a long
        // drop must hurt the way it hurts a player.
        if (!walking(ctx, id)) {
            if (!e.hasGravity()) e.setGravity(true);
            Location at = e.getLocation();
            World w = e.getWorld();
            int fx = at.getBlockX(), fz = at.getBlockZ();
            int fy = at.getBlockY();
            boolean inWater = w.getBlockAt(fx, fy, fz).getType() == Material.WATER;
            if (!inWater) {
                int fell = 0;
                while (fell < 24 && fy - 1 > w.getMinHeight()) {
                    Block below = w.getBlockAt(fx, fy - 1, fz);
                    Material bm = below.getType();
                    if (!below.isPassable() && bm != Material.WATER) break;
                    if (bm == Material.WATER) { fy--; break; }
                    fy--; fell++;
                }
                if (fell > 0) {
                    Location down = new Location(w, at.getX(), fy, at.getZ(), at.getYaw(), at.getPitch());
                    e.teleport(down);
                    if (fell > 3 && e instanceof LivingEntity && w.getBlockAt(fx, fy, fz).getType() != Material.WATER) {
                        ((LivingEntity) e).damage(fell - 3);
                        w.playSound(down, org.bukkit.Sound.ENTITY_PLAYER_BIG_FALL, 0.8f, 1.0f);
                    }
                }
            }
        }

        // --- the body, by the game's own rules
        JsonObject job = rec.has("job") && rec.get("job").isJsonObject() ? rec.getAsJsonObject("job") : null;
        int hunger = geti(rec, "hunger", 20);
        double sat = getd(rec, "saturation", 5.0);
        Location now = e.getLocation();
        // movement costs: climbing and swimming, measured from where they were last beat
        if (rec.has("lastPos") && rec.get("lastPos").isJsonObject()) {
            JsonObject lp = rec.getAsJsonObject("lastPos");
            double dy = now.getY() - getd(lp, "y", now.getY());
            double dxz = Math.sqrt(Math.pow(now.getX() - getd(lp, "x", now.getX()), 2) + Math.pow(now.getZ() - getd(lp, "z", now.getZ()), 2));
            if (dxz < 12) {                                   // a sane step, not a respawn
                if (dy > 0.5) exhaust(rec, EX_JUMP * Math.round(dy));
                if (e instanceof LivingEntity && ((LivingEntity) e).isInWater()) exhaust(rec, EX_SWIM * dxz);
            }
        }
        JsonObject lp = new JsonObject();
        lp.addProperty("x", now.getX()); lp.addProperty("y", now.getY()); lp.addProperty("z", now.getZ());
        rec.add("lastPos", lp);
        // hits taken since last beat
        if (e instanceof LivingEntity) {
            double hpNow = ((LivingEntity) e).getHealth();
            double hpWas = getd(rec, "hp", hpNow);
            if (hpNow < hpWas - 0.01) exhaust(rec, EX_HURT);
        }
        // settle exhaustion into saturation, then hunger
        double ex = getd(rec, "exhaustion", 0);
        while (ex >= 4.0) {
            ex -= 4.0;
            if (sat > 0) sat = Math.max(0, sat - 1.0);
            else hunger = Math.max(0, hunger - 1);
        }
        rec.addProperty("exhaustion", ex);

        rec.addProperty("ripeNear", ripeNear(e, 48) != null || ripeAtField(e.getWorld(), rec) != null);
        // Wheat is not food. Three of it is a loaf, at a bench.
        if (hunger <= EAT_AT && foodInBag(rec) == 0 && count(rec, Material.WHEAT) >= 3) {
            Block bench = nearestBlock(e, 4, 2, 2, new BlockTest() { public boolean ok(Block b) { return b.getType() == Material.CRAFTING_TABLE; } });
            if (bench != null || count(rec, Material.CRAFTING_TABLE) > 0) {
                if (take(rec, Material.WHEAT, 3)) { give(rec, Material.BREAD, 1); practise(rec, "crafting", 0.5); }
            } else if (job == null) {
                job = startJob(rec, "craft");
                job.addProperty("want", "BREAD");
            }
        }
        // eat: when hungry, or when hurt and food would start the healing
        boolean hurt = e instanceof LivingEntity && ((LivingEntity) e).getHealth() < 20;
        if (hunger <= EAT_AT || (hurt && hunger < 18)) {
            Material meal = null;
            int bestWaste = Integer.MAX_VALUE;
            for (JsonElement el : arr(rec, "inventory")) {
                Material m = Material.matchMaterial(gets(el.getAsJsonObject(), "item", ""));
                if (m == null) continue;
                int n = nutrition(m);
                if (n <= 0) continue;
                int waste = Math.max(0, n - (20 - hunger));
                if (waste < bestWaste) { bestWaste = waste; meal = m; }
            }
            if (meal != null && take(rec, meal, 1)) {
                hunger = Math.min(20, hunger + nutrition(meal));
                sat = Math.min(hunger, sat + saturationOf(meal));
                rec.addProperty("lastMeal", meal.name());
            }
        }

        if (e instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) e;
            // natural regeneration, at the game's prices
            if (le.getHealth() < 20) {
                if (hunger >= 20 && sat > 0) {
                    // every 10 ticks, 1 hp for 6 exhaustion: two per beat
                    for (int k = 0; k < 2 && le.getHealth() < 20 && sat > 0; k++) {
                        le.setHealth(Math.min(20, le.getHealth() + 1));
                        exhaust(rec, EX_REGEN);
                        double ex2 = getd(rec, "exhaustion", 0);
                        while (ex2 >= 4.0) { ex2 -= 4.0; if (sat > 0) sat = Math.max(0, sat - 1.0); else hunger = Math.max(0, hunger - 1); }
                        rec.addProperty("exhaustion", ex2);
                    }
                } else if (hunger >= 18 && beats % 4 == 0) {
                    le.setHealth(Math.min(20, le.getHealth() + 1));
                    exhaust(rec, EX_REGEN);
                }
            }
            // starvation: a point every four seconds, and how far it goes is the difficulty's call
            if (hunger <= 0 && beats % 4 == 0) {
                double floor;
                switch (e.getWorld().getDifficulty()) {
                    case PEACEFUL: floor = 20; break;
                    case EASY: floor = 10; break;
                    case NORMAL: floor = 1; break;
                    default: floor = 0; break;
                }
                if (le.getHealth() > floor) {
                    le.damage(1.0);
                    JsonObject ev = new JsonObject();
                    ev.addProperty("npcId", id);
                    ev.addProperty("name", d.name);
                    ctx.plugin().bridge().broadcastEvent("npc_starving", ev);
                }
            }
            if (le.isInWater()) practise(rec, "swimming", BEAT_MIN);
        }
        rec.addProperty("hunger", hunger);
        rec.addProperty("saturation", Math.round(sat * 10.0) / 10.0);

        // --- the third need drifts, and is fed by arriving somewhere new
        JsonObject need = obj(rec, "need");
        String kind = gets(need, "kind", "explore");
        if (!kind.equals("wealth") && beats % 180 == 0) {
            need.addProperty("value", Math.max(0, getd(need, "value", 10) - 1));
        }
        if (kind.equals("explore")) {
            if (markSeen(ctx, id, e.getLocation())) feedNeed(rec, "explore", 4);
        }
        if (kind.equals("social") && beats % 30 == 0) {
            JsonObject o = nearestOther(rec, e, everyone, npcs, 8);
            if (o != null) feedNeed(rec, "social", 1);
        }

        // --- things on the ground come along, as they would for a player walking over them
        for (Entity n : e.getNearbyEntities(2.5, 2.0, 2.5)) {
            if (!(n instanceof org.bukkit.entity.Item)) continue;
            ItemStack s = ((org.bukkit.entity.Item) n).getItemStack();
            // a person's head keeps who it was, so it can be carried and given back
            if (s.getType() == Material.PLAYER_HEAD && s.getItemMeta() != null) {
                String who = s.getItemMeta().getPersistentDataContainer()
                        .get(npcs.key(), org.bukkit.persistence.PersistentDataType.STRING);
                if (who != null && !isFull(rec)) {
                    JsonObject head = new JsonObject();
                    head.addProperty("item", "PLAYER_HEAD");
                    head.addProperty("count", 1);
                    head.addProperty("npcId", who);
                    arr(rec, "inventory").add(head);
                    n.remove();
                    JsonObject ev = new JsonObject();
                    ev.addProperty("npcId", id);
                    ev.addProperty("head", who);
                    ctx.plugin().bridge().broadcastEvent("npc_head_carried", ev);
                    continue;
                }
            }
            int left = give(rec, s.getType(), s.getAmount());
            if (left == 0) {
                n.remove();
                e.getWorld().playSound(e.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.3f, 1.2f);
            } else if (left < s.getAmount()) {
                s.setAmount(left);
                ((org.bukkit.entity.Item) n).setItemStack(s);
            }
        }

        // --- somebody walked up to you. Stop, turn to them, give them a moment. This is
        // what makes talking possible: chat only reaches an NPC within a few blocks, and
        // nobody can hold a conversation with someone marching off to a tree.
        org.bukkit.entity.Player near = null;
        for (Entity n : e.getNearbyEntities(4, 3, 4)) {
            if (n instanceof org.bukkit.entity.Player) { near = (org.bukkit.entity.Player) n; break; }
        }
        // A starving person does not stop to chat. They say so and keep moving.
        if (near != null && hunger <= 7) near = null;
        // ...but not all day. A minute of your time, then back to work for a while.
        if (near != null && geti(rec, "attendUntil", 0) > beats) {
            if (!rec.has("attendSince")) rec.addProperty("attendSince", beats);
            if (beats - geti(rec, "attendSince", beats) > 60) {
                rec.addProperty("attendUntil", 0);
                rec.addProperty("attendPauseUntil", beats + 120);
                rec.remove("attendSince");
                near = null;
            }
        } else if (near == null) {
            rec.remove("attendSince");
        }
        if (near != null && geti(rec, "attendPauseUntil", 0) > beats) near = null;
        if (near != null) {
            rec.addProperty("attendUntil", beats + 20);
            Location look = e.getLocation();
            look.setDirection(near.getLocation().toVector().subtract(look.toVector()));
            e.setRotation(look.getYaw(), look.getPitch());
        }
        if (geti(rec, "attendUntil", 0) > beats && hunger > 7) {
            if (walking(ctx, id)) {
                JsonObject a = new JsonObject();
                a.addProperty("action", "stop");
                a.addProperty("npcId", id);
                try { ctx.invoke("gadget:navigate", a); } catch (Throwable ignored) { }
            }
            rec.addProperty("activity", near != null ? "talking with " + near.getName() : "waiting");
            rec.addProperty("hp", hp(e));
            rec.addProperty("hunger", hunger);
            save(ctx, rec);
            return;
        }

        // --- somebody on their land? claims put an alert on the sheet; go have words.
        // A promise to a player still comes first.
        JsonObject alert = rec.has("alert") && rec.get("alert").isJsonObject() ? rec.getAsJsonObject("alert") : null;
        if (alert != null && System.currentTimeMillis() > (long) getd(alert, "until", 0)) {
            rec.remove("alert");
            alert = null;
        }
        if (alert != null && "trespass".equals(gets(alert, "kind", ""))
                && (job == null || (!"confront".equals(gets(job, "kind", "")) && !job.has("assigned")))) {
            job = startJob(rec, "confront");
            setTarget(job, geti(alert, "x", 0), geti(alert, "y", 64), geti(alert, "z", 0));
            rec.addProperty("activity", "going to see about " + gets(alert, "who", "someone") + " on their land");
        }

        // --- the work
        if (job == null) {
            // something worth having lying nearby? food when hungry, tools any time
            String next = null;
            org.bukkit.entity.Item want = nearestDrop(rec, e, hunger < 16 ? 24 : 12, hunger < 16);
            if (want == null && isNight(e.getWorld()) && hunger >= 6 && nearestInn(places(ctx), e, 240) != null
                    && !rec.has("assigned")) {
                job = startJob(rec, "lodge");
            } else if (want != null) {
                job = startJob(rec, "pickup");
                Location l = want.getLocation();
                setTarget(job, l.getBlockX(), l.getBlockY(), l.getBlockZ());
                rec.addProperty("activity", "picking up " + want.getItemStack().getType().name().toLowerCase().replace('_', ' '));
            } else {
                next = choose(rec, e, everyone);
                job = startJob(rec, next);
                if (next.equals("rest")) rec.addProperty("activity", "resting");
            }
        }
        String jk = gets(job, "kind", "rest");
        job.addProperty("beats", geti(job, "beats", 0) + 1);
        String sk = skillOf(jk);
        if (sk != null) practise(rec, sk, BEAT_MIN);

        boolean done;
        if (jk.equals("hunt")) done = jobHunt(ctx, rec, e, id, job);
        else if (jk.equals("chop")) done = jobChop(ctx, rec, e, id, job);
        else if (jk.equals("mine")) done = jobMine(ctx, rec, e, id, job);
        else if (jk.equals("fish")) done = jobFish(ctx, rec, e, id, job);
        else if (jk.equals("farm")) done = jobFarm(ctx, rec, e, id, job);
        else if (jk.equals("explore")) done = jobExplore(ctx, rec, e, id, job);
        else if (jk.equals("craft")) done = jobCraft(ctx, rec, e, id, job);
        else if (jk.equals("visit")) done = jobVisit(ctx, rec, e, id, job, everyone);
        else if (jk.equals("trade")) done = jobTrade(ctx, rec, e, id, job, everyone);
        else if (jk.equals("build")) done = jobBuild(ctx, rec, e, id, job);
        else if (jk.equals("confront")) done = jobConfront(ctx, rec, e, id, job);
        else if (jk.equals("lodge")) done = jobLodge(ctx, rec, e, id, job);
        else if (jk.equals("market")) done = jobMarket(ctx, rec, e, id, job);
        else if (jk.equals("pickup")) {
            int t = travel(ctx, e, id, job, 1.2, false);
            done = t != 0 || geti(job, "beats", 0) > 60;
            if (done) finishJob(rec, e, t > 0 ? "picked something up" : "could not reach it");
        }
        else {
            // rest: stand still; hp comes back by itself on a full stomach
            done = geti(job, "beats", 0) >= 20 || hp(e) >= 20;
            if (done) finishJob(rec, e, "rested");
        }
        // a job that ran far too long is stuck somewhere we did not foresee
        if (!done && rec.has("job") && geti(rec.getAsJsonObject("job"), "beats", 0) > 600) {
            finishJob(rec, e, "gave up");
        }

        // --- bookkeeping the console reads
        rec.addProperty("hp", hp(e));
        rec.addProperty("happiness", happiness(rec, e));
        JsonObject needs = new JsonObject();
        needs.addProperty("hp", hp(e));
        needs.addProperty("hunger", hunger);
        needs.addProperty(kind, thirdNeed(rec));
        rec.add("needs", needs);
        rec.addProperty("fedState", hunger <= 0 ? "starving" : (hunger <= 6 ? "hungry" : "fed"));
        save(ctx, rec);
    }

    /**
     * A grave where they fell: a stone under, their head on top wearing their own face, a
     * sign with their name. The head the plugin dropped is taken up into the grave - it
     * is still the one vessel of their return, now stamped on the skull block itself -
     * and the grave is a place anyone can find.
     */
    private static void grave(GadgetContext ctx, JsonObject rec, NpcData d) throws Exception {
        Location at = d.lastLocation != null ? d.lastLocation : d.home;
        if (at == null || at.getWorld() == null) return;
        World w = at.getWorld();
        org.bukkit.NamespacedKey key = ctx.plugin().npcManager().key();
        String id = d.id;
        // the head item the plugin dropped, if it is still lying there
        for (Entity n : w.getNearbyEntities(at, 10, 6, 10)) {
            if (!(n instanceof org.bukkit.entity.Item)) continue;
            ItemStack s = ((org.bukkit.entity.Item) n).getItemStack();
            if (s.getType() != Material.PLAYER_HEAD || s.getItemMeta() == null) continue;
            String who = s.getItemMeta().getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
            if (id.equals(who)) { at = n.getLocation(); n.remove(); break; }
        }
        int x = at.getBlockX(), z = at.getBlockZ();
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block base = w.getBlockAt(x, y, z);
        if (base.getType() == Material.WATER && d.home != null) {
            // drowned: the marker goes up at home, where people will see it
            x = d.home.getBlockX(); z = d.home.getBlockZ();
            y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            base = w.getBlockAt(x, y, z);
        }
        if (base.getType() == Material.WATER) return;           // the sea keeps its own
        if (base.isPassable()) base = base.getRelative(0, -1, 0);
        if (d.lastLocation == null) d.lastLocation = new Location(w, x, y, z);
        Block stone = base.getRelative(0, 1, 0);
        Block skull = base.getRelative(0, 2, 0);
        stone.setType(Material.COBBLESTONE);
        skull.setType(Material.PLAYER_HEAD);
        org.bukkit.block.BlockState st = skull.getState();
        if (st instanceof org.bukkit.block.Skull) {
            org.bukkit.block.Skull sk = (org.bukkit.block.Skull) st;
            String skin = gets(rec, "skin", null);
            if (skin != null) {
                try { sk.setPlayerProfile(Bukkit.createProfile(skin)); } catch (Throwable ignored) { }
            }
            sk.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, id);
            sk.update(true, false);
        }
        // a sign on the side with their name
        Block signAt = stone.getRelative(0, 0, 1);
        if (signAt.isPassable()) {
            signAt.setType(Material.OAK_WALL_SIGN);
            org.bukkit.block.BlockState ss = signAt.getState();
            if (ss instanceof org.bukkit.block.Sign) {
                org.bukkit.block.Sign sign = (org.bukkit.block.Sign) ss;
                sign.getSide(org.bukkit.block.sign.Side.FRONT).line(0, net.kyori.adventure.text.Component.text("Here lies"));
                sign.getSide(org.bukkit.block.sign.Side.FRONT).line(1, net.kyori.adventure.text.Component.text(d.name));
                sign.getSide(org.bukkit.block.sign.Side.FRONT).line(2, net.kyori.adventure.text.Component.text(gets(rec, "village", "").replace("village-", "")));
                sign.update(true, false);
            }
        }
        JsonObject place = new JsonObject();
        place.addProperty("id", "grave-" + id);
        place.addProperty("name", d.name + "'s grave");
        place.addProperty("kind", "grave");
        place.add("origin", xyzOf(x, stone.getY(), z));
        place.addProperty("npcId", id);
        place.addProperty("builtBy", "world");
        place.addProperty("description", "Where " + d.name + " fell. Their head rests on the stone.");
        JsonObject put = new JsonObject();
        put.addProperty("collection", "places");
        put.add("record", place);
        ctx.invoke("ledger_put", put);
        rec.add("grave", xyzOf(x, stone.getY(), z));
        JsonObject ev = new JsonObject();
        ev.addProperty("npcId", id);
        ev.addProperty("name", d.name);
        ev.addProperty("x", x);
        ev.addProperty("y", stone.getY());
        ev.addProperty("z", z);
        ctx.plugin().bridge().broadcastEvent("npc_grave", ev);
    }

    /**
     * Break the head on a grave and you get what they were carrying, and the head
     * itself - the real one, stamped with who it was - not the blank skull vanilla
     * would hand you. The bag is cleared so the loot exists exactly once.
     */
    private void watchGraves(GadgetContext ctx, List<JsonObject> everyone) {
        List<JsonObject> places = places(ctx);
        NpcManager npcs = ctx.plugin().npcManager();
        for (JsonObject p : places) {
            if (!"grave".equals(gets(p, "kind", "")) || (p.has("looted") && p.get("looted").getAsBoolean())) continue;
            JsonObject o = p.getAsJsonObject("origin");
            World w = ctx.world(null);
            int x = geti(o, "x", 0), y = geti(o, "y", 64), z = geti(o, "z", 0);
            Block skull = w.getBlockAt(x, y + 1, z);
            if (!skull.getChunk().isLoaded()) continue;
            if (skull.getType() == Material.PLAYER_HEAD) continue;
            // the head is gone: somebody broke it
            String who = gets(p, "npcId", "");
            JsonObject rec = null;
            for (JsonObject r : everyone) if (who.equals(gets(r, "id", ""))) rec = r;
            NpcData d = npcs.get(who);
            Location at = new Location(w, x + 0.5, y + 1.2, z + 0.5);
            // vanilla dropped a nameless skull; take it back
            for (Entity n : w.getNearbyEntities(at, 2.5, 2.5, 2.5)) {
                if (!(n instanceof org.bukkit.entity.Item)) continue;
                ItemStack s = ((org.bukkit.entity.Item) n).getItemStack();
                if (s.getType() != Material.PLAYER_HEAD) continue;
                ItemMeta meta = s.getItemMeta();
                String tag = meta == null ? null : meta.getPersistentDataContainer().get(npcs.key(), org.bukkit.persistence.PersistentDataType.STRING);
                if (tag == null) n.remove();
            }
            int dropped = 0;
            if (d != null) { w.dropItemNaturally(at, npcs.headOf(d)); dropped++; }
            if (rec != null) {
                for (JsonElement el : arr(rec, "inventory")) {
                    JsonObject s = el.getAsJsonObject();
                    Material m = Material.matchMaterial(gets(s, "item", ""));
                    if (m == null) continue;
                    if (m == Material.PLAYER_HEAD && s.has("npcId")) {
                        NpcData other = npcs.get(gets(s, "npcId", ""));
                        if (other != null) { w.dropItemNaturally(at, npcs.headOf(other)); dropped++; }
                        continue;
                    }
                    ItemStack it = stackOf(s);
                    if (it == null) continue;
                    it.setAmount(Math.max(1, geti(s, "count", 1)));
                    w.dropItemNaturally(at, it);
                    dropped++;
                }
                rec.add("inventory", new JsonArray());
                rec.addProperty("looted", true);
                try { save(ctx, rec); } catch (Throwable ignored) { }
            }
            p.addProperty("looted", true);
            p.addProperty("description", gets(p, "description", "") + " The grave has been opened.");
            try {
                JsonObject put = new JsonObject();
                put.addProperty("collection", "places");
                put.add("record", p);
                ctx.invoke("ledger_put", put);
            } catch (Throwable ignored) { }
            JsonObject ev = new JsonObject();
            ev.addProperty("npcId", who);
            ev.addProperty("drops", dropped);
            ev.addProperty("x", x); ev.addProperty("y", y); ev.addProperty("z", z);
            ctx.plugin().bridge().broadcastEvent("grave_opened", ev);
        }
    }

    /**
     * Heads. The plugin drops one when a person dies, stamped with who it was. Vanilla
     * would sweep it up with the rest of the litter after five minutes; a person's head
     * is not litter, so every one lying in the world is kept fresh each beat.
     */
    private void keepHeads(GadgetContext ctx) {
        org.bukkit.NamespacedKey key = ctx.plugin().npcManager().key();
        for (World w : ctx.server().getWorlds()) {
            for (org.bukkit.entity.Item it : w.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                ItemStack s = it.getItemStack();
                if (s.getType() != Material.PLAYER_HEAD) continue;
                ItemMeta meta = s.getItemMeta();
                if (meta == null) continue;
                String who = meta.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.STRING);
                if (who == null) continue;
                it.setTicksLived(1);
                it.setUnlimitedLifetime(true);
            }
        }
    }

    /**
     * A crop only grows in a loaded chunk. A player's farm does not grow while they are
     * away either - but a player's farm is usually at their base, and these people range
     * two hundred blocks for a pig. Hold the chunk under every living person's field so
     * the wheat they planted is wheat when they come back.
     */
    private void keepFields(GadgetContext ctx, List<JsonObject> everyone) {
        World w = ctx.world(null);
        java.util.Set<Long> want = new java.util.HashSet<Long>();
        for (JsonObject rec : everyone) {
            if (!(rec.has("alive") && rec.get("alive").getAsBoolean())) continue;
            if (!rec.has("field") || !rec.get("field").isJsonObject()) continue;
            JsonObject f = rec.getAsJsonObject("field");
            int cx = geti(f, "x", 0) >> 4, cz = geti(f, "z", 0) >> 4;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) want.add((((long) (cx + dx)) << 32) ^ ((cz + dz) & 0xFFFFFFFFL));
        }
        for (Long k : want) {
            int cx = (int) (k >> 32), cz = (int) (k & 0xFFFFFFFFL);
            w.addPluginChunkTicket(cx, cz, ctx.plugin());
        }
        FIELD_TICKETS.removeAll(want);
        for (Long k : FIELD_TICKETS) {
            int cx = (int) (k >> 32), cz = (int) (k & 0xFFFFFFFFL);
            w.removePluginChunkTicket(cx, cz, ctx.plugin());
        }
        FIELD_TICKETS.clear();
        FIELD_TICKETS.addAll(want);
    }

    private static final java.util.Set<Long> FIELD_TICKETS = new java.util.HashSet<Long>();

    private void beat(GadgetContext ctx) {
        beats++;
        try {
            List<JsonObject> everyone = roster(ctx);
            for (JsonObject rec : everyone) {
                if (!rec.has("id")) continue;
                try {
                    tickOne(ctx, rec, everyone);
                } catch (Throwable t) {
                    rec.addProperty("activity", "error: " + String.valueOf(t.getMessage()));
                    try { save(ctx, rec); } catch (Throwable ignored) { }
                }
            }
            if (beats % 5 == 0) dawn(ctx, everyone);
            if (beats % 60 == 0) keepHeads(ctx);
            if (beats % 30 == 0) keepFields(ctx, everyone);
            watchGraves(ctx, everyone);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ entry point

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("beats", beats);
            JsonArray people = new JsonArray();
            for (JsonObject rec : roster(ctx)) {
                JsonObject o = new JsonObject();
                o.addProperty("id", gets(rec, "id", ""));
                o.addProperty("name", gets(rec, "name", ""));
                o.addProperty("alive", rec.has("alive") && rec.get("alive").getAsBoolean());
                o.addProperty("hp", getd(rec, "hp", 20));
                o.addProperty("hunger", geti(rec, "hunger", 20));
                o.addProperty("need", gets(obj(rec, "need"), "kind", "") + " " + thirdNeed(rec));
                o.addProperty("happiness", geti(rec, "happiness", 0));
                o.addProperty("doing", gets(rec, "activity", "-"));
                o.addProperty("job", rec.has("job") ? gets(rec.getAsJsonObject("job"), "kind", "-") : "-");
                o.addProperty("stacks", stacksUsed(rec));
                JsonObject sk = new JsonObject();
                for (int i = 0; i < SKILLS.length; i++) {
                    int p = skill(rec, SKILLS[i]);
                    if (p > 0) sk.addProperty(SKILLS[i], p);
                }
                o.add("skills", sk);
                people.add(o);
            }
            out.add("people", people);
            return out;
        }

        /** Spawn one person at world spawn from a roster entry. */
        if (action.equals("spawn")) {
            String id = args.get("id").getAsString();
            String name = gets(args, "name", id);
            World w = ctx.world(null);
            Location sp = w.getSpawnLocation();
            int ox = rand(7) - 3, oz = rand(7) - 3;
            int x = sp.getBlockX() + ox, z = sp.getBlockZ() + oz;
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;

            JsonObject spawn = new JsonObject();
            spawn.addProperty("id", id);
            spawn.addProperty("name", name);
            spawn.addProperty("entityType", "MANNEQUIN");
            spawn.addProperty("defense", "fight");
            String skin = args.has("skin") ? args.get("skin").getAsString() : pickSkin(roster(ctx));
            spawn.addProperty("skin", skin);
            spawn.addProperty("world", w.getName());
            spawn.addProperty("x", x + 0.5);
            spawn.addProperty("y", y);
            spawn.addProperty("z", z + 0.5);
            spawn.addProperty("snap", true);
            JsonObject home = new JsonObject();
            home.addProperty("world", w.getName());
            home.addProperty("x", x);
            home.addProperty("y", y);
            home.addProperty("z", z);
            spawn.add("home", home);
            ctx.invoke("npc_spawn", spawn);

            JsonObject rec = new JsonObject();
            rec.addProperty("id", id);
            rec.addProperty("name", name);
            rec.addProperty("skin", skin);
            if (args.has("bio")) rec.addProperty("bio", args.get("bio").getAsString());
            if (args.has("abilities")) rec.add("abilities", args.getAsJsonObject("abilities"));
            if (args.has("need")) rec.add("need", args.getAsJsonObject("need"));
            rec.add("home", home);
            normalise(rec);
            // the one skill they came with
            if (args.has("skill")) {
                JsonObject s = obj(obj(rec, "skills"), args.get("skill").getAsString());
                s.addProperty("minutes", 1.0);
                s.addProperty("points", 1);
            }
            rec.addProperty("alive", true);
            rec.addProperty("activity", "just arrived");
            rec.addProperty("hp", 20);
            rec.addProperty("happiness", 100);
            save(ctx, rec);
            return rec;
        }

        /**
         * Somebody asked, and they said yes. Put the job at the front of their day, and
         * remember the promise so they can be held to it. This is how a conversation
         * becomes a task: the actor calls it when it agrees to something.
         */
        if (action.equals("assign")) {
            String npcId = args.get("npcId").getAsString();
            String jobKind = args.get("job").getAsString();
            String[] known = { "hunt", "fish", "farm", "chop", "mine", "explore", "craft", "trade", "visit", "market", "build", "rest" };
            boolean ok = false;
            for (String k : known) if (k.equals(jobKind)) ok = true;
            if (!ok) throw new IllegalArgumentException("no such job: " + jobKind + " (one of " + String.join(", ", known) + ")");
            JsonObject q = new JsonObject();
            q.addProperty("collection", "npcs");
            q.addProperty("id", npcId);
            JsonObject rec = ctx.invoke("ledger_get", q);
            NpcData d = ctx.plugin().npcManager().get(npcId);
            Entity e = d == null || d.dead ? null : ctx.plugin().npcManager().resolveEntity(d);
            if (e != null) hold(e, null);
            JsonObject job = startJob(rec, jobKind);
            if (args.has("want")) job.addProperty("want", args.get("want").getAsString().toUpperCase(java.util.Locale.ROOT));
            job.addProperty("assigned", true);
            if (args.has("for")) job.addProperty("for", args.get("for").getAsString());
            JsonObject promise = new JsonObject();
            promise.addProperty("job", jobKind);
            if (args.has("want")) promise.addProperty("want", args.get("want").getAsString());
            if (args.has("for")) promise.addProperty("to", args.get("for").getAsString());
            if (args.has("promise")) promise.addProperty("said", args.get("promise").getAsString());
            promise.addProperty("at", java.time.Instant.now().toString());
            JsonArray promises = arr(rec, "promises");
            promises.add(promise);
            while (promises.size() > 8) promises.remove(0);
            rec.addProperty("assigned", jobKind);
            rec.addProperty("attendUntil", 0);
            rec.addProperty("activity", "off to " + jobKind + (args.has("for") ? " for " + args.get("for").getAsString() : ""));
            save(ctx, rec);
            JsonObject out = new JsonObject();
            out.addProperty("npcId", npcId);
            out.addProperty("job", jobKind);
            out.addProperty("started", true);
            return out;
        }

        /** Raise graves for everyone dead who has none. */
        if (action.equals("graves")) {
            JsonArray made = new JsonArray();
            for (JsonObject rec : roster(ctx)) {
                if (rec.has("alive") && rec.get("alive").getAsBoolean()) continue;
                if (rec.has("grave") && rec.get("grave").isJsonObject()) continue;
                NpcData d = ctx.plugin().npcManager().get(gets(rec, "id", ""));
                if (d == null) continue;
                try {
                    grave(ctx, rec, d);
                    save(ctx, rec);
                    made.add(gets(rec, "name", "?") + " at " + (rec.has("grave") ? rec.getAsJsonObject("grave").toString() : "?"));
                } catch (Throwable t) {
                    made.add(gets(rec, "name", "?") + ": " + String.valueOf(t.getMessage()));
                }
            }
            JsonObject out = new JsonObject();
            out.add("graves", made);
            return out;
        }

        if (action.equals("arrive")) {
            // a stranger walks in now, as one would at dawn
            return arrive(ctx, roster(ctx));
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
        populationCap = args.has("populationCap") ? args.get("populationCap").getAsInt() : 40;
        TASK_ID = Integer.valueOf(ctx.runTimer(BEAT_TICKS, new Runnable() {
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
        out.addProperty("beatTicks", BEAT_TICKS);
        return out;
    }
}
