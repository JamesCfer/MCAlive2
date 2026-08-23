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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Woodcutting that actually completes. Felling by behavior program keeps stalling with
 * cant_reach, because the mannequin step-walker has no pathfinding and gives up on the
 * first trunk between it and the tree. This does for timber what the mine gadget does for
 * stone: the NPC is TELEPORTED to each tree, fells the whole trunk, and the yield is
 * delivered straight into the settlement store.
 *
 * Only trunks are taken - leaves are left standing to decay naturally, so the felling
 * still reads as real deforestation on the map rather than surgical block removal.
 */
public class Forester implements GadgetContract {

    private static final Map<String, Session> SESSIONS = new HashMap<String, Session>();

    private static class Session {
        String npcId;
        int taskId;
        int want;
        int got;
        int misses;
        Material wood;
        Location anchor;
        int radius;
        Location chest;
        Location start;
        boolean walking;
        int[] heading;
        Map<Material, Integer> yield = new LinkedHashMap<Material, Integer>();
        String stopReason;
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

    private static boolean isLog(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_WOOD") || n.equals("MUSHROOM_STEM");
    }

    private static Inventory chestAt(World w, Location loc) {
        Block b = w.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    /** Nearest standing trunk of the wanted type within radius of the anchor. */
    private static Block findTrunk(World w, Location anchor, int radius, Material wood) {
        int ax = anchor.getBlockX();
        int ay = anchor.getBlockY();
        int az = anchor.getBlockZ();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = ax - radius; x <= ax + radius; x++) {
            for (int z = az - radius; z <= az + radius; z++) {
                for (int y = Math.max(w.getMinHeight(), ay - 8); y <= Math.min(w.getMaxHeight() - 1, ay + 24); y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (wood == null ? !isLog(m) : m != wood) continue;
                    double d = (x - ax) * (x - ax) + (z - az) * (z - az) + (y - ay) * (y - ay) * 0.5;
                    if (d < bestD) { bestD = d; best = b; }
                }
            }
        }
        return best;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String npcId = args.get("npcId").getAsString();
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status") || action.equals("stop")) {
            JsonObject out = new JsonObject();
            Session s = SESSIONS.get(npcId);
            if (s == null) { out.addProperty("running", false); return out; }
            if (action.equals("stop")) s.stopReason = "stopped";
            out.addProperty("running", true);
            out.addProperty("felled", s.got);
            out.addProperty("want", s.want);
            return out;
        }
        if (SESSIONS.containsKey(npcId)) throw new IllegalStateException(npcId + " is already out felling");

        NpcManager npcs = ctx.plugin().npcManager();
        NpcData data = npcs.get(npcId);
        if (data == null) throw new IllegalArgumentException("no NPC with id: " + npcId);
        Entity entity = npcs.resolveEntity(data);
        if (entity == null) throw new IllegalStateException("NPC entity could not be resolved: " + npcId);
        World world = entity.getWorld();

        Session s = new Session();
        s.npcId = npcId;
        s.want = args.has("count") ? args.get("count").getAsInt() : 32;
        s.radius = args.has("radius") ? args.get("radius").getAsInt() : 32;
        s.start = entity.getLocation().clone();
        JsonObject a = args.getAsJsonObject("anchor");
        s.anchor = new Location(world, a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
        JsonObject c = args.getAsJsonObject("chest");
        s.chest = new Location(world, c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        s.wood = args.has("wood") ? Material.matchMaterial(args.get("wood").getAsString().toUpperCase()) : null;
        int period = args.has("ticksPerBlock") ? args.get("ticksPerBlock").getAsInt() : 6;

        final Session session = s;
        s.taskId = ctx.runTimer(period, new Runnable() {
            public void run() { tick(ctx, session, world); }
        });
        SESSIONS.put(npcId, s);

        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("want", s.want);
        out.addProperty("anchor", s.anchor.getBlockX() + "," + s.anchor.getBlockZ());
        return out;
    }

    private void tick(GadgetContext ctx, Session s, World world) {
        try {
            NpcManager npcs = ctx.plugin().npcManager();
            NpcData data = npcs.get(s.npcId);
            Entity entity = data == null ? null : npcs.resolveEntity(data);
            if (entity == null) { finish(ctx, s, world, "npc_gone"); return; }
            if (s.stopReason != null || s.got >= s.want || s.misses > 3) {
                finish(ctx, s, world, s.stopReason != null ? s.stopReason
                        : (s.got >= s.want ? "done" : "no_trees_in_range"));
                return;
            }
            if (s.walking) {
                if (stillWalking(ctx, s.npcId)) return;
                s.walking = false;
            }
            Block trunk = findTrunk(world, s.anchor, s.radius, s.wood);
            if (trunk == null) { s.misses++; return; }
            s.misses = 0;

            // walk to the tree - a woodcutter goes to the wood, it does not appear at it
            double away = entity.getLocation().distance(new Location(world, trunk.getX() + 0.5, trunk.getY(), trunk.getZ() + 0.5));
            if (away > 4.0) {
                int[] h = new int[]{trunk.getX(), trunk.getY(), trunk.getZ()};
                if (s.heading == null || s.heading[0] != h[0] || s.heading[2] != h[2]) {
                    s.heading = h;
                    if (walkTo(ctx, s.npcId, h[0], h[1], h[2])) { s.walking = true; return; }
                }
                s.misses++;                       // no way to this tree on foot
                return;
            }
            s.heading = null;
            if (entity instanceof LivingEntity) {
                ((LivingEntity) entity).getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE));
            }
            int x = trunk.getX();
            int z = trunk.getZ();
            int taken = 0;
            for (int y = trunk.getY(); y < world.getMaxHeight() && taken < 12; y++) {
                Block b = world.getBlockAt(x, y, z);
                if (!isLog(b.getType())) break;
                Material m = b.getType();
                world.playSound(b.getLocation(), b.getBlockData().getSoundGroup().getBreakSound(), 1.0f, 1.0f);
                b.setType(Material.AIR);
                Integer prev = s.yield.get(m);
                s.yield.put(m, (prev == null ? 0 : prev.intValue()) + 1);
                taken++;
                s.got++;
            }
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
            if (entity.getLocation().distance(s.start) > 6) {
                walkTo(ctx, s.npcId, s.start.getBlockX(), s.start.getBlockY(), s.start.getBlockZ());
            }
            if (entity instanceof LivingEntity) {
                ((LivingEntity) entity).getEquipment().setItemInMainHand(null);
            }
        }
        Inventory inv = chestAt(world, s.chest);
        JsonObject y = new JsonObject();
        int dropped = 0;
        for (Map.Entry<Material, Integer> e : s.yield.entrySet()) {
            y.addProperty(e.getKey().name(), e.getValue());
            if (inv == null) continue;
            int left = e.getValue().intValue();
            while (left > 0) {
                int n = Math.min(left, e.getKey().getMaxStackSize());
                Map<Integer, ItemStack> over = inv.addItem(new ItemStack(e.getKey(), n));
                for (ItemStack o : over.values()) dropped += o.getAmount();
                left -= n;
            }
        }
        JsonObject ev = new JsonObject();
        ev.addProperty("npcId", s.npcId);
        ev.addProperty("reason", reason);
        ev.addProperty("felled", s.got);
        ev.add("yield", y);
        ev.addProperty("overflowLost", dropped);
        ctx.plugin().bridge().broadcastEvent("npc_felled", ev);
    }
}
