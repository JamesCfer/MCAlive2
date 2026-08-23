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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A field, worked forever.
 *
 * Hunting cannot feed this world. Animals only exist where a chunk has newly generated,
 * so meat is a windfall a forager walks days to find - and with walking restored, a
 * round trip costs more time than it buys. A crop is different: it is planted once and
 * gives back every harvest, on ground the line already stands on.
 *
 * The farmer works one cell per beat, walking to it first. Bare ground is tilled, tilled
 * ground is sown, ripe crops are cut and carried to the store - and every harvest returns
 * seed, so the field pays for its own next planting. Tilling costs a hoe and real
 * durability, exactly as it would for a player.
 */
public class Farm implements GadgetContract {

    private static Integer TASK_ID = null;
    private static final Map<String, Field> FIELDS = new HashMap<String, Field>();
    private static int tilled = 0, sown = 0, reaped = 0;
    private static final List<String> LOG = new ArrayList<String>();

    private static class Field {
        String faction;
        String farmer;
        JsonObject chest;
        int x, y, z, size;
        boolean walking;
        int[] heading;
        String lastLook = "-";
    }

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("farm-generation");
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
            if (inner != null && inner.getClass().getName().contains("Farm")) { t.cancel(); killed++; }
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

    private static Inventory chestAt(GadgetContext ctx, JsonObject c) {
        World w = ctx.world(null);
        Block b = w.getBlockAt(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    private static ItemStack findHoe(Inventory inv) {
        if (inv == null) return null;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType().name().endsWith("_HOE")) return s;
        }
        return null;
    }

