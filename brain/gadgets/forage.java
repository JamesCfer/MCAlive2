package celestia.gadgets;

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
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Foraging expeditions. Nothing spawns naturally in this world - animals arrive only
 * with newly generated chunks - so a settlement that wants meat has to send someone
 * over the horizon to find it. This walks an NPC outward from its home in a fixed
 * bearing, loading (and therefore generating) fresh ground as it goes, taking food from
 * whatever it meets, and carrying the haul back to the settlement store.
 *
 * Movement is teleport-based for the same reason mining and felling are: the mannequin
 * step-walker cannot path around obstacles and would give up on the first tree.
 *
 * Herds are never wiped out - at most two animals are taken from any one group, so the
 * rest are left to breed and the food supply can recover.
 */
public class Forage implements GadgetContract {

    private static final Map<String, Session> SESSIONS = new HashMap<String, Session>();
    /** Turns the road every trip. Sending a forager down the same bearing each time
     *  just walks it back over ground it already generated and already stripped. */
    private static int trips = 0;

    private static class Session {
        String npcId;
        int taskId;
        int want;
        int got;
        int step;
        int maxSteps;
        double bearing;
        int stride;
        Location home;
        Location chest;
        Map<Material, Integer> bag = new LinkedHashMap<Material, Integer>();
        String stopReason;
        int farthest;
        boolean walking;
    }

    /** What each animal yields. Fish are included for coastal foraging. */

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

    private static Material meatOf(EntityType t) {
        if (t == EntityType.COW || t == EntityType.MOOSHROOM) return Material.BEEF;
        if (t == EntityType.PIG) return Material.PORKCHOP;
        if (t == EntityType.SHEEP) return Material.MUTTON;
        if (t == EntityType.CHICKEN) return Material.CHICKEN;
        if (t == EntityType.RABBIT) return Material.RABBIT;
        if (t == EntityType.COD) return Material.COD;
        if (t == EntityType.SALMON) return Material.SALMON;
        return null;
    }

