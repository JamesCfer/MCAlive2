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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lineage. An Ancient decides for itself when to call kin into its line, and what that
 * kin is for.
 *
 * The decision is arithmetic, not a threshold and not a model call. Every beat each
 * living progenitor asks the same question its people ask about everything else: how
 * badly is something needed, how much does my nature care about it, and can we afford
 * it. Three terms feed the answer.
 *
 *   NECESSITY  - the work nobody is doing. Each unfilled role in the line is a reason
 *                to call someone. This is what lets a cold, solitary founder still grow:
 *                Vecna does not want company, but he wants the wood cut.
 *   BELONGING  - the ordinary human pull, weighted by warmth. Lliira feels it hard and
 *                early; Orcus barely feels it at all. It saturates around four kin,
 *                because belonging reads as line-mates standing near you.
 *   PURPOSE    - the sense that there is more to be done than one pair of hands can do.
 *
 * Against those sits RESTRAINT, which rises with each kin already called and with the
 * founder's own hunger. Nobody sensibly calls another mouth to a camp while starving.
 *
 * The whole thing is gated by CONFIDENCE: the line's store must actually hold enough
 * banked food to carry the extra mouth. Below that the decision cannot fire at all, no
 * matter how much anyone wants it. That is the point. A line grows exactly as fast as it
 * has learned to feed itself, and a line that never solves food stays one person and
 * dies with them.
 *
 * The role for the new kin is chosen the same way, by what the line is short of right
 * now, read from the store and the roster rather than from a fixed script.
 *
 * Followers inherit their founder's alignment, ethos and long want, and their
 * personality axes with a small drift, so a line has a recognisable character that is
 * not a clone. They are generation 1; their bloodline records the parent.
 */
public class Lineage implements GadgetContract {

    private static Integer TASK_ID = null;
    private static int beats = 0;
    private static int begotten = 0;
    private static final Map<String, JsonObject> STORES = new HashMap<String, JsonObject>();
    private static final Map<String, String> LAST_REASON = new LinkedHashMap<String, String>();
    private static int maxKin = 5;
    private static int reservePerMouth = 20;
    private static long seed = 987654321L;

    private static int reserve() { return reservePerMouth; }

