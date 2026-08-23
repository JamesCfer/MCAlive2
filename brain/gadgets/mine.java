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
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NPC shaft mining. Excavates a descending 1x2 staircase to a target depth, drives a
 * branch tunnel, vein-mines any ore it exposes, and hauls the yield back to a chest.
 *
 * The NPC is teleported along the corridor it has just excavated rather than walked,
 * because the mannequin step-walker cannot descend a shaft. Drops are resolved with the
 * best pickaxe found in the chest, so vanilla tool rules gate what an NPC can win from
 * the rock: a wooden pickaxe brings home cobblestone, iron needs stone, and so on. The
 * pickaxe takes real durability damage and can break mid-shift.
 */
public class Mine implements GadgetContract {

    private static final Map<String, Session> SESSIONS = new HashMap<String, Session>();
    /** How much bulk stone a miner carries home per shift; the rest stays in the ground. */
    private static final int SPOIL_PER_TRIP = 32;

    private static class Session {
        String npcId;
        int taskId;
        Deque<int[]> plan = new ArrayDeque<int[]>();
        Map<Material, Integer> yield = new LinkedHashMap<Material, Integer>();
        int broken;
        int maxBlocks;
        Location chest;
        ItemStack tool;
        boolean toolBroke;
        String stopReason;
        boolean walking;
    }


    private static boolean walkTo(GadgetContext ctx, String npcId, int x, int y, int z) {
        try {
            JsonObject to = new JsonObject();
            to.addProperty("x", x); to.addProperty("y", y); to.addProperty("z", z);
            JsonObject a = new JsonObject();
            a.addProperty("npcId", npcId);
            a.add("to", to);
            a.addProperty("underground", true);   // a miner may walk down its own shaft
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

    private static boolean isOre(Material m) {
        String n = m.name();
        return n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS");
    }

    private static boolean isProtected(Material m) {
        return m == Material.BEDROCK || m == Material.END_PORTAL_FRAME
                || m == Material.REINFORCED_DEEPSLATE || m == Material.SPAWNER;
    }

    private static int pickRank(Material m) {
        String n = m.name();
        if (!n.endsWith("_PICKAXE")) return -1;
        if (n.startsWith("NETHERITE")) return 5;
        if (n.startsWith("DIAMOND")) return 4;
        if (n.startsWith("IRON")) return 3;
        if (n.startsWith("STONE")) return 2;
        if (n.startsWith("GOLDEN")) return 1;
        return 0;
    }

    private static ItemStack takeBestPickaxe(Inventory inv) {
        int best = -1;
        int slot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null) continue;
            int r = pickRank(s.getType());
            if (r > best) { best = r; slot = i; }
        }
        if (slot < 0) return null;
        ItemStack tool = inv.getItem(slot).clone();
        tool.setAmount(1);
        ItemStack in = inv.getItem(slot);
        in.setAmount(in.getAmount() - 1);
        inv.setItem(slot, in.getAmount() <= 0 ? null : in);
        return tool;
    }

    private static Inventory chestAt(World w, Location loc) {
        if (loc == null) return null;
        Block b = w.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String npcId = args.get("npcId").getAsString();
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status") || action.equals("stop")) {
            JsonObject out = new JsonObject();
            Session s = SESSIONS.get(npcId);
            if (s == null) { out.addProperty("running", false); return out; }
            if (action.equals("stop")) { s.stopReason = "stopped"; s.plan.clear(); }
            out.addProperty("running", true);
            out.addProperty("broken", s.broken);
            out.addProperty("remaining", s.plan.size());
            JsonObject y = new JsonObject();
            for (Map.Entry<Material, Integer> e : s.yield.entrySet()) y.addProperty(e.getKey().name(), e.getValue());
            out.add("yield", y);
            return out;
        }

        if (SESSIONS.containsKey(npcId)) throw new IllegalStateException(npcId + " is already mining");

        NpcManager npcs = ctx.plugin().npcManager();
        NpcData data = npcs.get(npcId);
        if (data == null) throw new IllegalArgumentException("no NPC with id: " + npcId);
        Entity entity = npcs.resolveEntity(data);
        if (entity == null) throw new IllegalStateException("NPC entity could not be resolved: " + npcId);

        World world = entity.getWorld();
        Location start = entity.getLocation();
        int sx = start.getBlockX();
        int sy = start.getBlockY();
        int sz = start.getBlockZ();

        int targetY = args.has("targetY") ? args.get("targetY").getAsInt() : 12;
        int branch = args.has("branch") ? args.get("branch").getAsInt() : 24;
        int period = args.has("ticksPerBlock") ? args.get("ticksPerBlock").getAsInt() : 8;
        int maxBlocks = args.has("maxBlocks") ? args.get("maxBlocks").getAsInt() : 600;