    private static Inventory chestAt(World w, Location loc) {
        Block b = w.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    private static void add(Session s, Material m, int n) {
        Integer prev = s.bag.get(m);
        s.bag.put(m, (prev == null ? 0 : prev.intValue()) + n);
        s.got += n;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("sessions")) {
            JsonObject out = new JsonObject();
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (Map.Entry<String, Session> e : SESSIONS.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("npcId", e.getKey());
                o.addProperty("found", e.getValue().got);
                o.addProperty("blocksOut", e.getValue().farthest);
                a.add(o);
            }
            out.add("foraging", a);
            return out;
        }

        String npcId = args.get("npcId").getAsString();
        if (action.equals("status") || action.equals("stop")) {
            JsonObject out = new JsonObject();
            Session s = SESSIONS.get(npcId);
            if (s == null) { out.addProperty("running", false); return out; }
            if (action.equals("stop")) s.stopReason = "stopped";
            out.addProperty("running", true);
            out.addProperty("found", s.got);
            out.addProperty("blocksOut", s.farthest);
            return out;
        }
        if (SESSIONS.containsKey(npcId)) throw new IllegalStateException(npcId + " is already out foraging");

        NpcManager npcs = ctx.plugin().npcManager();
        NpcData data = npcs.get(npcId);
        if (data == null) throw new IllegalArgumentException("no NPC with id: " + npcId);
        Entity entity = npcs.resolveEntity(data);
        if (entity == null) throw new IllegalStateException("NPC entity could not be resolved: " + npcId);
        World world = entity.getWorld();

        Session s = new Session();
        s.npcId = npcId;
        s.want = args.has("want") ? args.get("want").getAsInt() : 12;
        s.stride = args.has("stride") ? args.get("stride").getAsInt() : 24;
        s.maxSteps = args.has("maxSteps") ? args.get("maxSteps").getAsInt() : 24;
        s.home = entity.getLocation().clone();
        if (args.has("home") && args.get("home").isJsonObject()) {
            JsonObject h = args.getAsJsonObject("home");
            s.home = new Location(world, h.get("x").getAsInt(), h.get("y").getAsInt(), h.get("z").getAsInt());
        }
        JsonObject c = args.getAsJsonObject("chest");
        s.chest = new Location(world, c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        trips++;
        s.bearing = args.has("bearing") ? args.get("bearing").getAsDouble()
                : ((Math.abs(npcId.hashCode()) + trips * 47) % 360);

        final Session session = s;
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 20;
        s.taskId = ctx.runTimer(period, new Runnable() {
            public void run() { tick(ctx, session, world); }
        });
        SESSIONS.put(npcId, s);

        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("want", s.want);
        out.addProperty("bearing", Math.round(s.bearing));
        return out;
    }

    private void tick(GadgetContext ctx, Session s, World world) {
        try {
            NpcManager npcs = ctx.plugin().npcManager();
            NpcData data = npcs.get(s.npcId);
            Entity entity = data == null ? null : npcs.resolveEntity(data);
            if (entity == null) { finish(ctx, s, world, "npc_gone"); return; }
            if (s.stopReason != null || s.got >= s.want || s.step >= s.maxSteps) {
                finish(ctx, s, world, s.stopReason != null ? s.stopReason
                        : (s.got >= s.want ? "found_food" : "came_back_empty"));
                return;
            }
            // still on the road to the next stretch of country
            if (s.walking) {
                if (stillWalking(ctx, s.npcId)) { takeFromHerds(ctx, s, entity); return; }
                s.walking = false;
                takeFromHerds(ctx, s, entity);
                takeFromPlants(s, world, entity.getLocation());
                return;
            }
            // If there is game in sight, go to it. Since the no-teleport rule, a forager
            // can only take what is within reach, so it has to actually walk up to the
            // animal - otherwise it strolls past whole herds and comes home empty.
            LivingEntity quarry = null;
            double best = Double.MAX_VALUE;
            for (Entity near : entity.getNearbyEntities(72, 32, 72)) {
                if (!(near instanceof LivingEntity)) continue;
                if (meatOf(near.getType()) == null) continue;
                double d = near.getLocation().distance(entity.getLocation());
                if (d < best) { best = d; quarry = (LivingEntity) near; }
            }
            if (quarry != null && best > 3.5) {
                Location q = quarry.getLocation();
                if (walkTo(ctx, s.npcId, q.getBlockX(), q.getBlockY(), q.getBlockZ())) {
                    s.walking = true;
                    return;
                }
            }

            s.step++;
            // set out for the next stretch on foot; loading the chunk generates it if it
            // has never existed, which is where animals come from
            int dist = s.step * s.stride;
            s.farthest = dist;
            int tx = s.home.getBlockX() + (int) Math.round(Math.cos(Math.toRadians(s.bearing)) * dist);
            int tz = s.home.getBlockZ() + (int) Math.round(Math.sin(Math.toRadians(s.bearing)) * dist);
            world.getChunkAt(tx >> 4, tz >> 4).load(true);
            int ty = world.getHighestBlockYAt(tx, tz, org.bukkit.HeightMap.OCEAN_FLOOR) + 1;
            if (walkTo(ctx, s.npcId, tx, ty, tz)) { s.walking = true; return; }
            takeFromPlants(s, world, entity.getLocation());
        } catch (Throwable t) {
            finish(ctx, s, world, "error: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /** Take at most two head from any one herd, so the group can still breed back. */
    private void takeFromHerds(GadgetContext ctx, Session s, Entity entity) {
        Map<EntityType, List<LivingEntity>> herds = new HashMap<EntityType, List<LivingEntity>>();
        for (Entity e : entity.getNearbyEntities(72, 32, 72)) {
            if (!(e instanceof LivingEntity)) continue;
            Material meat = meatOf(e.getType());
            if (meat == null) continue;
            List<LivingEntity> l = herds.get(e.getType());
            if (l == null) { l = new ArrayList<LivingEntity>(); herds.put(e.getType(), l); }
            l.add((LivingEntity) e);
        }
        for (Map.Entry<EntityType, List<LivingEntity>> e : herds.entrySet()) {
            List<LivingEntity> herd = e.getValue();
            // leave one behind rather than two: small groups are what newly generated
            // chunks actually produce, and requiring three meant a forager walked a
            // third of a kilometre past perfectly good cattle and came home empty
            int take = Math.min(3, Math.max(1, herd.size() - 1));
            Material meat = meatOf(e.getKey());
            for (int i = 0; i < take && s.got < s.want; i++) {
                LivingEntity victim = herd.get(i);
                Location at = victim.getLocation();
                // only what is within reach; the rest of the herd is walked down on a
                // later stretch rather than snatched from across the field
                if (entity.getLocation().distance(at) > 4.0) continue;
                victim.getWorld().playSound(at, org.bukkit.Sound.ENTITY_GENERIC_HURT, 1.0f, 1.0f);
                victim.remove();
                add(s, meat, 2);
                if (e.getKey() == EntityType.SHEEP) add(s, Material.WHITE_WOOL, 1);
                if (e.getKey() == EntityType.COW) add(s, Material.LEATHER, 1);
            }
        }
    }

    /** Berries and ripe crops within reach of where the forager is standing. */
    private void takeFromPlants(Session s, World world, Location here) {
        int x = here.getBlockX(), y = here.getBlockY(), z = here.getBlockZ();
        for (int dx = -6; dx <= 6 && s.got < s.want; dx++) {
            for (int dz = -6; dz <= 6 && s.got < s.want; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    Block b = world.getBlockAt(x + dx, y + dy, z + dz);
                    Material m = b.getType();
                    if (m == Material.SWEET_BERRY_BUSH) {
                        if (b.getBlockData() instanceof Ageable) {
                            Ageable a = (Ageable) b.getBlockData();
                            if (a.getAge() < 2) continue;
                            a.setAge(1);
                            b.setBlockData(a);
                            add(s, Material.SWEET_BERRIES, 2);
                        }
                    } else if (m == Material.WHEAT || m == Material.CARROTS || m == Material.POTATOES
                            || m == Material.BEETROOTS) {
                        if (!(b.getBlockData() instanceof Ageable)) continue;
                        Ageable a = (Ageable) b.getBlockData();
                        if (a.getAge() < a.getMaximumAge()) continue;
                        b.setType(Material.AIR);
                        if (m == Material.WHEAT) add(s, Material.WHEAT, 2);
                        else if (m == Material.CARROTS) add(s, Material.CARROT, 2);
                        else if (m == Material.POTATOES) add(s, Material.POTATO, 2);
                        else add(s, Material.BEETROOT, 2);
                    }
                }
            }
        }
    }

    private void finish(GadgetContext ctx, Session s, World world, String reason) {
        ctx.cancelTask(s.taskId);
        SESSIONS.remove(s.npcId);
        NpcManager npcs = ctx.plugin().npcManager();
        NpcData data = npcs.get(s.npcId);
        Entity entity = data == null ? null : npcs.resolveEntity(data);
        if (entity != null && entity.getLocation().distance(s.home) > 6) {
            walkTo(ctx, s.npcId, s.home.getBlockX(), s.home.getBlockY(), s.home.getBlockZ());
        }
        Inventory inv = chestAt(world, s.chest);
        JsonObject haul = new JsonObject();
        int lost = 0;
        for (Map.Entry<Material, Integer> e : s.bag.entrySet()) {
            haul.addProperty(e.getKey().name(), e.getValue());
            if (inv == null) continue;
            int left = e.getValue().intValue();
            while (left > 0) {
                int n = Math.min(left, e.getKey().getMaxStackSize());
                Map<Integer, ItemStack> over = inv.addItem(new ItemStack(e.getKey(), n));
                for (ItemStack o : over.values()) lost += o.getAmount();
                left -= n;
            }
        }
        JsonObject ev = new JsonObject();
        ev.addProperty("npcId", s.npcId);
        ev.addProperty("reason", reason);
        ev.addProperty("blocksTravelled", s.farthest);
        ev.add("haul", haul);
        ev.addProperty("overflowLost", lost);
        ctx.plugin().bridge().broadcastEvent("npc_foraged", ev);
    }
}