    private static boolean take(Inventory inv, Material m) {
        if (inv == null) return false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() != m) continue;
            s.setAmount(s.getAmount() - 1);
            inv.setItem(i, s.getAmount() <= 0 ? null : s);
            return true;
        }
        return false;
    }

    /** Which crop this seed grows into. */
    private static Material cropOf(Material seed) {
        if (seed == Material.WHEAT_SEEDS) return Material.WHEAT;
        if (seed == Material.CARROT) return Material.CARROTS;
        if (seed == Material.POTATO) return Material.POTATOES;
        if (seed == Material.BEETROOT_SEEDS) return Material.BEETROOTS;
        return null;
    }

    private static Material seedInStore(Inventory inv) {
        if (inv == null) return null;
        Material[] order = {Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.BEETROOT_SEEDS};
        for (Material m : order) {
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s != null && s.getType() == m && s.getAmount() > 1) return m;   // keep one to eat
            }
        }
        return null;
    }

    private static void note(String s) {
        LOG.add(s);
        while (LOG.size() > 12) LOG.remove(0);
    }

    /**
     * Grass within reach of the field, for a farmer with nothing to sow. Beating grass for
     * seed is how a field starts from nothing, and waiting for somebody else to think of
     * it left every plot tilled and bare.
     */
    private static Block grassNear(World w, Field f) {
        for (int r = 2; r <= 40; r += 2) {   // range further; some fields sit on bare ground
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        Block b = w.getBlockAt(f.x + dx, f.y + 1 + dy, f.z + dz);
                        Material m = b.getType();
                        if (m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN) return b;
                    }
                }
            }
        }
        return null;
    }

    /** The next cell in this field that wants work, and what kind. */
    private static int[] nextCell(World w, Field f, boolean haveSeed, boolean haveHoe) {
        int half = f.size / 2;
        int[] ripe = null, empty = null, bare = null;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (dx == 0 && dz == 0) continue;      // the water source
                Block ground = w.getBlockAt(f.x + dx, f.y, f.z + dz);
                Block above = w.getBlockAt(f.x + dx, f.y + 1, f.z + dz);
                Material gm = ground.getType();
                if (gm == Material.FARMLAND) {
                    if (above.getBlockData() instanceof Ageable) {
                        Ageable a = (Ageable) above.getBlockData();
                        if (a.getAge() >= a.getMaximumAge() && ripe == null) ripe = new int[]{f.x + dx, f.y, f.z + dz, 2};
                    } else if (above.getType().isAir() && empty == null && haveSeed) {
                        empty = new int[]{f.x + dx, f.y, f.z + dz, 1};
                    }
                } else if ((gm == Material.GRASS_BLOCK || gm == Material.DIRT || gm == Material.COARSE_DIRT)
                        && bare == null && haveHoe
                        && (above.getType().isAir() || above.isPassable())) {
                    // grass growing on the cell is cut on the way, not a reason to skip it
                    bare = new int[]{f.x + dx, f.y, f.z + dz, 0};
                }
            }
        }
        if (ripe != null) return ripe;          // reap first, so the field keeps turning over
        if (empty != null) return empty;
        return bare;
    }

    private void work(GadgetContext ctx, Field f) {
        NpcManager npcs = ctx.plugin().npcManager();
        NpcData d = npcs.get(f.farmer);
        Entity e = d == null || d.dead ? null : npcs.resolveEntity(d);
        // A named farmer who has wandered off - or fallen down a ravine - leaves the field
        // untended forever. Take whoever of the line is actually near it instead.
        World probe = ctx.world(null);
        Location plot = new Location(probe, f.x, f.y, f.z);
        if (e == null) {
            String line = f.faction.startsWith("line-") ? f.faction.substring(5) : f.faction;
            NpcData bestD = null;
            Entity bestE = null;
            double bestDist = Double.MAX_VALUE;
            for (NpcData cand : npcs.all()) {
                if (cand.dead || !cand.id.startsWith(line)) continue;
                Entity ce = npcs.resolveEntity(cand);
                if (ce == null || !ce.getWorld().equals(probe)) continue;
                // Somebody stranded underground is the nearest by the map and no use at
                // all in the field - they cannot climb out to reach it.
                int below = probe.getHighestBlockYAt(ce.getLocation().getBlockX(),
                        ce.getLocation().getBlockZ(), HeightMap.OCEAN_FLOOR) - ce.getLocation().getBlockY();
                if (below > 8) continue;
                double dd = ce.getLocation().distance(plot);
                if (dd < bestDist) { bestDist = dd; bestD = cand; bestE = ce; }
            }
            if (bestD == null || bestDist > 400) return;   // they can walk to work
            f.farmer = bestD.id;
            d = bestD;
            e = bestE;
        }
        World w = e.getWorld();

        if (f.walking) {
            if (stillWalking(ctx, f.farmer)) return;
            f.walking = false;
        }
        Inventory inv = chestAt(ctx, f.chest);
        ItemStack hoe = findHoe(inv);
        Material seed = seedInStore(inv);
        int[] cell = nextCell(w, f, seed != null, hoe != null);
        f.lastLook = (hoe == null ? "no hoe" : "hoe ok") + ", " + (seed == null ? "no seed" : "seed ok")
                + ", cell " + (cell == null ? "none" : (cell[3] == 0 ? "till" : cell[3] == 1 ? "sow" : "reap"));
        if (cell == null && seed == null) {
            // Nothing to sow: go and make some. Grass gives seed, and seed is the only
            // thing between this field and feeding the line.
            Block g = grassNear(w, f);
            if (g == null) return;
            Location gat = g.getLocation().add(0.5, 0, 0.5);
            if (e.getLocation().distance(gat) > 4.0) {
                if (f.heading == null || f.heading[0] != g.getX() || f.heading[2] != g.getZ()) {
                    f.heading = new int[]{g.getX(), g.getY(), g.getZ(), 9};
                    if (walkTo(ctx, f.farmer, g.getX(), g.getY(), g.getZ())) { f.walking = true; }
                }
                return;
            }
            f.heading = null;
            java.util.Collection<ItemStack> drops = g.getDrops();
            g.setType(Material.AIR);
            w.playSound(g.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.7f, 1.0f);
            if (inv != null) for (ItemStack drop : drops) inv.addItem(drop);
            if (e instanceof LivingEntity) ((LivingEntity) e).getEquipment().setItemInMainHand(null);
            return;
        }
        if (cell == null) {
            // Planted and growing. The farmer stays with the field - partly because that
            // is what a farmer does, and partly because a crop only grows in a chunk
            // somebody is standing in. Walk off and the harvest never comes.
            Location centre = new Location(w, f.x + 0.5, f.y + 1, f.z + 0.5);
            if (e.getLocation().distance(centre) > 6.0) {
                if (walkTo(ctx, f.farmer, f.x + 2, f.y + 1, f.z + 2)) f.walking = true;
            }
            return;
        }

        Location at = new Location(w, cell[0] + 0.5, cell[1] + 1, cell[2] + 0.5);
        if (e.getLocation().distance(at) > 4.0) {
            if (f.heading == null || f.heading[0] != cell[0] || f.heading[2] != cell[2]) {
                f.heading = cell;
                if (walkTo(ctx, f.farmer, cell[0], cell[1] + 1, cell[2])) { f.walking = true; return; }
            }
            return;
        }
        f.heading = null;
        Block ground = w.getBlockAt(cell[0], cell[1], cell[2]);
        Block above = w.getBlockAt(cell[0], cell[1] + 1, cell[2]);

        if (cell[3] == 0) {                              // till
            if (hoe == null) return;
            if (!above.getType().isAir()) above.setType(Material.AIR);   // clear the weeds
            ground.setType(Material.FARMLAND);
            w.playSound(ground.getLocation(), Sound.ITEM_HOE_TILL, 0.8f, 1.0f);
            ItemMeta meta = hoe.getItemMeta();
            if (meta instanceof Damageable) {
                Damageable dm = (Damageable) meta;
                dm.setDamage(dm.getDamage() + 1);
                if (dm.getDamage() >= hoe.getType().getMaxDurability()) hoe.setAmount(hoe.getAmount() - 1);
                else hoe.setItemMeta(meta);
            }
            if (e instanceof LivingEntity) ((LivingEntity) e).getEquipment().setItemInMainHand(new ItemStack(Material.STONE_HOE));
            tilled++;
        } else if (cell[3] == 1) {                       // sow
            if (seed == null || !take(inv, seed)) return;
            Material crop = cropOf(seed);
            if (crop == null) return;
            above.setType(crop);
            w.playSound(above.getLocation(), Sound.ITEM_CROP_PLANT, 0.8f, 1.0f);
            if (e instanceof LivingEntity) ((LivingEntity) e).getEquipment().setItemInMainHand(new ItemStack(seed));
            sown++;
        } else {                                         // reap
            Material crop = above.getType();
            above.setType(Material.AIR);
            w.playSound(above.getLocation(), Sound.BLOCK_CROP_BREAK, 0.9f, 1.0f);
            if (inv != null) {
                // a harvest returns food AND the seed for the next one - this is the
                // whole point: the field feeds itself as well as the line
                if (crop == Material.WHEAT) {
                    inv.addItem(new ItemStack(Material.WHEAT, 1));
                    inv.addItem(new ItemStack(Material.WHEAT_SEEDS, 2));
                } else if (crop == Material.CARROTS) {
                    inv.addItem(new ItemStack(Material.CARROT, 3));
                } else if (crop == Material.POTATOES) {
                    inv.addItem(new ItemStack(Material.POTATO, 3));
                } else if (crop == Material.BEETROOTS) {
                    inv.addItem(new ItemStack(Material.BEETROOT, 1));
                    inv.addItem(new ItemStack(Material.BEETROOT_SEEDS, 2));
                }
            }
            reaped++;
            note(d.name + " brought in a crop at " + cell[0] + "," + cell[2]);
        }
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        // Hand a field to a named farmer without restarting. Without this the field keeps
        // whoever it first latched onto - usually the founder - and a purpose-built
        // farmer stands next to it doing something else entirely.
        if (action.equals("assign")) {
            String faction = args.get("faction").getAsString();
            String farmer = args.get("farmer").getAsString();
            JsonObject out = new JsonObject();
            Field f = FIELDS.get(faction);
            if (f == null) {
                out.addProperty("assigned", false);
                out.addProperty("reason", "no field registered for " + faction);
                return out;
            }
            f.farmer = farmer;
            f.walking = false;
            out.addProperty("assigned", true);
            out.addProperty("faction", faction);
            out.addProperty("farmer", farmer);
            return out;
        }

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("fields", FIELDS.size());
            out.addProperty("tilled", tilled);
            out.addProperty("sown", sown);
            out.addProperty("harvested", reaped);
            JsonArray fs = new JsonArray();
            for (Field f : FIELDS.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("line", f.faction);
                o.addProperty("farmer", f.farmer);
                o.addProperty("at", f.x + "," + f.y + "," + f.z);
                o.addProperty("sees", f.lastLook);
                fs.add(o);
            }
            out.add("fieldList", fs);
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
        FIELDS.clear();
        JsonArray fields = args.getAsJsonArray("fields");
        for (JsonElement el : fields) {
            JsonObject o = el.getAsJsonObject();
            Field f = new Field();
            f.faction = o.get("faction").getAsString();
            f.farmer = o.get("farmer").getAsString();
            f.chest = o.getAsJsonObject("chest");
            JsonObject at = o.getAsJsonObject("at");
            f.x = at.get("x").getAsInt();
            f.z = at.get("z").getAsInt();
            World w = ctx.world(null);
            w.getChunkAt(f.x >> 4, f.z >> 4).load(true);
            // Read the ground from the CORNERS, never the centre. The centre holds the
            // irrigation source, and water is not solid - so measuring there drops the
            // field a block on every restart, and every cell then inspects the earth
            // underneath the real surface, where nothing is ever tillable.
            int[] corners = new int[]{
                w.getHighestBlockYAt(f.x - 2, f.z - 2, HeightMap.OCEAN_FLOOR),
                w.getHighestBlockYAt(f.x + 2, f.z - 2, HeightMap.OCEAN_FLOOR),
                w.getHighestBlockYAt(f.x - 2, f.z + 2, HeightMap.OCEAN_FLOOR),
                w.getHighestBlockYAt(f.x + 2, f.z + 2, HeightMap.OCEAN_FLOOR)
            };
            java.util.Arrays.sort(corners);
            f.y = corners[1];                      // the level most of the plot sits at
            f.size = o.has("size") ? o.get("size").getAsInt() : 5;
            // Irrigation. Farmland with no water within four blocks dries out and reverts
            // to dirt, so an unwatered field is tilled forever and never sown - which is
            // exactly what was happening. One source block at the centre waters the whole
            // plot, the way a farmer would channel to it.
            Block centre = w.getBlockAt(f.x, f.y, f.z);
            Block over = w.getBlockAt(f.x, f.y + 1, f.z);
            if (centre.getType() != Material.WATER) {
                if (!centre.getType().isSolid()) {
                    // nothing solid to dig into: leave it dry rather than flood the plot
                } else {
                    if (!over.getType().isAir()) over.setType(Material.AIR);
                    centre.setType(Material.WATER);
                }
            }
            FIELDS.put(f.faction, f);
        }
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 30;
        TASK_ID = Integer.valueOf(ctx.runTimer(period, new Runnable() {
            public void run() {
                try {
                    if (generation(ctx, false) != myGen) { reap(ctx); return; }
                    for (Field f : new ArrayList<Field>(FIELDS.values())) {
                        try { work(ctx, f); } catch (Throwable t) {
                            note("ERROR " + f.faction + ": " + t.getClass().getSimpleName()
                                    + " " + t.getMessage());
                        }
                    }
                } catch (Throwable ignored) { }
            }
        }));
        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("generation", myGen);
        out.addProperty("fields", FIELDS.size());
        return out;
    }
}
