package celestia.gadgets;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Iterator;
import java.util.Map;

/**
 * Smelting for NPCs: turns raw drops into usable stock (raw iron to ingots, sand to
 * glass, logs to charcoal) using the live vanilla furnace recipes, paid for with real
 * fuel out of the same stockpile chest.
 *
 * A furnace must stand within two blocks of the chest, so a settlement has to build one
 * before it can work metal - the step between "we can dig" and "we can forge".
 */
public class Smelt implements GadgetContract {

    /** Items smelted per unit of fuel, mirroring vanilla burn times (200 ticks per item). */
    private static int fuelYield(Material m) {
        String n = m.name();
        if (m == Material.LAVA_BUCKET) return 100;
        if (m == Material.COAL_BLOCK) return 80;
        if (m == Material.DRIED_KELP_BLOCK) return 20;
        if (m == Material.BLAZE_ROD) return 12;
        if (m == Material.COAL || m == Material.CHARCOAL) return 8;
        if (n.endsWith("_LOG") || n.endsWith("_PLANKS") || n.endsWith("_WOOD")) return 1;
        if (m == Material.STICK) return 1;
        return 0;
    }

    /** Fuel that exists to be burnt, as opposed to timber a settlement would rather build with. */
    private static boolean isProperFuel(Material m) {
        return m == Material.COAL || m == Material.CHARCOAL || m == Material.COAL_BLOCK
                || m == Material.LAVA_BUCKET || m == Material.BLAZE_ROD || m == Material.DRIED_KELP_BLOCK;
    }

    private static Inventory chestAt(World w, JsonObject c) {
        Block b = w.getBlockAt(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    private static boolean furnaceNear(World w, JsonObject c) {
        int x = c.get("x").getAsInt();
        int y = c.get("y").getAsInt();
        int z = c.get("z").getAsInt();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Material m = w.getBlockAt(x + dx, y + dy, z + dz).getType();
                    if (m == Material.FURNACE || m == Material.BLAST_FURNACE || m == Material.SMOKER) return true;
                }
            }
        }
        return false;
    }

    private static CookingRecipe<?> recipeFor(ItemStack in) {
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (!(r instanceof CookingRecipe)) continue;
            CookingRecipe<?> cr = (CookingRecipe<?>) r;
            if (cr.getInputChoice().test(in)) return cr;
        }
        return null;
    }

    private static int countOf(Inventory inv, Material m) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType() == m) n += s.getAmount();
        }
        return n;
    }

    private static void removeOf(Inventory inv, Material m, int want) {
        for (int i = 0; i < inv.getSize() && want > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() != m) continue;
            int take = Math.min(want, s.getAmount());
            s.setAmount(s.getAmount() - take);
            inv.setItem(i, s.getAmount() <= 0 ? null : s);
            want -= take;
        }
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        World world = ctx.world(args.has("world") ? args.get("world").getAsString() : null);
        JsonObject c = args.getAsJsonObject("chest");
        Inventory inv = chestAt(world, c);
        if (inv == null) throw new IllegalStateException("no chest at the given position");

        JsonObject out = new JsonObject();
        if (!furnaceNear(world, c)) {
            out.addProperty("smelted", 0);
            out.addProperty("blockedBy", "no_furnace_within_2_blocks");
            return out;
        }

        Material input = Material.matchMaterial(args.get("input").getAsString().toUpperCase());
        if (input == null) throw new IllegalArgumentException("unknown material: " + args.get("input").getAsString());
        int want = args.has("count") ? args.get("count").getAsInt() : 8;

        CookingRecipe<?> recipe = recipeFor(new ItemStack(input));
        if (recipe == null) {
            out.addProperty("smelted", 0);
            out.addProperty("blockedBy", "not_smeltable");
            return out;
        }

        int have = countOf(inv, input);
        int todo = Math.min(want, have);
        if (todo <= 0) {
            out.addProperty("smelted", 0);
            out.addProperty("blockedBy", "no_input");
            return out;
        }

        // pay for it with whatever fuel the stockpile holds, worst fuel first
        int smelted = 0;
        int fuelUsed = 0;
        String fuelName = null;
        while (smelted < todo) {
            // pick the fuel that wastes the least heat on what is still to smelt,
            // breaking ties toward proper fuel so timber stays building material
            int remaining = todo - smelted;
            Material best = null;
            int bestYield = 0;
            int bestScore = Integer.MAX_VALUE;
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s == null) continue;
                if (s.getType() == input) continue;
                int fy = fuelYield(s.getType());
                if (fy <= 0) continue;
                int waste = Math.max(0, fy - remaining);
                int score = waste * 4 + (isProperFuel(s.getType()) ? 0 : 2);
                if (score < bestScore) { bestScore = score; best = s.getType(); bestYield = fy; }
            }
            if (best == null) break;
            removeOf(inv, best, 1);
            fuelUsed++;
            fuelName = best.name();
            int batch = Math.min(bestYield, todo - smelted);
            smelted += batch;
        }

        if (smelted > 0) {
            removeOf(inv, input, smelted);
            ItemStack res = recipe.getResult().clone();
            int total = res.getAmount() * smelted;
            int left = total;
            int dropped = 0;
            while (left > 0) {
                int n = Math.min(left, res.getType().getMaxStackSize());
                Map<Integer, ItemStack> over = inv.addItem(new ItemStack(res.getType(), n));
                for (ItemStack o : over.values()) dropped += o.getAmount();
                left -= n;
            }
            out.addProperty("produced", res.getType().name());
            out.addProperty("producedCount", total);
            out.addProperty("overflowLost", dropped);
            JsonObject ev = new JsonObject();
            ev.addProperty("input", input.name());
            ev.addProperty("produced", res.getType().name());
            ev.addProperty("count", total);
            if (args.has("npcId")) ev.addProperty("npcId", args.get("npcId").getAsString());
            ctx.plugin().bridge().broadcastEvent("npc_smelted", ev);
        } else {
            out.addProperty("blockedBy", "no_fuel");
        }
        out.addProperty("smelted", smelted);
        out.addProperty("fuelUsed", fuelUsed);
        if (fuelName != null) out.addProperty("fuel", fuelName);
        return out;
    }
}