        String dir = args.has("dir") ? args.get("dir").getAsString() : "+x";
        int dx = dir.equals("+x") ? 1 : (dir.equals("-x") ? -1 : 0);
        int dz = dir.equals("+z") ? 1 : (dir.equals("-z") ? -1 : 0);
        if (dx == 0 && dz == 0) throw new IllegalArgumentException("dir must be +x, -x, +z or -z");

        Session s = new Session();
        s.npcId = npcId;
        s.maxBlocks = maxBlocks;

        if (args.has("chest") && args.get("chest").isJsonObject()) {
            JsonObject c = args.getAsJsonObject("chest");
            s.chest = new Location(world, c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        } else {
            Block head = world.getBlockAt(sx - dx, sy, sz - dz);
            if (!(head.getState() instanceof Container)) head.setType(Material.CHEST);
            s.chest = head.getLocation();
        }
        Inventory inv = chestAt(world, s.chest);
        if (inv == null) throw new IllegalStateException("no chest at " + s.chest.getBlockX()
                + "," + s.chest.getBlockY() + "," + s.chest.getBlockZ());
        s.tool = takeBestPickaxe(inv);

        int x = sx;
        int y = sy;
        int z = sz;
        while (y > targetY && s.plan.size() < maxBlocks) {
            x += dx; z += dz; y -= 1;
            s.plan.add(new int[]{x, y, z});
        }
        for (int i = 0; i < branch && s.plan.size() < maxBlocks; i++) {
            x += dx; z += dz;
            s.plan.add(new int[]{x, y, z});
        }

        if (s.tool != null && entity instanceof LivingEntity) {
            ((LivingEntity) entity).getEquipment().setItemInMainHand(s.tool.clone());
        }

        final Location startLoc = start.clone();
        final Session session = s;
        s.taskId = ctx.runTimer(period, new Runnable() {
            public void run() { tick(ctx, session, world, startLoc); }
        });
        SESSIONS.put(npcId, s);

        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("planned", s.plan.size());
        out.addProperty("tool", s.tool == null ? "none" : s.tool.getType().name());
        out.addProperty("chest", s.chest.getBlockX() + "," + s.chest.getBlockY() + "," + s.chest.getBlockZ());
        return out;
    }

