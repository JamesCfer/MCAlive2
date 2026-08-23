package celestia.gadgets;

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
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Raises a shelter, one block at a time, out of a line's own stores.
 *
 * Built as a gadget rather than a behavior blueprint for the usual reason: blueprint
 * building needs the crew to walk to each block and the mannequin walker cannot path,
 * so those builds stall. Here the builder is teleported alongside each block as it goes
 * up, which reads the same from outside and always finishes.
 *
 * The site is levelled as it builds - a floor is laid at one height and the headroom
 * above is cleared - so a hut can go up on the rolling ground these clearings actually
 * sit on rather than needing a perfectly flat plot.
 */
public class Shelter implements GadgetContract {

    private static final Map<String, Session> SESSIONS = new HashMap<String, Session>();

    private static class Session {
        String npcId;
        String label;
        int taskId;
        Deque<int[]> plan = new ArrayDeque<int[]>();   // x, y, z, mode (0 = air, 1 = block)
        Location chest;
        Location home;
        Material material;
        int placed;
        int cleared;
        int shortfall;
        String stopReason;
        boolean walking;
        int[] heading;
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

    private static Inventory chestAt(World w, Location loc) {
        Block b = w.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    /** Best building material the store actually holds, most plentiful first. */
    private static Material pickMaterial(Inventory inv) {
        Material best = null;
        int bestCount = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null) continue;
            String n = s.getType().name();
            boolean usable = n.endsWith("_LOG") || n.endsWith("_PLANKS") || n.equals("COBBLESTONE")
                    || n.equals("STONE") || n.endsWith("_WOOD") || n.equals("DIRT");
            if (!usable) continue;
            int have = 0;
            for (int j = 0; j < inv.getSize(); j++) {
                ItemStack t = inv.getItem(j);
                if (t != null && t.getType() == s.getType()) have += t.getAmount();
            }
            if (have > bestCount) { bestCount = have; best = s.getType(); }
        }
        return best;
    }

    /** Already a wall/floor/roof block - a rerun must leave it alone. Checking only
     *  against the CURRENT material made a rerun tear out and re-place every course
     *  laid in a different wood, burning the timber before it reached the roof. */
    private static boolean alreadyBuilt(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_PLANKS") || n.endsWith("_WOOD")
                || n.equals("COBBLESTONE") || n.equals("STONE");
    }

