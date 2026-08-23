package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Crafting for NPCs, driven by the live vanilla recipe table rather than a hardcoded
 * ladder - so anything the game can make, an NPC can make, as its materials allow.
 *
 * Inputs are consumed from, and results delivered to, a real chest, so a settlement
 * stockpile is the unit of progress and stays finite and inspectable. Recipes needing
 * more than a 2x2 grid require a crafting table (in the chest or placed beside it),
 * which keeps the early tech ladder honest: planks and sticks by hand, a table, then
 * tools.
 *
 * Actions: "make" (craft a result), "options" (what the stockpile can currently make).
 */
public class Craft implements GadgetContract {

    private static Inventory chestAt(World w, JsonObject c) {
        Block b = w.getBlockAt(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
    }

    private static boolean hasTable(Inventory inv, World w, JsonObject c) {
        if (inv.contains(Material.CRAFTING_TABLE)) return true;
        int x = c.get("x").getAsInt();
        int y = c.get("y").getAsInt();
        int z = c.get("z").getAsInt();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (w.getBlockAt(x + dx, y + dy, z + dz).getType() == Material.CRAFTING_TABLE) return true;
                }
            }
        }
        return false;
    }

    /** The ingredient choices a recipe needs, or null if it is a kind we do not handle. */
    private static List<RecipeChoice> choicesOf(Recipe r) {
        List<RecipeChoice> out = new ArrayList<RecipeChoice>();
        if (r instanceof ShapedRecipe) {
            ShapedRecipe sr = (ShapedRecipe) r;
            Map<Character, RecipeChoice> map = sr.getChoiceMap();
            String[] shape = sr.getShape();
            for (int i = 0; i < shape.length; i++) {
                String row = shape[i];
                for (int j = 0; j < row.length(); j++) {
                    RecipeChoice ch = map.get(Character.valueOf(row.charAt(j)));
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
            int h = shape.length;
            int w = 0;
            for (int i = 0; i < shape.length; i++) w = Math.max(w, shape[i].length());
            return w > 2 || h > 2;
        }
        if (r instanceof ShapelessRecipe) return ((ShapelessRecipe) r).getChoiceList().size() > 4;
        return true;
    }

    /** Is there room for this stack without losing any of it? */
    private static boolean fits(Inventory inv, ItemStack res) {
        int room = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() == Material.AIR) { room += res.getType().getMaxStackSize(); continue; }
            if (s.isSimilar(res)) room += Math.max(0, s.getMaxStackSize() - s.getAmount());
            if (room >= res.getAmount()) return true;
        }
        return room >= res.getAmount();
    }

    /** Try to consume one set of ingredients. Returns false (consuming nothing) if short. */
    private static boolean consume(Inventory inv, List<RecipeChoice> needs, boolean dryRun) {
        int size = inv.getSize();
        ItemStack[] snapshot = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            ItemStack s = inv.getItem(i);
            snapshot[i] = s == null ? null : s.clone();
        }
        for (int n = 0; n < needs.size(); n++) {
            RecipeChoice need = needs.get(n);
            boolean found = false;
            for (int i = 0; i < size && !found; i++) {
                ItemStack s = snapshot[i];
                if (s == null || s.getAmount() <= 0) continue;
                ItemStack one = s.clone();
                one.setAmount(1);
                if (!need.test(one)) continue;
                s.setAmount(s.getAmount() - 1);
                if (s.getAmount() <= 0) snapshot[i] = null;
                found = true;
            }
            if (!found) return false;
        }
        if (!dryRun) for (int i = 0; i < size; i++) inv.setItem(i, snapshot[i]);
        return true;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "make";
        World world = ctx.world(args.has("world") ? args.get("world").getAsString() : null);
        JsonObject c = args.getAsJsonObject("chest");
        Inventory inv = chestAt(world, c);
        if (inv == null) throw new IllegalStateException("no chest at the given position");

        if (action.equals("options")) {
            JsonArray arr = new JsonArray();
            boolean table = hasTable(inv, world, c);
            Iterator<Recipe> it = Bukkit.recipeIterator();
            int found = 0;
            while (it.hasNext() && found < 60) {
                Recipe r = it.next();
                List<RecipeChoice> needs = choicesOf(r);
                if (needs == null || needs.isEmpty()) continue;
                if (needsTable(r) && !table) continue;
                if (!consume(inv, needs, true)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("result", r.getResult().getType().name());
                o.addProperty("amount", r.getResult().getAmount());
                o.addProperty("needsTable", needsTable(r));
                arr.add(o);
                found++;
            }
            JsonObject out = new JsonObject();
            out.addProperty("hasCraftingTable", table);
            out.add("canMake", arr);
            return out;
        }

        String resultName = args.get("result").getAsString().toUpperCase();
        Material want = Material.matchMaterial(resultName);
        if (want == null) throw new IllegalArgumentException("unknown material: " + resultName);
        int count = args.has("count") ? args.get("count").getAsInt() : 1;
        boolean table = hasTable(inv, world, c);

        List<Recipe> recipes = Bukkit.getRecipesFor(new ItemStack(want));
        int made = 0;
        int batches = 0;
        String blockedBy = null;
        for (int b = 0; b < count; b++) {
            boolean did = false;
            for (int i = 0; i < recipes.size() && !did; i++) {
                Recipe r = recipes.get(i);
                List<RecipeChoice> needs = choicesOf(r);
                if (needs == null || needs.isEmpty()) continue;
                if (needsTable(r) && !table) { blockedBy = "needs_crafting_table"; continue; }
                // Check the result can actually be stored BEFORE consuming anything.
                // Without this a full chest silently ate the output while the inputs were
                // still spent - which burned a settlement's cobblestone and sticks making
                // pickaxes that were destroyed on delivery, over and over.
                ItemStack res = r.getResult().clone();
                if (!fits(inv, res)) { blockedBy = "chest_full"; continue; }
                if (!consume(inv, needs, false)) { blockedBy = "missing_ingredients"; continue; }
                Map<Integer, ItemStack> over = inv.addItem(res);
                for (ItemStack o : over.values()) made -= o.getAmount(); // never silently lose output
                made += res.getAmount();
                batches++;
                did = true;
            }
            if (!did) break;
        }

        JsonObject out = new JsonObject();
        out.addProperty("result", want.name());
        out.addProperty("crafted", made);
        out.addProperty("batches", batches);
        out.addProperty("hasCraftingTable", table);
        if (made == 0) out.addProperty("blockedBy", blockedBy == null ? "no_recipe" : blockedBy);
        if (made > 0) {
            JsonObject ev = new JsonObject();
            ev.addProperty("result", want.name());
            ev.addProperty("crafted", made);
            if (args.has("npcId")) ev.addProperty("npcId", args.get("npcId").getAsString());
            ctx.plugin().bridge().broadcastEvent("npc_crafted", ev);
        }
        return out;
    }
}