    private static int rand(int n) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        int v = (int) ((seed >>> 33) % n);
        return v < 0 ? -v : v;
    }

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("lineage-generation");
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
            if (inner != null && inner.getClass().getName().contains("Lineage")) { t.cancel(); killed++; }
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

    // ------------------------------------------------------------------ the larder

    /** Same table hunger eats by, so "can we feed them" means the same thing everywhere. */
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

    private static int provision(Inventory inv) {
        if (inv == null) return 0;
        int total = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null) continue;
            total += nutrition(s.getType()) * s.getAmount();
        }
        return total;
    }

    private static int countOf(Inventory inv, String... names) {
        if (inv == null) return 0;
        int total = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null) continue;
            String n = s.getType().name();
            for (int j = 0; j < names.length; j++) {
                if (n.contains(names[j])) { total += s.getAmount(); break; }
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ the roles

    private static String roleVerb(String role) {
        if (role.equals("forager")) return "bringing in meat and wild food";
        if (role.equals("farmer")) return "keeping the field";
        if (role.equals("woodcutter")) return "cutting and hauling timber";
        if (role.equals("miner")) return "working the shaft for stone and ore";
        return "raising and repairing the camp";
    }

    private static String roleWant(String role) {
        if (role.equals("forager")) return "Keep the store stocked with food, however far I have to walk for it.";
        if (role.equals("farmer")) return "Get a field planted and keep it turning over, so the line stops living hand to mouth.";
        if (role.equals("woodcutter")) return "Keep timber coming in - nothing else here gets built without it.";
        if (role.equals("miner")) return "Sink a shaft and bring up stone, coal and iron.";
        return "Put a roof over this line and keep it standing.";
    }

    private static String roleLook(String role) {
        if (role.equals("forager")) return "Weather-beaten and long-legged, always carrying more than looks comfortable.";
        if (role.equals("farmer")) return "Dirt worked permanently into the knuckles; moves slowly and finishes things.";
        if (role.equals("woodcutter")) return "Heavy through the shoulders, with a woodsman squint and resin in the hair.";
        if (role.equals("miner")) return "Pale from the shaft, with stone dust in every seam of their clothes.";
        return "Calloused hands, an eye for a level line, and pockets full of odds and ends.";
    }

    /**
     * What is this line short of? Read from the store and the living roster, in the order
     * survival actually demands: food first, then the field that ends the hand-to-mouth,
     * then timber, shelter, and only then the shaft.
     */
    private static List<String> gaps(Map<String, Integer> filled, Inventory inv, int mouths) {
        List<String> out = new ArrayList<String>();
        int food = provision(inv);
        boolean hungryLine = food < mouths * reserve();
        boolean haveForager = filled.containsKey("forager");
        boolean haveFarmer = filled.containsKey("farmer");
        boolean haveWood = filled.containsKey("woodcutter");
        boolean haveBuilder = filled.containsKey("builder");
        boolean haveMiner = filled.containsKey("miner");

        if (!haveForager) out.add("forager");
        if (!haveFarmer) out.add("farmer");
        if (hungryLine && !out.isEmpty()) {
            if (!haveWood) out.add("woodcutter");
            if (!haveBuilder) out.add("builder");
            if (!haveMiner) out.add("miner");
        } else {
            if (!haveWood && countOf(inv, "_LOG") < 32) out.add("woodcutter");
            if (!haveBuilder) out.add("builder");
            if (!haveMiner) out.add("miner");
        }
        return out;
    }

    // ------------------------------------------------------------------ the judgement

    private static int axis(JsonObject rec, String name) {
        if (!rec.has("personality") || !rec.get("personality").isJsonObject()) return 0;
        JsonObject p = rec.getAsJsonObject("personality");
        return p.has(name) ? p.get(name).getAsInt() : 0;
    }

    private static double needValue(JsonObject rec, String need) {
        if (rec.has("needs") && rec.get("needs").isJsonObject()) {
            JsonObject n = rec.getAsJsonObject("needs");
            if (n.has(need) && !n.get(need).isJsonNull()) return n.get(need).getAsDouble();
        }
        if (need.equals("hunger") && rec.has("hunger") && !rec.get("hunger").isJsonNull()) {
            return rec.get("hunger").getAsDouble();
        }
        return 14;
    }

    /** The same personality weighting the chooser uses, so a line reads consistently. */
    private static double weightOf(String need, int drive, int warmth, int boldness) {
        double w = 1.0;
        if (need.equals("purpose"))   w *= 1 + drive / 3.0 * 0.6 + boldness / 3.0 * 0.2;
        if (need.equals("belonging")) w *= 1 + warmth / 3.0 * 0.8 - drive / 3.0 * 0.2;
        return Math.max(0.25, Math.min(2.5, w));
    }

    private static class Verdict {
        boolean beget;
        String role;
        double pressure, restraint, confidence, necessity, belongingTerm, purposeTerm;
        int kin, mouths, food, needed;
        String reason;
    }

    private Verdict judge(GadgetContext ctx, JsonObject rec, String faction,
                          Map<String, Integer> filled, int kin, int mouths) {
        Verdict v = new Verdict();
        v.kin = kin;
        v.mouths = mouths;

        Inventory inv = chestAt(ctx.world(null), STORES.get(faction));
        v.food = provision(inv);
        v.needed = (mouths + 1) * reserve();
        v.confidence = Math.max(0, Math.min(2.0, v.needed <= 0 ? 0 : (double) v.food / v.needed));

        List<String> open = gaps(filled, inv, mouths);
        v.necessity = open.size() * 1.4;
        v.role = open.isEmpty() ? "builder" : open.get(0);

        int drive = axis(rec, "drive"), warmth = axis(rec, "warmth"), boldness = axis(rec, "boldness");
        double belonging = needValue(rec, "belonging");
        double purpose = needValue(rec, "purpose");
        v.belongingTerm = Math.pow(Math.max(0, 20 - belonging), 2)
                * weightOf("belonging", drive, warmth, boldness) / 100.0;
        v.purposeTerm = Math.pow(Math.max(0, 20 - purpose), 2)
                * weightOf("purpose", drive, warmth, boldness) / 100.0 * 0.6;

        double fed = needValue(rec, "hunger");
        v.restraint = 1.5 + kin * 0.4 + Math.max(0, 12 - fed) * 0.35;
        v.pressure = (v.necessity + v.belongingTerm + v.purposeTerm) * v.confidence;

        if (kin >= maxKin) { v.reason = "line is full (" + kin + "/" + maxKin + ")"; return v; }
        if (v.confidence < 1.0) {
            v.reason = "cannot feed another mouth (" + v.food + "/" + v.needed + " nutrition banked)";
            return v;
        }
        if (fed < 8) {
            v.reason = "founder is too hungry to call anyone (fed " + (int) fed + "/20)";
            return v;
        }
        if (v.pressure <= v.restraint) {
            v.reason = "no pressing need yet (pressure " + round2(v.pressure)
                    + " vs restraint " + round2(v.restraint) + ")";
            return v;
        }
        v.beget = true;
        v.reason = "calling a " + v.role + " (pressure " + round2(v.pressure)
                + " vs restraint " + round2(v.restraint) + ")";
        return v;
    }

    private static double round2(double d) { return Math.round(d * 100.0) / 100.0; }

    // ------------------------------------------------------------------ the calling

    private static final Map<String, String[]> NAMES = new HashMap<String, String[]>();
    static {
        NAMES.put("line-vecna",   new String[]{ "Ashkar", "Nevil", "Sildra", "Corvane", "Ythra" });
        NAMES.put("line-bane",    new String[]{ "Dregan", "Halkor", "Marn", "Vosk", "Ilzeth" });
        NAMES.put("line-lliira",  new String[]{ "Peri", "Tamsin", "Odalie", "Bry", "Fennick" });
        NAMES.put("line-mystra",  new String[]{ "Wren", "Calla", "Osric", "Ilya", "Semra" });
        NAMES.put("line-tiamat",  new String[]{ "Vashka", "Kroth", "Nerissa", "Zaldrik", "Ophis" });
        NAMES.put("line-moradin", new String[]{ "Durn", "Halla", "Brok", "Sigrun", "Torvald" });
        NAMES.put("line-pelor",   new String[]{ "Aurel", "Mira", "Casta", "Elion", "Rhosyn" });
        NAMES.put("line-orcus",   new String[]{ "Gralt", "Hesk", "Umber", "Vretch", "Sallow" });
    }

    /** Inherit an axis with a small drift, so kin resemble their founder without cloning. */
    private int inherit(int parent) {
        int drift = rand(3) - 1;
        int v = parent + drift;
        return Math.max(-3, Math.min(3, v));
    }

    private JsonObject beget(GadgetContext ctx, JsonObject parent, String faction,
                             String role, int slot) throws Exception {
        String line = faction.startsWith("line-") ? faction.substring(5) : faction;
        String id = line + "-" + slot;
        String[] pool = NAMES.get(faction);
        String name = pool != null && slot - 1 < pool.length ? pool[slot - 1] : (line + " " + slot);
        String parentId = parent.get("id").getAsString();

        NpcManager npcs = ctx.plugin().npcManager();
        NpcData pd = npcs.get(parentId);
        Entity pe = pd == null ? null : npcs.resolveEntity(pd);
        Location at = pe != null ? pe.getLocation()
                : (pd != null && pd.home != null ? pd.home : null);
        if (at == null) throw new IllegalStateException("no location for founder " + parentId);
        Location spot = at.clone().add(1 + rand(3), 0, 1 + rand(3));

        JsonObject spawn = new JsonObject();
        spawn.addProperty("id", id);
        spawn.addProperty("name", name);
        spawn.addProperty("entityType", "MANNEQUIN");
        spawn.addProperty("defense", "fight");
        spawn.addProperty("world", spot.getWorld().getName());
        spawn.addProperty("x", spot.getX());
        spawn.addProperty("y", spot.getY());
        spawn.addProperty("z", spot.getZ());
        spawn.addProperty("snap", true);
        JsonObject home = new JsonObject();
        home.addProperty("world", at.getWorld().getName());
        home.addProperty("x", at.getBlockX());
        home.addProperty("y", at.getBlockY());
        home.addProperty("z", at.getBlockZ());
        spawn.add("home", home);
        ctx.invoke("npc_spawn", spawn);

        JsonObject rec = new JsonObject();
        rec.addProperty("id", id);
        rec.addProperty("name", name);
        rec.addProperty("alignment", parent.has("alignment") ? parent.get("alignment").getAsString() : "neutral");
        rec.addProperty("appearance", roleLook(role));
        rec.addProperty("ethos", parent.has("ethos") ? parent.get("ethos").getAsString() : "");
        rec.addProperty("role", role);

        JsonObject pers = new JsonObject();
        pers.addProperty("drive", inherit(axis(parent, "drive")));
        pers.addProperty("warmth", inherit(axis(parent, "warmth")));
        pers.addProperty("boldness", inherit(axis(parent, "boldness")));
        pers.addProperty("composure", inherit(axis(parent, "composure")));
        rec.add("personality", pers);

        JsonArray wants = new JsonArray();
        String longWant = null;
        if (parent.has("wants") && parent.get("wants").isJsonArray()) {
            for (JsonElement e : parent.getAsJsonArray("wants")) {
                JsonObject w = e.getAsJsonObject();
                if (w.has("horizon") && "long".equals(w.get("horizon").getAsString())) {
                    longWant = w.get("text").getAsString();
                }
            }
        }
        if (longWant != null) {
            JsonObject lw = new JsonObject();
            lw.addProperty("horizon", "long");
            lw.addProperty("text", longWant);
            wants.add(lw);
        }
        JsonObject sw = new JsonObject();
        sw.addProperty("horizon", "short");
        sw.addProperty("text", roleWant(role));
        wants.add(sw);
        rec.add("wants", wants);

        rec.add("home", home);
        rec.addProperty("alive", true);
        rec.addProperty("faction", faction);
        JsonObject blood = new JsonObject();
        blood.addProperty("house", faction);
        blood.addProperty("generation", 1);
        JsonArray parents = new JsonArray();
        parents.add(parentId);
        blood.add("parents", parents);
        blood.addProperty("progenitor", false);
        rec.add("bloodline", blood);
        rec.addProperty("activity", roleVerb(role));
        rec.addProperty("hunger", 14);
        rec.addProperty("fedState", "fed");
        JsonObject needs = new JsonObject();
        needs.addProperty("fatigue", 16);
        needs.addProperty("purpose", 10);
        needs.addProperty("curiosity", 12);
        needs.addProperty("safety", 12);
        needs.addProperty("belonging", 8);
        needs.addProperty("shelter", 8);
        needs.addProperty("wealth", 8);
        needs.addProperty("hunger", 14);
        rec.add("needs", needs);

        JsonObject put = new JsonObject();
        put.addProperty("collection", "npcs");
        put.add("record", rec);
        ctx.invoke("ledger_put", put);

        // A farmer is no use if the chooser drags them off to cut wood. Reserve them, and
        // hand the field over so it stops leaning on whoever happens to be standing near.
        if (role.equals("farmer")) {
            try {
                JsonObject res = new JsonObject();
                res.addProperty("action", "reserve");
                JsonArray add = new JsonArray();
                add.add(id);
                res.add("add", add);
                ctx.invoke("gadget:pursuits", res);
            } catch (Throwable ignored) { }
            try {
                JsonObject as = new JsonObject();
                as.addProperty("action", "assign");
                as.addProperty("faction", faction);
                as.addProperty("farmer", id);
                ctx.invoke("gadget:farm", as);
            } catch (Throwable ignored) { }
        }

        begotten++;
        JsonObject ev = new JsonObject();
        ev.addProperty("npcId", id);
        ev.addProperty("name", name);
        ev.addProperty("faction", faction);
        ev.addProperty("role", role);
        ev.addProperty("parent", parentId);
        ev.addProperty("generation", 1);
        ctx.plugin().bridge().broadcastEvent("npc_begotten", ev);
        return rec;
    }

    // ------------------------------------------------------------------ the beat

    private List<JsonObject> roster(GadgetContext ctx) throws Exception {
        JsonObject q = new JsonObject();
        q.addProperty("collection", "npcs");
        JsonArray records = ctx.invoke("ledger_query", q).getAsJsonArray("records");
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonElement el : records) out.add(el.getAsJsonObject());
        return out;
    }

    private static boolean isProgenitor(JsonObject rec) {
        if (rec.has("bloodline") && rec.get("bloodline").isJsonObject()) {
            JsonObject b = rec.getAsJsonObject("bloodline");
            if (b.has("progenitor")) return b.get("progenitor").getAsBoolean();
        }
        return rec.has("id") && rec.get("id").getAsString().indexOf('-') < 0;
    }

    private static boolean alive(JsonObject rec) {
        return !rec.has("alive") || rec.get("alive").getAsBoolean();
    }

    /** Tally one line: how many kin, which slots are taken, which roles are covered. */
    private static void tally(List<JsonObject> all, String faction, Map<String, Integer> filled,
                              boolean[] slots, int[] counts) {
        int kin = 0, mouths = 0;
        for (int i = 0; i < all.size(); i++) {
            JsonObject o = all.get(i);
            if (!o.has("faction") || o.get("faction").isJsonNull()) continue;
            if (!faction.equals(o.get("faction").getAsString())) continue;
            if (!alive(o)) continue;
            mouths++;
            if (isProgenitor(o)) continue;
            kin++;
            String rid = o.get("id").getAsString();
            int dash = rid.lastIndexOf('-');
            if (dash >= 0 && slots != null) {
                try {
                    int s = Integer.parseInt(rid.substring(dash + 1));
                    if (s >= 1 && s < slots.length) slots[s] = true;
                } catch (NumberFormatException ignored) { }
            }
            if (o.has("role") && !o.get("role").isJsonNull()) {
                filled.put(o.get("role").getAsString(), Integer.valueOf(1));
            }
        }
        counts[0] = kin;
        counts[1] = mouths;
    }

    private void beat(GadgetContext ctx) {
        beats++;
        try {
            List<JsonObject> all = roster(ctx);
            for (int i = 0; i < all.size(); i++) {
                JsonObject rec = all.get(i);
                if (!rec.has("id") || !isProgenitor(rec) || !alive(rec)) continue;
                String faction = rec.has("faction") && !rec.get("faction").isJsonNull()
                        ? rec.get("faction").getAsString() : null;
                if (faction == null || !STORES.containsKey(faction)) continue;

                Map<String, Integer> filled = new HashMap<String, Integer>();
                boolean[] slots = new boolean[maxKin + 1];
                int[] counts = new int[2];
                tally(all, faction, filled, slots, counts);

                Verdict v = judge(ctx, rec, faction, filled, counts[0], counts[1]);
                LAST_REASON.put(faction, v.reason);
                if (!v.beget) continue;

                int slot = 0;
                for (int s = 1; s <= maxKin; s++) { if (!slots[s]) { slot = s; break; } }
                if (slot == 0) continue;
                try {
                    beget(ctx, rec, faction, v.role, slot);
                } catch (Throwable t) {
                    LAST_REASON.put(faction, "call failed: " + String.valueOf(t.getMessage()));
                }
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ entry point

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("beats", beats);
            out.addProperty("begotten", begotten);
            out.addProperty("maxKin", maxKin);
            out.addProperty("reservePerMouth", reservePerMouth);
            JsonObject why = new JsonObject();
            for (Map.Entry<String, String> e : LAST_REASON.entrySet()) why.addProperty(e.getKey(), e.getValue());
            out.add("lastDecision", why);
            return out;
        }

        // Explain, without acting: the full arithmetic for every line.
        if (action.equals("why")) {
            JsonObject out = new JsonObject();
            JsonArray lines = new JsonArray();
            List<JsonObject> all = roster(ctx);
            for (int i = 0; i < all.size(); i++) {
                JsonObject rec = all.get(i);
                if (!rec.has("id") || !isProgenitor(rec) || !alive(rec)) continue;
                String faction = rec.has("faction") && !rec.get("faction").isJsonNull()
                        ? rec.get("faction").getAsString() : null;
                if (faction == null) continue;
                Map<String, Integer> filled = new HashMap<String, Integer>();
                int[] counts = new int[2];
                tally(all, faction, filled, null, counts);
                Verdict v = judge(ctx, rec, faction, filled, counts[0], counts[1]);
                JsonObject o = new JsonObject();
                o.addProperty("faction", faction);
                o.addProperty("founder", rec.get("name").getAsString());
                o.addProperty("kin", v.kin);
                o.addProperty("mouths", v.mouths);
                o.addProperty("foodBanked", v.food);
                o.addProperty("foodNeeded", v.needed);
                o.addProperty("confidence", round2(v.confidence));
                o.addProperty("necessity", round2(v.necessity));
                o.addProperty("belongingTerm", round2(v.belongingTerm));
                o.addProperty("purposeTerm", round2(v.purposeTerm));
                o.addProperty("pressure", round2(v.pressure));
                o.addProperty("restraint", round2(v.restraint));
                o.addProperty("wouldCall", v.beget);
                o.addProperty("role", v.role);
                o.addProperty("reason", v.reason);
                lines.add(o);
            }
            out.add("lines", lines);
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
        maxKin = args.has("maxKin") ? args.get("maxKin").getAsInt() : 5;
        reservePerMouth = args.has("reservePerMouth") ? args.get("reservePerMouth").getAsInt() : 20;
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 1200;

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
        out.addProperty("maxKin", maxKin);
        out.addProperty("reservePerMouth", reservePerMouth);
        out.addProperty("lines", STORES.size());
        return out;
    }
}