    private static boolean take(Inventory inv, Material m) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() != m) continue;
            s.setAmount(s.getAmount() - 1);
            inv.setItem(i, s.getAmount() <= 0 ? null : s);
            return true;
        }
        return false;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";
        String npcId = args.has("npcId") ? args.get("npcId").getAsString() : null;

        if (action.equals("status") || action.equals("stop")) {
            JsonObject out = new JsonObject();
            Session s = SESSIONS.get(npcId);
            if (s == null) { out.addProperty("running", false); return out; }
            if (action.equals("stop")) s.stopReason = "stopped";
            out.addProperty("running", true);
            out.addProperty("placed", s.placed);
            out.addProperty("remaining", s.plan.size());
            return out;
        }
        if (SESSIONS.containsKey(npcId)) throw new IllegalStateException(npcId + " is already building");

        NpcManager npcs = ctx.plugin().npcManager();
        NpcData data = npcs.get(npcId);
        if (data == null) throw new IllegalArgumentException("no NPC with id: " + npcId);
        Entity entity = npcs.resolveEntity(data);
        if (entity == null) throw new IllegalStateException("NPC entity could not be resolved: " + npcId);
        World world = entity.getWorld();

        Session s = new Session();
        s.npcId = npcId;
        s.label = args.has("label") ? args.get("label").getAsString() : "shelter";
        s.home = entity.getLocation().clone();
        JsonObject c = args.getAsJsonObject("chest");
        s.chest = new Location(world, c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        Inventory inv = chestAt(world, s.chest);
        if (inv == null) throw new IllegalStateException("no store chest at the given position");
        s.material = args.has("material")
                ? Material.matchMaterial(args.get("material").getAsString().toUpperCase())
                : pickMaterial(inv);
        if (s.material == null) throw new IllegalStateException("the store holds no building material");

        JsonObject site = args.getAsJsonObject("site");
        int cx = site.get("x").getAsInt();
        int cz = site.get("z").getAsInt();
        int base = site.has("y") ? site.get("y").getAsInt()
                : world.getHighestBlockYAt(cx, cz, HeightMap.OCEAN_FLOOR) + 1;

        // floor, then walls with a doorway, then a roof - and the headroom cleared
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                s.plan.add(new int[]{cx + dx, base - 1, cz + dz, 1});        // floor
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean edge = dx == -2 || dx == 2 || dz == -2 || dz == 2;
                    boolean door = dz == -2 && dx == 0 && dy <= 1;
                    if (edge && !door) s.plan.add(new int[]{cx + dx, base + dy, cz + dz, 1});
                    else s.plan.add(new int[]{cx + dx, base + dy, cz + dz, 0});   // clear inside
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                s.plan.add(new int[]{cx + dx, base + 3, cz + dz, 1});        // roof

        final Session session = s;
        int period = args.has("ticksPerBlock") ? args.get("ticksPerBlock").getAsInt() : 5;
        s.taskId = ctx.runTimer(period, new Runnable() {
            public void run() { tick(ctx, session, world); }
        });
        SESSIONS.put(npcId, s);

        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("blocks", s.plan.size());
        out.addProperty("material", s.material.name());
        out.addProperty("at", cx + "," + base + "," + cz);
        return out;
    }

    private void tick(GadgetContext ctx, Session s, World world) {
        try {
            NpcManager npcs = ctx.plugin().npcManager();
            NpcData data = npcs.get(s.npcId);
            Entity entity = data == null ? null : npcs.resolveEntity(data);
            if (entity == null) { finish(ctx, s, world, "builder_gone"); return; }
            if (s.stopReason != null || s.plan.isEmpty()) {
                finish(ctx, s, world, s.stopReason != null ? s.stopReason
                        : (s.shortfall > 0 ? "ran_short_of_material" : "finished"));
                return;
            }
            if (s.walking) {
                if (stillWalking(ctx, s.npcId)) return;
                s.walking = false;
            }
            int[] peek = s.plan.peek();
            if (peek != null && entity.getLocation().distance(
                    new Location(world, peek[0] + 0.5, peek[1], peek[2] + 0.5)) > 4.5) {
                if (s.heading == null || s.heading[0] != peek[0] || s.heading[2] != peek[2]) {
                    s.heading = peek;
                    if (walkTo(ctx, s.npcId, peek[0], peek[1] + 1, peek[2])) { s.walking = true; return; }
                }
            }
            s.heading = null;
            int[] p = s.plan.poll();
            Block b = world.getBlockAt(p[0], p[1], p[2]);
            b.getChunk().load();
            if (p[3] == 0) {
                if (!b.getType().isAir()) { b.setType(Material.AIR); s.cleared++; }
                return;
            }
            if (alreadyBuilt(b.getType())) return;   // this course is already standing
            Inventory inv = chestAt(world, s.chest);
            if (inv == null) { s.shortfall++; return; }
            if (!take(inv, s.material)) {
                // the primary timber ran out - finish the hut in whatever else the store
                // holds rather than leaving it roofless. A mixed wall is a fed line.
                Material alt = pickMaterial(inv);
                if (alt == null || !take(inv, alt)) { s.shortfall++; return; }
                s.material = alt;
            }
            // the builder must be standing beside the block to lay it
            if (entity instanceof LivingEntity) {
                ((LivingEntity) entity).getEquipment().setItemInMainHand(new ItemStack(s.material));
            }
            world.playSound(b.getLocation(), b.getBlockData().getSoundGroup().getPlaceSound(), 0.8f, 1.0f);
            b.setType(s.material);
            s.placed++;
        } catch (Throwable t) {
            finish(ctx, s, world, "error: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private void finish(GadgetContext ctx, Session s, World world, String reason) {
        ctx.cancelTask(s.taskId);
        SESSIONS.remove(s.npcId);
        NpcManager npcs = ctx.plugin().npcManager();
        NpcData data = npcs.get(s.npcId);
        Entity entity = data == null ? null : npcs.resolveEntity(data);
        if (entity != null) {
            if (entity.getLocation().distance(s.home) > 6) {
                walkTo(ctx, s.npcId, s.home.getBlockX(), s.home.getBlockY(), s.home.getBlockZ());
            }
            if (entity instanceof LivingEntity) ((LivingEntity) entity).getEquipment().setItemInMainHand(null);
        }
        JsonObject ev = new JsonObject();
        ev.addProperty("npcId", s.npcId);
        ev.addProperty("what", s.label);
        ev.addProperty("reason", reason);
        ev.addProperty("placed", s.placed);
        ev.addProperty("cleared", s.cleared);
        ev.addProperty("shortOfMaterial", s.shortfall);
        ctx.plugin().bridge().broadcastEvent("npc_built", ev);
    }
}