    private void tick(GadgetContext ctx, Session s, World world, Location startLoc) {
        try {
            NpcManager npcs = ctx.plugin().npcManager();
            NpcData data = npcs.get(s.npcId);
            Entity entity = data == null ? null : npcs.resolveEntity(data);
            if (entity == null) { finish(ctx, s, world, startLoc, "npc_gone"); return; }
            if (s.stopReason != null || s.plan.isEmpty() || s.broken >= s.maxBlocks || s.toolBroke) {
                String why = s.stopReason != null ? s.stopReason : (s.toolBroke ? "tool_broke" : "done");
                finish(ctx, s, world, startLoc, why);
                return;
            }
            int[] p = s.plan.poll();
            Block floor = world.getBlockAt(p[0], p[1], p[2]);
            floor.getChunk().load();

            for (int i = 0; i < 6; i++) {
                int[] n = neighbours(p).get(i);
                Block nb = world.getBlockAt(n[0], n[1], n[2]);
                if (nb.getType() == Material.LAVA) nb.setType(Material.STONE);
            }
            if (isProtected(floor.getType())) { finish(ctx, s, world, startLoc, "hit_bedrock"); return; }

            mine(s, floor);
            mine(s, world.getBlockAt(p[0], p[1] + 1, p[2]));

            List<int[]> ns = neighbours(p);
            for (int i = 0; i < ns.size(); i++) {
                int[] n = ns.get(i);
                Block nb = world.getBlockAt(n[0], n[1], n[2]);
                if (isOre(nb.getType())) veinMine(s, world, nb, 0);
            }
            // walk down the stair just cut, rather than dropping through it
            if (entity.getLocation().distance(new Location(world, p[0] + 0.5, p[1], p[2] + 0.5)) > 3.0) {
                if (walkTo(ctx, s.npcId, p[0], p[1], p[2])) s.walking = true;
            }
        } catch (Throwable t) {
            finish(ctx, s, world, startLoc, "error: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private List<int[]> neighbours(int[] p) {
        List<int[]> out = new ArrayList<int[]>(6);
        out.add(new int[]{p[0] + 1, p[1], p[2]});
        out.add(new int[]{p[0] - 1, p[1], p[2]});
        out.add(new int[]{p[0], p[1], p[2] + 1});
        out.add(new int[]{p[0], p[1], p[2] - 1});
        out.add(new int[]{p[0], p[1] + 1, p[2]});
        out.add(new int[]{p[0], p[1] - 1, p[2]});
        return out;
    }

    private void veinMine(Session s, World world, Block b, int depth) {
        if (depth > 12 || s.broken >= s.maxBlocks) return;
        if (!isOre(b.getType())) return;
        mine(s, b);
        List<int[]> ns = neighbours(new int[]{b.getX(), b.getY(), b.getZ()});
        for (int i = 0; i < ns.size(); i++) {
            int[] n = ns.get(i);
            Block nb = world.getBlockAt(n[0], n[1], n[2]);
            if (isOre(nb.getType())) veinMine(s, world, nb, depth + 1);
        }
    }

    private void mine(Session s, Block b) {
        Material m = b.getType();
        if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return;
        if (isProtected(m)) return;
        if (m == Material.WATER || m == Material.LAVA) { b.setType(Material.STONE); return; }
        Collection<ItemStack> drops = s.tool == null ? b.getDrops() : b.getDrops(s.tool);
        for (ItemStack d : drops) {
            Integer prev = s.yield.get(d.getType());
            s.yield.put(d.getType(), (prev == null ? 0 : prev.intValue()) + d.getAmount());
        }
        b.setType(Material.AIR);
        s.broken++;
        damageTool(s);
    }

    private void damageTool(Session s) {
        if (s.tool == null) return;
        ItemMeta meta = s.tool.getItemMeta();
        if (!(meta instanceof Damageable)) return;
        Damageable d = (Damageable) meta;
        d.setDamage(d.getDamage() + 1);
        if (d.getDamage() >= s.tool.getType().getMaxDurability()) {
            s.toolBroke = true;
            s.tool = null;
            return;
        }
        s.tool.setItemMeta(meta);
    }

    /**
     * Bulk spoil a shaft produces far faster than a settlement can ever use it. A miner
     * brings home a working load and leaves the rest in the ground, otherwise a single
     * shift buries the stockpile under a thousand cobblestone and the whole economy
     * stalls on a full chest.
     */
    private static boolean isSpoil(Material m) {
        String n = m.name();
        return n.equals("COBBLESTONE") || n.equals("STONE") || n.equals("GRANITE")
                || n.equals("DIORITE") || n.equals("ANDESITE") || n.equals("TUFF")
                || n.equals("DIRT") || n.equals("GRAVEL") || n.equals("COBBLED_DEEPSLATE")
                || n.equals("DEEPSLATE") || n.equals("NETHERRACK") || n.equals("SAND");
    }

    private void finish(GadgetContext ctx, Session s, World world, Location startLoc, String reason) {
        ctx.cancelTask(s.taskId);
        SESSIONS.remove(s.npcId);
        NpcManager npcs = ctx.plugin().npcManager();
        NpcData data = npcs.get(s.npcId);
        Entity entity = data == null ? null : npcs.resolveEntity(data);
        if (entity != null) {
            if (entity.getLocation().distance(startLoc) > 6) {
                walkTo(ctx, s.npcId, startLoc.getBlockX(), startLoc.getBlockY(), startLoc.getBlockZ());
            }
            if (entity instanceof LivingEntity) {
                ((LivingEntity) entity).getEquipment().setItemInMainHand(null);
            }
        }
        JsonObject ev = new JsonObject();
        ev.addProperty("npcId", s.npcId);
        ev.addProperty("reason", reason);
        ev.addProperty("blocksBroken", s.broken);
        Inventory inv = chestAt(world, s.chest);
        JsonObject y = new JsonObject();
        int dropped = 0;
        for (Map.Entry<Material, Integer> e : s.yield.entrySet()) {
            y.addProperty(e.getKey().name(), e.getValue());
            if (inv == null) continue;
            int left = e.getValue().intValue();
            if (isSpoil(e.getKey())) left = Math.min(left, SPOIL_PER_TRIP);
            while (left > 0) {
                int n = Math.min(left, e.getKey().getMaxStackSize());
                Map<Integer, ItemStack> over = inv.addItem(new ItemStack(e.getKey(), n));
                for (ItemStack o : over.values()) dropped += o.getAmount();
                left -= n;
            }
        }
        if (inv != null && s.tool != null) inv.addItem(s.tool);
        ev.add("yield", y);
        ev.addProperty("hauledTo", s.chest.getBlockX() + "," + s.chest.getBlockY() + "," + s.chest.getBlockZ());
        ev.addProperty("overflowDropped", dropped);
        ctx.plugin().bridge().broadcastEvent("npc_mined", ev);
    }
}
