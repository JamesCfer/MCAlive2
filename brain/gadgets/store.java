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
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a settlement stockpile: what a chest holds, how worn its tools are, and how much
 * room is left. The director needs this to decide what a settlement can attempt next -
 * it is the eyes for the mine/craft/smelt gadgets.
 */
public class Store implements GadgetContract {

    /** Remove one of a material from the stockpile and set it as a block in the world. */
    private JsonObject place(JsonObject args, GadgetContext ctx, World world, Inventory inv) {
        Material want = Material.matchMaterial(args.get("material").getAsString().toUpperCase());
        JsonObject out = new JsonObject();
        if (want == null) { out.addProperty("placed", false); out.addProperty("why", "unknown_material"); return out; }
        boolean took = false;
        for (int i = 0; i < inv.getSize() && !took; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() != want) continue;
            s.setAmount(s.getAmount() - 1);
            inv.setItem(i, s.getAmount() <= 0 ? null : s);
            took = true;
        }
        if (!took) { out.addProperty("placed", false); out.addProperty("why", "not_in_stock"); return out; }
        Block t = world.getBlockAt(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
        t.getChunk().load();
        t.setType(want);
        out.addProperty("placed", true);
        out.addProperty("material", want.name());
        out.addProperty("at", t.getX() + "," + t.getY() + "," + t.getZ());
        return out;
    }

    /**
     * Move surplus out of a working stockpile into an overflow container, keeping a
     * working amount behind. A settlement that mines faster than it builds fills its
     * chest and then silently stalls; this is the warehouse.
     */
    private JsonObject spill(JsonObject args, GadgetContext ctx, World world, Inventory from) {
        JsonObject t = args.getAsJsonObject("to");
        Block tb = world.getBlockAt(t.get("x").getAsInt(), t.get("y").getAsInt(), t.get("z").getAsInt());
        tb.getChunk().load();
        if (!(tb.getState() instanceof Container)) {
            if (!tb.getType().isAir()) {
                JsonObject bad = new JsonObject();
                bad.addProperty("moved", 0);
                bad.addProperty("why", "target is " + tb.getType().name() + ", not a container");
                return bad;
            }
            tb.setType(Material.CHEST);
        }
        Inventory to = ((Container) tb.getState()).getInventory();
        Material only = args.has("material")
                ? Material.matchMaterial(args.get("material").getAsString().toUpperCase()) : null;
        int keep = args.has("keep") ? args.get("keep").getAsInt() : 64;

        Map<String, Integer> kept = new LinkedHashMap<String, Integer>();
        int moved = 0;
        for (int i = 0; i < from.getSize(); i++) {
            ItemStack s = from.getItem(i);
            if (s == null || s.getType() == Material.AIR) continue;
            if (only != null && s.getType() != only) continue;
            String n = s.getType().name();
            Integer sofar = kept.get(n);
            int have = sofar == null ? 0 : sofar.intValue();
            if (have < keep) {                       // leave a working stock behind
                int room = Math.min(keep - have, s.getAmount());
                kept.put(n, Integer.valueOf(have + room));
                if (room >= s.getAmount()) continue;
                s.setAmount(s.getAmount() - room);
            }
            Map<Integer, ItemStack> over = to.addItem(s.clone());
            int back = 0;
            for (ItemStack o : over.values()) back += o.getAmount();
            moved += s.getAmount() - back;
            if (back <= 0) from.setItem(i, null);
            else { s.setAmount(back); from.setItem(i, s); }
        }
        int free = 0;
        for (int i = 0; i < from.getSize(); i++) if (from.getItem(i) == null) free++;
        JsonObject out = new JsonObject();
        out.addProperty("moved", moved);
        out.addProperty("freeSlotsNow", free);
        out.addProperty("warehouse", tb.getX() + "," + tb.getY() + "," + tb.getZ());
        return out;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        World world = ctx.world(args.has("world") ? args.get("world").getAsString() : null);
        JsonObject c = args.getAsJsonObject("chest");
        Block b = world.getBlockAt(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        b.getChunk().load();
        BlockState st = b.getState();
        if (!(st instanceof Container)) {
            JsonObject out = new JsonObject();
            out.addProperty("exists", false);
            out.addProperty("blockHere", b.getType().name());
            return out;
        }
        Inventory inv = ((Container) st).getInventory();

        String action = args.has("action") ? args.get("action").getAsString() : "read";
        if (action.equals("place")) return place(args, ctx, world, inv);
        if (action.equals("spill")) return spill(args, ctx, world, inv);

        Map<String, Integer> tally = new LinkedHashMap<String, Integer>();
        JsonArray tools = new JsonArray();
        int used = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() == Material.AIR) continue;
            used++;
            String n = s.getType().name();
            Integer prev = tally.get(n);
            tally.put(n, (prev == null ? 0 : prev.intValue()) + s.getAmount());
            ItemMeta meta = s.getItemMeta();
            if (meta instanceof Damageable && s.getType().getMaxDurability() > 0) {
                Damageable d = (Damageable) meta;
                if (d.getDamage() > 0) {
                    JsonObject t = new JsonObject();
                    t.addProperty("item", n);
                    t.addProperty("wear", d.getDamage() + "/" + s.getType().getMaxDurability());
                    tools.add(t);
                }
            }
        }
        JsonObject contents = new JsonObject();
        for (Map.Entry<String, Integer> e : tally.entrySet()) contents.addProperty(e.getKey(), e.getValue());

        JsonObject out = new JsonObject();
        out.addProperty("exists", true);
        out.addProperty("slotsUsed", used);
        out.addProperty("slotsTotal", inv.getSize());
        out.add("contents", contents);
        out.add("worn", tools);
        return out;
    }
}
