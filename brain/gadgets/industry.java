package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An autonomous settlement economy. Every beat it reads one stockpile, decides the single
 * most valuable next step up the tech ladder, and takes it - felling nothing itself, but
 * crafting, smelting, placing workshop blocks and sending miners underground.
 *
 * The policy is deliberately the vanilla progression: planks and sticks, then a crafting
 * table, then the best pickaxe the stock allows, then a shaft, then a furnace, then iron.
 * It stalls honestly when the store runs dry rather than inventing materials, and reports
 * the stall so the director can decide whether to send woodcutters, move the shaft, or
 * let the settlement fail.
 */
public class Industry implements GadgetContract {

    private static final Map<String, Site> SITES = new HashMap<String, Site>();

    private static class Site {
        String id;
        int taskId;
        JsonObject chest;
        List<String> miners = new ArrayList<String>();
        JsonObject mineFrom;
        JsonObject warehouse;
        JsonObject forestAnchor;
        List<String> woodcutters = new ArrayList<String>();
        String dir = "-z";
        int targetY = 16;
        String world;
        List<String> log = new ArrayList<String>();
        String stall;
        int beats;
    }

    private static Inventory inv(GadgetContext ctx, Site s) {
        World w = ctx.world(s.world);
        Block b = w.getBlockAt(s.chest.get("x").getAsInt(), s.chest.get("y").getAsInt(), s.chest.get("z").getAsInt());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    private static int count(Inventory inv, Material m) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType() == m) n += s.getAmount();
        }
        return n;
    }

    /** Total of every material whose name ends with the given suffix (any wood type, any ore). */
    private static int countSuffix(Inventory inv, String suffix) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType().name().endsWith(suffix)) n += s.getAmount();
        }
        return n;
    }

    private static Material firstSuffix(Inventory inv, String suffix) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType().name().endsWith(suffix)) return s.getType();
        }
        return null;
    }

    private static boolean blockNear(GadgetContext ctx, Site s, Material m) {
        World w = ctx.world(s.world);
        int x = s.chest.get("x").getAsInt();
        int y = s.chest.get("y").getAsInt();
        int z = s.chest.get("z").getAsInt();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (w.getBlockAt(x + dx, y + dy, z + dz).getType() == m) return true;
                }
            }
        }
        return false;
    }

    /** A free block beside the chest to stand a workshop block on. */
    private static int[] freeSpot(GadgetContext ctx, Site s) {
        World w = ctx.world(s.world);
        int x = s.chest.get("x").getAsInt();
        int y = s.chest.get("y").getAsInt();
        int z = s.chest.get("z").getAsInt();
        int[][] around = new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,-1},{1,-1},{-1,1},{2,0},{0,2}};
        for (int i = 0; i < around.length; i++) {
            Block b = w.getBlockAt(x + around[i][0], y, z + around[i][1]);
            if (b.getType().isAir() || b.getType() == Material.SHORT_GRASS || b.getType() == Material.TALL_GRASS) {
                return new int[]{b.getX(), b.getY(), b.getZ()};
            }
        }
        return null;
    }

    private JsonObject callGadget(GadgetContext ctx, String id, JsonObject args) throws Exception {
        JsonObject call = new JsonObject();
        call.addProperty("id", id);
        call.add("args", args);
        return ctx.invoke("gadget_run", call);
    }

    private JsonObject chestArgs(Site s) {
        JsonObject o = new JsonObject();
        o.addProperty("x", s.chest.get("x").getAsInt());
        o.addProperty("y", s.chest.get("y").getAsInt());
        o.addProperty("z", s.chest.get("z").getAsInt());
        return o;
    }

    private JsonObject craft(GadgetContext ctx, Site s, String result, int count) throws Exception {
        JsonObject a = new JsonObject();
        a.add("chest", chestArgs(s));
        a.addProperty("result", result);
        a.addProperty("count", count);
        return callGadget(ctx, "craft", a);
    }

    private void note(Site s, String what) {
        s.log.add(what);
        while (s.log.size() > 12) s.log.remove(0);
    }

    /** One decision, in ladder order. Returns a description, or null if nothing could be done. */
    private String step(GadgetContext ctx, Site s) throws Exception {
        Inventory inv = inv(ctx, s);
        if (inv == null) return null;

        int planks = countSuffix(inv, "_PLANKS");
        int logs = countSuffix(inv, "_LOG");
        int sticks = count(inv, Material.STICK);
        int cobble = count(inv, Material.COBBLESTONE);
        boolean table = blockNear(ctx, s, Material.CRAFTING_TABLE) || inv.contains(Material.CRAFTING_TABLE);
        boolean furnace = blockNear(ctx, s, Material.FURNACE);

        // Timber is the root of the whole ladder - planks, sticks, tool handles, fuel.
        // Send a woodcutter out by gadget rather than by behavior program: felling by
        // program stalls on cant_reach, teleport-based felling always completes.
        if (logs < 10 && s.forestAnchor != null && !s.woodcutters.isEmpty()) {
            for (int i = 0; i < s.woodcutters.size(); i++) {
                String npcId = s.woodcutters.get(i);
                JsonObject q = new JsonObject();
                q.addProperty("npcId", npcId);
                q.addProperty("action", "status");
                JsonObject cur = callGadget(ctx, "forester", q);
                if (cur.has("running") && cur.get("running").getAsBoolean()) continue;
                JsonObject f = new JsonObject();
                f.addProperty("npcId", npcId);
                f.add("anchor", s.forestAnchor);
                f.addProperty("radius", 32);
                f.addProperty("count", 48);
                f.add("chest", chestArgs(s));
                try {
                    callGadget(ctx, "forester", f);
                    return npcId + " went out to fell timber";
                } catch (Exception ignored) { }
            }
        }
        if (planks < 12 && logs > 2) {
            Material log = firstSuffix(inv, "_LOG");
            String planksName = log.name().replace("_LOG", "_PLANKS");
            JsonObject r = craft(ctx, s, planksName, Math.min(6, logs - 2));
            if (r.get("crafted").getAsInt() > 0) return "cut " + r.get("crafted").getAsInt() + " " + planksName;
        }
        if (sticks < 8 && planks >= 4) {
            JsonObject r = craft(ctx, s, "STICK", 2);
            if (r.get("crafted").getAsInt() > 0) return "whittled " + r.get("crafted").getAsInt() + " sticks";
        }
        if (!table && planks >= 4) {
            JsonObject r = craft(ctx, s, "CRAFTING_TABLE", 1);
            if (r.get("crafted").getAsInt() > 0) {
                int[] spot = freeSpot(ctx, s);
                if (spot != null) placeFromStore(ctx, s, "CRAFTING_TABLE", spot);
                return "raised a crafting table";
            }
        }

        boolean anyPick = countSuffix(inv, "_PICKAXE") > 0;
        int iron = count(inv, Material.IRON_INGOT);
        if (table && iron >= 3 && sticks >= 2 && count(inv, Material.IRON_PICKAXE) == 0) {
            JsonObject r = craft(ctx, s, "IRON_PICKAXE", 1);
            if (r.get("crafted").getAsInt() > 0) return "forged an iron pickaxe";
        }
        if (table && !anyPick && cobble >= 3 && sticks >= 2) {
            JsonObject r = craft(ctx, s, "STONE_PICKAXE", 1);
            if (r.get("crafted").getAsInt() > 0) return "knapped a stone pickaxe";
        }
        if (table && !anyPick && planks >= 3 && sticks >= 2) {
            JsonObject r = craft(ctx, s, "WOODEN_PICKAXE", 1);
            if (r.get("crafted").getAsInt() > 0) return "cut a wooden pickaxe";
        }
        if (!furnace && cobble >= 8 && table) {
            JsonObject r = craft(ctx, s, "FURNACE", 1);
            if (r.get("crafted").getAsInt() > 0) {
                int[] spot = freeSpot(ctx, s);
                if (spot != null) { placeFromStore(ctx, s, "FURNACE", spot); return "built a furnace"; }
                return "made a furnace but found nowhere to stand it";
            }
        }

        // smelt whatever raw stock the forge can work
        if (furnace) {
            String[] raws = new String[]{"RAW_IRON", "RAW_COPPER", "RAW_GOLD"};
            for (int i = 0; i < raws.length; i++) {
                Material m = Material.matchMaterial(raws[i]);
                if (m != null && count(inv, m) > 0) {
                    JsonObject a = new JsonObject();
                    a.add("chest", chestArgs(s));
                    a.addProperty("input", raws[i]);
                    a.addProperty("count", Math.min(8, count(inv, m)));
                    JsonObject r = callGadget(ctx, "smelt", a);
                    if (r.has("smelted") && r.get("smelted").getAsInt() > 0) {
                        return "smelted " + r.get("smelted").getAsInt() + " " + raws[i];
                    }
                }
            }
        }

        // never mine into a full store - hauled overflow is destroyed, and the world's
        // stone is finite; better to stall visibly and let the director build more storage
        int free = 0;
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) free++;
        if (free <= 3) {
            // A settlement mines faster than it builds. Rather than stall forever on a
            // full chest, haul the surplus into the warehouse and keep working.
            if (s.warehouse != null) {
                JsonObject a = new JsonObject();
                a.add("chest", chestArgs(s));
                a.addProperty("action", "spill");
                a.add("to", s.warehouse);
                a.addProperty("keep", 64);
                JsonObject r = callGadget(ctx, "store", a);
                if (r.has("moved") && r.get("moved").getAsInt() > 0) {
                    return "hauled " + r.get("moved").getAsInt() + " surplus to the warehouse";
                }
            }
            s.stall = "store_full";
            return null;
        }

        // with a pickaxe in stock, send an idle miner down
        if (countSuffix(inv, "_PICKAXE") > 0 && !s.miners.isEmpty()) {
            for (int i = 0; i < s.miners.size(); i++) {
                String npcId = s.miners.get(i);
                JsonObject st = new JsonObject();
                st.addProperty("npcId", npcId);
                st.addProperty("action", "status");
                JsonObject cur = callGadget(ctx, "mine", st);
                if (cur.has("running") && cur.get("running").getAsBoolean()) continue;
                if (s.mineFrom != null) {
                    JsonObject rev = new JsonObject();
                    rev.addProperty("id", npcId);
                    rev.addProperty("x", s.mineFrom.get("x").getAsInt());
                    rev.addProperty("y", s.mineFrom.get("y").getAsInt());
                    rev.addProperty("z", s.mineFrom.get("z").getAsInt());
                    try { ctx.invoke("npc_revive", rev); } catch (Exception ignored) { }
                }
                JsonObject a = new JsonObject();
                a.addProperty("npcId", npcId);
                a.addProperty("targetY", s.targetY);
                a.addProperty("branch", 20);
                a.addProperty("dir", s.dir);
                a.addProperty("ticksPerBlock", 4);
                a.addProperty("maxBlocks", 240);
                a.add("chest", chestArgs(s));
                try {
                    callGadget(ctx, "mine", a);
                    return npcId + " went down the shaft";
                } catch (Exception e) { /* try the next miner */ }
            }
        }
        return null;
    }

    private void placeFromStore(GadgetContext ctx, Site s, String material, int[] at) throws Exception {
        JsonObject a = new JsonObject();
        a.add("chest", chestArgs(s));
        a.addProperty("action", "place");
        a.addProperty("material", material);
        a.addProperty("x", at[0]);
        a.addProperty("y", at[1]);
        a.addProperty("z", at[2]);
        callGadget(ctx, "store", a);
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";
        String id = args.has("id") ? args.get("id").getAsString() : "default";

        if (action.equals("stop")) {
            Site s = SITES.remove(id);
            JsonObject out = new JsonObject();
            if (s != null) ctx.cancelTask(s.taskId);
            out.addProperty("stopped", s != null);
            return out;
        }
        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            Site s = SITES.get(id);
            if (s == null) { out.addProperty("running", false); return out; }
            out.addProperty("running", true);
            out.addProperty("beats", s.beats);
            if (s.stall != null) out.addProperty("stalled", s.stall);
            JsonArray l = new JsonArray();
            for (int i = 0; i < s.log.size(); i++) l.add(s.log.get(i));
            out.add("recent", l);
            return out;
        }

        if (SITES.containsKey(id)) throw new IllegalStateException("industry already running for " + id);
        final Site s = new Site();
        s.id = id;
        s.chest = args.getAsJsonObject("chest");
        s.world = args.has("world") ? args.get("world").getAsString() : null;
        if (args.has("mineFrom") && args.get("mineFrom").isJsonObject()) s.mineFrom = args.getAsJsonObject("mineFrom");
        if (args.has("warehouse") && args.get("warehouse").isJsonObject()) s.warehouse = args.getAsJsonObject("warehouse");
        if (args.has("forestAnchor") && args.get("forestAnchor").isJsonObject()) s.forestAnchor = args.getAsJsonObject("forestAnchor");
        if (args.has("woodcutters")) {
            JsonArray wc = args.getAsJsonArray("woodcutters");
            for (int i = 0; i < wc.size(); i++) s.woodcutters.add(wc.get(i).getAsString());
        }
        if (args.has("dir")) s.dir = args.get("dir").getAsString();
        if (args.has("targetY")) s.targetY = args.get("targetY").getAsInt();
        if (args.has("miners")) {
            JsonArray m = args.getAsJsonArray("miners");
            for (int i = 0; i < m.size(); i++) s.miners.add(m.get(i).getAsString());
        }
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 200;

        s.taskId = ctx.runTimer(period, new Runnable() {
            public void run() {
                s.beats++;
                try {
                    String did = step(ctx, s);
                    if (did != null) {
                        note(s, did);
                        s.stall = null;
                        JsonObject ev = new JsonObject();
                        ev.addProperty("site", s.id);
                        ev.addProperty("did", did);
                        ctx.plugin().bridge().broadcastEvent("industry_step", ev);
                    } else if (s.stall == null) {
                        s.stall = "nothing further possible from current stock";
                        JsonObject ev = new JsonObject();
                        ev.addProperty("site", s.id);
                        ev.addProperty("reason", s.stall);
                        ctx.plugin().bridge().broadcastEvent("industry_stalled", ev);
                    }
                } catch (Throwable t) {
                    note(s, "error: " + t.getClass().getSimpleName() + " " + t.getMessage());
                }
            }
        });
        SITES.put(id, s);

        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("site", id);
        out.addProperty("periodTicks", period);
        out.addProperty("miners", s.miners.size());
        return out;
    }
}
