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
import org.bukkit.Particle;
import org.bukkit.Sound;
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
 * The day's work. A catalogue of things an NPC can be doing and a chooser that keeps
 * every one of them busy: nobody stands idle, and no two neighbours are doing the same
 * thing for long.
 *
 * Actions come in a few shapes - HARVEST takes blocks from the land, PLACE puts them
 * back, CRAFT and ERRAND hand off to the specialist gadgets (mine, forage, forester,
 * shelter, craft, smelt), and TEND is the small human business of standing watch,
 * tending a fire or sparring, which changes nothing but makes a settlement look lived
 * in. Each action carries the sentence shown when you click that NPC on the map.
 *
 * While a job runs the NPC's walking circuit is paused, so the behavior engine and this
 * gadget never fight over the same body, and it is resumed the moment the job ends.
 */
public class Pursuits implements GadgetContract {

    private static Integer TASK_ID = null;
    private static final Map<String, Job> JOBS = new HashMap<String, Job>();
    private static final Map<String, String> LAST_ACTION = new HashMap<String, String>();
    private static final Map<String, JsonObject> STORES = new HashMap<String, JsonObject>();
    private static final Map<String, JsonObject> WAREHOUSES = new HashMap<String, JsonObject>();
    /** NPCs another system owns - the chooser must not hand them work. */
    private static final java.util.Set<String> RESERVED = new java.util.HashSet<String>();
    /** Circuits we paused, so one can never be left stopped after its job ends. */
    private static final java.util.Set<String> PAUSED_BY_US = new java.util.HashSet<String>();
    private static final Map<String, Integer> DONE_COUNT = new LinkedHashMap<String, Integer>();
    /** Each NPC walks its own rotation through the catalogue - variety without a dice roll. */
    private static final Map<String, Integer> ROTATION = new HashMap<String, Integer>();
    private static int started = 0;
    private static long seed = 12345;

    // ---- deterministic-but-varied choice; Math.random is fine here but this keeps
    // ---- the spread even across a small roster
    private static int rand(int n) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        int v = (int) ((seed >>> 33) % n);
        return v < 0 ? -v : v;
    }

    private static class Job {
        String npcId;
        String actionId;
        String verb;
        int stepsLeft;
        String delegate;          // gadget id when the work is handed off
        List<int[]> targets = new ArrayList<int[]>();
        Material material;
        String pausedProgram;
        int produced;
        boolean walking;        // travelling to the next spot on foot
        int[] walkTarget;
    }

    // ---------------------------------------------------------------- catalogue
    private static final int HARVEST = 0, PLACE = 1, ERRAND = 2, TEND = 3, MAKE = 4;

    private static class Action {
        String id, verb;
        int kind;
        String[] materials;       // HARVEST: what to take. PLACE: what to put down.
        String delegate;          // ERRAND: gadget id
        String result;            // MAKE: what to craft/smelt
        int steps;
        String lineOnly;          // restrict to one line, or null
        Action(String id, int kind, String verb, int steps) {
            this.id = id; this.kind = kind; this.verb = verb; this.steps = steps;
        }
        Action mats(String... m) { this.materials = m; return this; }
        Action via(String g) { this.delegate = g; return this; }
        Action makes(String r) { this.result = r; return this; }
        Action only(String line) { this.lineOnly = line; return this; }
    }

    private static final List<Action> CATALOGUE = new ArrayList<Action>();
    static {
        // ---- taking from the land ----
        CATALOGUE.add(new Action("quarry_stone", HARVEST, "quarrying stone", 14).mats("STONE", "COBBLESTONE", "ANDESITE", "GRANITE", "DIORITE"));
        CATALOGUE.add(new Action("dig_sand", HARVEST, "digging sand", 12).mats("SAND", "RED_SAND"));
        CATALOGUE.add(new Action("dig_gravel", HARVEST, "sifting gravel", 12).mats("GRAVEL"));
        CATALOGUE.add(new Action("dig_clay", HARVEST, "digging clay from the bank", 10).mats("CLAY"));
        CATALOGUE.add(new Action("cut_peat", HARVEST, "cutting turf", 10).mats("DIRT", "COARSE_DIRT", "PODZOL", "ROOTED_DIRT"));
        CATALOGUE.add(new Action("pick_flowers", HARVEST, "picking flowers", 10).mats("DANDELION", "POPPY", "CORNFLOWER", "OXEYE_DAISY", "AZURE_BLUET", "ALLIUM", "BLUE_ORCHID", "WILDFLOWERS"));
        CATALOGUE.add(new Action("gather_seeds", HARVEST, "beating grass for seed", 16).mats("SHORT_GRASS", "TALL_GRASS", "FERN"));
        CATALOGUE.add(new Action("pick_berries", HARVEST, "picking berries", 10).mats("SWEET_BERRY_BUSH"));
        CATALOGUE.add(new Action("cut_reeds", HARVEST, "cutting reeds by the water", 10).mats("SUGAR_CANE"));
        CATALOGUE.add(new Action("gather_kelp", HARVEST, "hauling kelp from the shallows", 10).mats("KELP", "KELP_PLANT"));
        CATALOGUE.add(new Action("strip_leaves", HARVEST, "stripping leaves for saplings", 14).mats("OAK_LEAVES", "BIRCH_LEAVES", "JUNGLE_LEAVES", "SPRUCE_LEAVES"));
        CATALOGUE.add(new Action("pick_mushrooms", HARVEST, "gathering mushrooms", 8).mats("BROWN_MUSHROOM", "RED_MUSHROOM"));
        CATALOGUE.add(new Action("gather_moss", HARVEST, "peeling moss from the stones", 8).mats("MOSS_BLOCK", "MOSS_CARPET"));
        CATALOGUE.add(new Action("break_ice", HARVEST, "breaking ice", 8).mats("ICE", "PACKED_ICE"));

        // ---- putting back ----
        CATALOGUE.add(new Action("plant_saplings", PLACE, "planting saplings", 8).mats("OAK_SAPLING", "BIRCH_SAPLING", "JUNGLE_SAPLING", "SPRUCE_SAPLING"));
        CATALOGUE.add(new Action("light_the_camp", PLACE, "setting torches around the camp", 8).mats("TORCH"));
        CATALOGUE.add(new Action("fence_the_yard", PLACE, "fencing the yard", 12).mats("OAK_FENCE", "BIRCH_FENCE", "JUNGLE_FENCE"));
        CATALOGUE.add(new Action("pave_a_path", PLACE, "laying a path", 14).mats("COBBLESTONE", "GRAVEL", "STONE"));
        CATALOGUE.add(new Action("raise_a_cairn", PLACE, "raising a cairn", 6).mats("COBBLESTONE", "STONE"));
        CATALOGUE.add(new Action("plant_a_crop", PLACE, "planting a crop", 10).mats("WHEAT_SEEDS", "CARROT", "POTATO"));
        CATALOGUE.add(new Action("lay_a_floor", PLACE, "laying a floor", 12).mats("OAK_PLANKS", "BIRCH_PLANKS", "JUNGLE_PLANKS"));

        // ---- errands handed to the specialist gadgets ----
        CATALOGUE.add(new Action("fell_timber", ERRAND, "out felling timber", 1).via("forester"));
        CATALOGUE.add(new Action("mine_deep", ERRAND, "down the mine shaft", 1).via("mine"));
        CATALOGUE.add(new Action("forage_far", ERRAND, "foraging beyond the horizon", 1).via("forage"));
        CATALOGUE.add(new Action("build_shelter", ERRAND, "raising a shelter", 1).via("shelter"));

        // ---- the workshop ----
        CATALOGUE.add(new Action("cook_food", MAKE, "cooking at the fire", 1).makes("smelt:BEEF"));
        CATALOGUE.add(new Action("cook_pork", MAKE, "roasting pork", 1).makes("smelt:PORKCHOP"));
        CATALOGUE.add(new Action("cook_mutton", MAKE, "roasting mutton", 1).makes("smelt:MUTTON"));
        CATALOGUE.add(new Action("smelt_ore", MAKE, "smelting ore", 1).makes("smelt:RAW_IRON"));
        CATALOGUE.add(new Action("burn_charcoal", MAKE, "burning charcoal", 1).makes("smelt:OAK_LOG"));
        CATALOGUE.add(new Action("craft_planks", MAKE, "splitting planks", 1).makes("craft:OAK_PLANKS"));
        CATALOGUE.add(new Action("craft_sticks", MAKE, "whittling sticks", 1).makes("craft:STICK"));
        CATALOGUE.add(new Action("craft_torches", MAKE, "making torches", 1).makes("craft:TORCH"));
        CATALOGUE.add(new Action("craft_axe", MAKE, "fitting an axe", 1).makes("craft:STONE_AXE"));
        CATALOGUE.add(new Action("craft_shovel", MAKE, "fitting a shovel", 1).makes("craft:STONE_SHOVEL"));
        CATALOGUE.add(new Action("craft_hoe", MAKE, "fitting a hoe", 1).makes("craft:STONE_HOE"));
        CATALOGUE.add(new Action("craft_sword", MAKE, "grinding a blade", 1).makes("craft:STONE_SWORD"));
        CATALOGUE.add(new Action("craft_bowl", MAKE, "turning bowls", 1).makes("craft:BOWL"));
        CATALOGUE.add(new Action("craft_chest", MAKE, "building a chest", 1).makes("craft:CHEST"));
        CATALOGUE.add(new Action("craft_ladder", MAKE, "making ladders", 1).makes("craft:LADDER"));
        CATALOGUE.add(new Action("bake_bread", MAKE, "baking bread", 1).makes("craft:BREAD"));

        // ---- the small human business ----
        CATALOGUE.add(new Action("stand_watch", TEND, "standing watch", 10));
        CATALOGUE.add(new Action("tend_the_fire", TEND, "tending the fire", 8));
        CATALOGUE.add(new Action("spar", TEND, "sparring", 10));
        CATALOGUE.add(new Action("take_stock", TEND, "taking stock of the store", 6));
        CATALOGUE.add(new Action("survey_the_ground", TEND, "surveying the ground", 8));
        CATALOGUE.add(new Action("rest", TEND, "resting", 6));
        CATALOGUE.add(new Action("celebrate", TEND, "dancing", 10).only("line-lliira"));
        CATALOGUE.add(new Action("brood", TEND, "brooding over something unsaid", 8).only("line-vecna"));
        CATALOGUE.add(new Action("drill", TEND, "drilling for war", 10).only("line-bane"));
        CATALOGUE.add(new Action("study", TEND, "studying", 10).only("line-mystra"));
        CATALOGUE.add(new Action("hoard", TEND, "counting the hoard", 8).only("line-tiamat"));
        CATALOGUE.add(new Action("preach_light", TEND, "keeping a lantern lit", 8).only("line-pelor"));
        CATALOGUE.add(new Action("whisper_to_beasts", TEND, "whispering to something in the dark", 8).only("line-orcus"));
        CATALOGUE.add(new Action("hammer_at_the_anvil", TEND, "hammering at the anvil", 10).only("line-moradin"));
    }

    // ---------------------------------------------------------------- plumbing
    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("pursuits-generation");
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
            if (inner != null && inner.getClass().getName().contains("Pursuits")) { t.cancel(); killed++; }
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

    private static Inventory store(GadgetContext ctx, String faction) {
        JsonObject c = STORES.get(faction);
        if (c == null) return null;
        World w = ctx.world(null);
        Block b = w.getBlockAt(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        b.getChunk().load();
        BlockState st = b.getState();
        if (st instanceof Container) return ((Container) st).getInventory();
        return null;
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

    private static int countOf(Inventory inv, Material m) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType() == m) n += s.getAmount();
        }
        return n;
    }

    /** Blocks of any wanted material within reach of a point. */
    private static List<int[]> findBlocks(World w, Location at, String[] mats, int radius, int limit) {
        List<int[]> out = new ArrayList<int[]>();
        int cx = at.getBlockX(), cy = at.getBlockY(), cz = at.getBlockZ();
        for (int r = 2; r <= radius && out.size() < limit; r += 2) {
            for (int dx = -r; dx <= r && out.size() < limit; dx++) {
                for (int dz = -r; dz <= r && out.size() < limit; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;   // ring only
                    for (int dy = -4; dy <= 4; dy++) {
                        Block b = w.getBlockAt(cx + dx, cy + dy, cz + dz);
                        String n = b.getType().name();
                        for (int i = 0; i < mats.length; i++) {
                            if (n.equals(mats[i])) { out.add(new int[]{b.getX(), b.getY(), b.getZ()}); break; }
                        }
                        if (out.size() >= limit) break;
                    }
                }
            }
        }
        return out;
    }

    /** Open ground near a point where something can be set down. */
    private static List<int[]> findGround(World w, Location at, int radius, int limit) {
        List<int[]> out = new ArrayList<int[]>();
        int cx = at.getBlockX(), cz = at.getBlockZ();
        for (int r = 2; r <= radius && out.size() < limit; r++) {
            for (int dx = -r; dx <= r && out.size() < limit; dx++) {
                for (int dz = -r; dz <= r && out.size() < limit; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int gy = w.getHighestBlockYAt(cx + dx, cz + dz, HeightMap.OCEAN_FLOOR);
                    Block ground = w.getBlockAt(cx + dx, gy, cz + dz);
                    Block above = w.getBlockAt(cx + dx, gy + 1, cz + dz);
                    if (!above.getType().isAir()) continue;
                    if (ground.getType() == Material.WATER || ground.getType() == Material.LAVA) continue;
                    out.add(new int[]{cx + dx, gy + 1, cz + dz});
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- choosing
    private String factionOf(JsonObject rec) {
        return rec.has("faction") && !rec.get("faction").isJsonNull() ? rec.get("faction").getAsString() : null;
    }

    private Action byId(String id) {
        for (Action a : CATALOGUE) if (a.id.equals(id)) return a;
        return null;
    }

    private int countAny(Inventory inv, String suffix) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && s.getType().name().endsWith(suffix)) n += s.getAmount();
        }
        return n;
    }

    private boolean hasFood(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null) continue;
            String n = s.getType().name();
            if (n.equals("BEEF") || n.equals("PORKCHOP") || n.equals("MUTTON") || n.equals("CHICKEN")
                    || n.equals("RABBIT") || n.equals("SWEET_BERRIES") || n.equals("BREAD")
                    || n.equals("CARROT") || n.equals("POTATO") || n.startsWith("COOKED_")) return true;
        }
        return false;
    }

    private boolean eligible(GadgetContext ctx, Action a, String faction, Location where, Inventory inv) {
        if (a == null) return false;
        if (a.lineOnly != null && !a.lineOnly.equals(faction)) return false;
        World w = where.getWorld();
        if (a.kind == HARVEST) return !findBlocks(w, where, a.materials, 12, 1).isEmpty();
        if (a.kind == PLACE) {
            if (inv == null) return false;
            for (String m : a.materials) {
                Material mm = Material.matchMaterial(m);
                if (mm != null && countOf(inv, mm) > 0) return true;
            }
            return false;
        }
        if (a.kind == MAKE) return canMake(ctx, a, faction, inv);
        return true;
    }

    /**
     * Can this actually be made here and now? Without this every recipe scored as
     * available, so a starving NPC would keep picking "cook at the fire" in a camp with
     * no furnace and nothing to cook - a high score for an action that does nothing.
     */
    private boolean canMake(GadgetContext ctx, Action a, String faction, Inventory inv) {
        if (inv == null || a.result == null) return false;
        String[] parts = a.result.split(":");
        if (parts[0].equals("smelt")) {
            Material in = Material.matchMaterial(parts[1]);
            return in != null && countOf(inv, in) > 0 && nearFurnace(ctx, faction);
        }
        String out = parts[1];
        int planks = countAny(inv, "_PLANKS"), logs = countAny(inv, "_LOG"), sticks = countOf(inv, Material.STICK);
        if (out.endsWith("_PLANKS")) return logs > 0;
        if (out.equals("STICK")) return planks >= 2;
        if (out.equals("CRAFTING_TABLE")) return planks >= 4;
        if (out.equals("TORCH")) return sticks >= 1 && countOf(inv, Material.COAL) + countOf(inv, Material.CHARCOAL) > 0;
        if (out.equals("BREAD")) return countOf(inv, Material.WHEAT) >= 3;
        if (out.equals("CHEST")) return planks >= 8;
        if (out.equals("LADDER")) return sticks >= 7;
        if (out.equals("BOWL")) return planks >= 3;
        if (out.endsWith("_HOE") || out.endsWith("_AXE") || out.endsWith("_SHOVEL")
                || out.endsWith("_SWORD") || out.endsWith("_PICKAXE")) {
            boolean head = countOf(inv, Material.COBBLESTONE) > 0 || planks > 0;
            return sticks >= 2 && head;
        }
        return true;
    }

    /**
     * THE FLOW CHART. Every branch below is a plain condition on the world and the line
     * store - no model, no tokens. Tokens are spent only when a NEW kind of action is
     * authored into the catalogue; choosing among the actions that already exist is
     * arithmetic.
     *
     *   starving, or the larder is empty        -> go and find food
     *   no timber in store                      -> fell trees
     *   raw meat and a furnace                  -> cook it
     *   raw ore and a furnace                   -> smelt it
     *   logs but no planks                      -> split planks
     *   planks but no sticks                    -> whittle sticks
     *   no pick or axe in store                 -> make one
     *   otherwise                               -> the next job in this NPC's rotation
     *                                              that the ground and the store allow
     */
    private Action choose(GadgetContext ctx, String npcId, String faction, Location where, Inventory inv, int fed) {
        boolean furnace = inv != null && nearFurnace(ctx, faction);
        // An Ancient keeps its own ground. Sending them out on the long errands scattered
        // them a thousand blocks from their domains and left nobody near enough to answer
        // when one of the dead needed carrying home.
        boolean ancient = npcId.indexOf('-') < 0;

        if (!ancient && (fed <= 6 || inv == null || !hasFood(inv))) {
            Action a = byId("forage_far");
            if (eligible(ctx, a, faction, where, inv)) return a;
        }
        if (inv != null) {
            if (!ancient && countAny(inv, "_LOG") < 16) {
                Action a = byId("fell_timber");
                if (eligible(ctx, a, faction, where, inv)) return a;
            }
            if (furnace) {
                if (countOf(inv, Material.BEEF) > 0) return byId("cook_food");
                if (countOf(inv, Material.PORKCHOP) > 0) return byId("cook_pork");
                if (countOf(inv, Material.MUTTON) > 0) return byId("cook_mutton");
                if (countOf(inv, Material.RAW_IRON) > 0) return byId("smelt_ore");
            }
            if (countAny(inv, "_LOG") > 2 && countAny(inv, "_PLANKS") < 8) return byId("craft_planks");
            if (countAny(inv, "_PLANKS") >= 4 && countOf(inv, Material.STICK) < 8) return byId("craft_sticks");
            if (countAny(inv, "_PICKAXE") == 0 && countOf(inv, Material.STICK) >= 2) {
                Action a = byId("craft_axe");
                if (a != null) return a;
            }
        }

        // Nothing pressing: take the next thing in this NPC's own rotation that the
        // ground here and the store actually allow. A rotation rather than a dice roll,
        // so neighbours drift out of step instead of all doing the same thing.
        List<Action> ok = new ArrayList<Action>();
        for (Action a : CATALOGUE) {
            if (a.kind == ERRAND) continue;                 // errands are for the branches above
            if (ancient && a.kind == HARVEST && a.steps > 12) continue;
            if (a.id.equals(LAST_ACTION.get(npcId))) continue;
            if (eligible(ctx, a, faction, where, inv)) ok.add(a);
        }
        if (ok.isEmpty()) return byId("rest");
        Integer turn = ROTATION.get(npcId);
        int t = turn == null ? Math.abs(npcId.hashCode()) : turn.intValue();
        ROTATION.put(npcId, Integer.valueOf(t + 1));
        return ok.get(Math.abs(t) % ok.size());
    }

    private boolean nearFurnace(GadgetContext ctx, String faction) {
        JsonObject c = STORES.get(faction);
        if (c == null) return false;
        World w = ctx.world(null);
        int x = c.get("x").getAsInt(), y = c.get("y").getAsInt(), z = c.get("z").getAsInt();
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -1; dy <= 1; dy++)
                    if (w.getBlockAt(x + dx, y + dy, z + dz).getType() == Material.FURNACE) return true;
        return false;
    }


    // ================================================================= the model
    // Everything below is arithmetic on numbers already in the ledger. No model is
    // consulted to choose anything; tokens are spent only when a NEW action is written
    // into the catalogue above, with the satisfies vector that says what it is good for.

    private static final String[] NEEDS = {
        "hunger", "fatigue", "safety", "shelter", "belonging", "purpose", "curiosity", "wealth"
    };
    /** Urgency curve. At 2.0 a need at 80% short shouts four times as loud as one at 40%,
     *  which is what lets pressure win an argument without a hard threshold. */
    private static final double GAMMA = 2.0;
    private static boolean LIVE = false;      // false = score in the shadows, obey the ladder

    /** How much an action serves a need, 0-1. Defaults by kind, with the exceptions named. */
    private static double satisfies(Action a, String need) {
        String id = a.id;
        // --- named exceptions, where the action is about one particular want ---
        if (id.equals("rest")) return need.equals("fatigue") ? 1.0 : 0;
        if (id.equals("forage_far")) return need.equals("hunger") ? 1.0 : (need.equals("curiosity") ? 0.6 : 0);
        if (id.startsWith("cook_")) return need.equals("hunger") ? 0.7 : (need.equals("purpose") ? 0.3 : 0);
        if (id.equals("bake_bread")) return need.equals("hunger") ? 0.7 : 0;
        // The field is the answer to hunger, so everything on the road to it is scored
        // against hunger too: a hoe, a handful of seed, a planted row.
        if (id.equals("craft_hoe")) return need.equals("hunger") ? 0.55 : (need.equals("purpose") ? 0.3 : 0);
        if (id.equals("gather_seeds")) return need.equals("hunger") ? 0.95 : (need.equals("purpose") ? 0.3 : 0);
        if (id.equals("plant_a_crop")) return need.equals("hunger") ? 0.75 : (need.equals("purpose") ? 0.4 : 0);
        if (id.equals("stand_watch")) return need.equals("safety") ? 0.8 : (need.equals("purpose") ? 0.2 : 0);
        if (id.equals("light_the_camp")) return need.equals("safety") ? 0.9 : (need.equals("shelter") ? 0.2 : 0);
        if (id.equals("fence_the_yard")) return need.equals("safety") ? 0.6 : (need.equals("shelter") ? 0.4 : 0);
        if (id.equals("build_shelter") || id.equals("lay_a_floor")) return need.equals("shelter") ? 0.9 : 0;
        if (id.equals("tend_the_fire")) return need.equals("safety") ? 0.4 : (need.equals("belonging") ? 0.4 : 0);
        if (id.equals("celebrate")) return need.equals("belonging") ? 1.0 : 0;
        if (id.equals("spar") || id.equals("drill")) {
            if (need.equals("belonging")) return 0.6;
            if (need.equals("purpose")) return 0.5;
            return 0;
        }
        if (id.equals("survey_the_ground")) return need.equals("curiosity") ? 0.9 : 0;
        if (id.equals("study")) return need.equals("purpose") ? 0.8 : (need.equals("curiosity") ? 0.5 : 0);
        if (id.equals("hoard") || id.equals("take_stock")) return need.equals("wealth") ? 0.7 : 0;
        if (id.equals("brood") || id.equals("whisper_to_beasts")) return need.equals("purpose") ? 0.5 : 0;
        if (id.equals("preach_light")) return need.equals("safety") ? 0.5 : (need.equals("purpose") ? 0.5 : 0);
        if (id.equals("hammer_at_the_anvil")) return need.equals("purpose") ? 0.8 : 0;
        if (id.equals("mine_deep")) {
            if (need.equals("wealth")) return 0.8;
            if (need.equals("purpose")) return 0.6;
            if (need.equals("curiosity")) return 0.3;
            return 0;
        }
        if (id.equals("fell_timber")) return need.equals("wealth") ? 0.7 : (need.equals("purpose") ? 0.5 : 0);
        // --- defaults by kind ---
        if (a.kind == HARVEST) return need.equals("wealth") ? 0.5 : (need.equals("purpose") ? 0.5 : 0);
        if (a.kind == PLACE) {
            if (need.equals("shelter")) return 0.4;
            if (need.equals("purpose")) return 0.5;
            if (need.equals("safety")) return 0.2;
            return 0;
        }
        if (a.kind == MAKE) return need.equals("purpose") ? 0.7 : (need.equals("wealth") ? 0.3 : 0);
        return need.equals("belonging") ? 0.2 : 0;     // the quiet business of being present
    }

    /** Personality turns a shared need into a personal one. */
    private static double weightOf(String need, int drive, int warmth, int boldness) {
        double w = 1.0;
        if (need.equals("purpose"))   w *= 1 + drive / 3.0 * 0.6 + boldness / 3.0 * 0.2;
        if (need.equals("wealth"))    w *= 1 + drive / 3.0 * 0.4 - warmth / 3.0 * 0.3;
        if (need.equals("fatigue"))   w *= 1 - drive / 3.0 * 0.35;
        if (need.equals("belonging")) w *= 1 + warmth / 3.0 * 0.8 - drive / 3.0 * 0.2;
        if (need.equals("safety"))    w *= 1 - boldness / 3.0 * 0.5;
        if (need.equals("shelter"))   w *= 1 - boldness / 3.0 * 0.35;
        if (need.equals("curiosity")) w *= 1 + boldness / 3.0 * 0.7;
        if (need.equals("hunger"))    w *= 1.0;        // everyone eats
        return Math.max(0.25, Math.min(2.5, w));
    }

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

    /** Night is for resting and light; day is for work. Orcus keeps the other hours. */
    private static double contextOf(GadgetContext ctx, Action a, String faction) {
        long t = ctx.world(null).getTime();
        boolean night = t > 13000 && t < 23000;
        boolean nocturnal = "line-orcus".equals(faction);
        boolean working = night == nocturnal;          // their own working hours
        double c = 1.0;
        if (a.id.equals("rest")) c *= working ? 0.5 : 1.8;
        else if (a.kind == ERRAND) c *= working ? 1.15 : 0.55;
        else if (a.kind == HARVEST) c *= working ? 1.1 : 0.7;
        if (a.id.equals("light_the_camp") || a.id.equals("stand_watch")) c *= night ? 1.5 : 0.8;
        return c;
    }

    private static class Scored {
        Action action;
        double score;
        String top;          // the need that carried it
        double topShare;
    }

    /** The whole model in one place: worth = urgency x fitness x personality x context. */
    private Scored scoreAction(GadgetContext ctx, Action a, JsonObject rec, String faction) {
        int drive = axis(rec, "drive"), warmth = axis(rec, "warmth"), boldness = axis(rec, "boldness");
        double total = 0, best = 0;
        String bestNeed = null;
        for (int i = 0; i < NEEDS.length; i++) {
            String need = NEEDS[i];
            double sat = satisfies(a, need);
            if (sat <= 0) continue;
            double deficit = Math.max(0, (20.0 - needValue(rec, need)) / 20.0);
            double part = Math.pow(deficit, GAMMA) * sat * weightOf(need, drive, warmth, boldness);
            total += part;
            if (part > best) { best = part; bestNeed = need; }
        }
        total *= contextOf(ctx, a, faction);
        Integer last = null;
        if (a.id.equals(LAST_ACTION.get(rec.has("id") ? rec.get("id").getAsString() : ""))) total *= 0.35;
        Scored sc = new Scored();
        sc.action = a;
        sc.score = total;
        sc.top = bestNeed;
        sc.topShare = total > 0 ? best / total : 0;
        return sc;
    }

    private List<Scored> rank(GadgetContext ctx, JsonObject rec, String faction, Location where, Inventory inv) {
        List<Scored> out = new ArrayList<Scored>();
        boolean ancient = rec.has("id") && rec.get("id").getAsString().indexOf('-') < 0;
        for (Action a : CATALOGUE) {
            if (a.lineOnly != null && !a.lineOnly.equals(faction)) continue;
            if (ancient && a.kind == ERRAND && !a.id.equals("forage_far")) continue;
            if (!eligible(ctx, a, faction, where, inv)) continue;
            Scored sc = scoreAction(ctx, a, rec, faction);
            if (sc.score > 0) out.add(sc);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Scored>() {
            public int compare(Scored x, Scored y) { return Double.compare(y.score, x.score); }
        });
        return out;
    }

    /**
     * Pick from the best few rather than always the best. Composure sets how sharply -
     * a composed NPC nearly always takes the strongest option, an erratic one surprises
     * you. Two overrides sit ABOVE the model: nobody whittles while starving.
     */
    private Action decide(GadgetContext ctx, JsonObject rec, String faction, Location where, Inventory inv) {
        double hunger = needValue(rec, "hunger");
        if (hunger <= 3) {
            Action f = byId("forage_far");
            if (eligible(ctx, f, faction, where, inv)) return f;
        }
        List<Scored> ranked = rank(ctx, rec, faction, where, inv);
        if (ranked.isEmpty()) return byId("rest");
        int pool = Math.min(5, ranked.size());
        int composure = axis(rec, "composure");
        double sharpness = 1.6 + composure / 3.0 * 1.4;      // composed -> peaked
        double sum = 0;
        double[] wts = new double[pool];
        for (int i = 0; i < pool; i++) {
            wts[i] = Math.pow(ranked.get(i).score / Math.max(1e-6, ranked.get(0).score), sharpness);
            sum += wts[i];
        }
        double roll = (rand(10000) / 10000.0) * sum;
        for (int i = 0; i < pool; i++) {
            roll -= wts[i];
            if (roll <= 0) return ranked.get(i).action;
        }
        return ranked.get(0).action;
    }

    // ---------------------------------------------------------------- running
    /** Set off on foot. Everything an NPC does now begins with walking there. */
    private boolean walkTo(GadgetContext ctx, String npcId, int x, int y, int z) {
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

    private boolean stillWalking(GadgetContext ctx, String npcId) {
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

    /**
     * Deliberately empty. Routine life used to run on behavior programs, which block on
     * unreachable waypoints and raise behavior_blocked - a DIRECTOR WAKE EVENT. At
     * sixteen blocks a minute that alone burned the entire daily token budget. Nothing
     * here touches the behavior engine now: every decision below is plain Java.
     */
    private void pauseCircuit(GadgetContext ctx, Job job) { }

    private void resumeCircuit(GadgetContext ctx, Job job) {
        if (true) return;   // no circuits to resume: pursuits is the only driver now
        if (job.pausedProgram == null) return;
        try {
            JsonObject a = new JsonObject();
            a.addProperty("id", job.pausedProgram);
            ctx.invoke("behavior_resume", a);
            PAUSED_BY_US.remove(job.pausedProgram);
        } catch (Throwable ignored) { }
    }

    /** Resume anything we paused that no longer has a job behind it. A leaked pause
     *  reads on the map as "stopped, cannot reach its next task", which is a lie. */
    private void reconcilePauses(GadgetContext ctx) {
        for (String pid : new ArrayList<String>(PAUSED_BY_US)) {
            String npcId = pid.endsWith("-work") ? pid.substring(0, pid.length() - 5) : pid;
            if (JOBS.containsKey(npcId)) continue;
            if (RESERVED.contains(npcId)) continue;      // this one has a trade already
            try {
                JsonObject a = new JsonObject();
                a.addProperty("id", pid);
                ctx.invoke("behavior_resume", a);
            } catch (Throwable ignored) { }
            PAUSED_BY_US.remove(pid);
        }
    }

    private void startJob(GadgetContext ctx, String npcId, String faction, Action a,
                          Entity entity, Location where, Inventory inv) throws Exception {
        Job job = new Job();
        job.npcId = npcId;
        job.actionId = a.id;
        job.verb = a.verb;
        job.stepsLeft = a.steps;
        World w = where.getWorld();

        if (a.kind == HARVEST) {
            job.targets = findBlocks(w, where, a.materials, 12, a.steps);
            if (job.targets.isEmpty()) return;
        } else if (a.kind == PLACE) {
            for (String m : a.materials) {
                Material mm = Material.matchMaterial(m);
                if (mm != null && inv != null && countOf(inv, mm) > 0) { job.material = mm; break; }
            }
            if (job.material == null) return;
            job.targets = findGround(w, where, 8, a.steps);
            if (job.targets.isEmpty()) return;
        } else if (a.kind == ERRAND) {
            job.delegate = a.delegate;
            if (!startErrand(ctx, npcId, faction, a, entity)) return;
        } else if (a.kind == MAKE) {
            runMake(ctx, faction, a);
            job.stepsLeft = 3;   // stay at the bench a moment so it reads as work
        }

        pauseCircuit(ctx, job);
        JOBS.put(npcId, job);
        LAST_ACTION.put(npcId, a.id);
        Integer c = DONE_COUNT.get(a.id);
        DONE_COUNT.put(a.id, (c == null ? 0 : c.intValue()) + 1);
        started++;
    }

    private boolean startErrand(GadgetContext ctx, String npcId, String faction, Action a, Entity entity) {
        JsonObject store = STORES.get(faction);
        if (store == null) return false;
        try {
            JsonObject args = new JsonObject();
            args.addProperty("npcId", npcId);
            args.add("chest", store);
            Location l = entity.getLocation();
            JsonObject home = new JsonObject();
            home.addProperty("x", l.getBlockX());
            home.addProperty("y", l.getBlockY());
            home.addProperty("z", l.getBlockZ());
            if (a.delegate.equals("forester")) {
                args.add("anchor", home);
                args.addProperty("radius", 40);
                args.addProperty("count", 32);
            } else if (a.delegate.equals("forage")) {
                args.add("home", home);
                args.addProperty("want", 12);
                args.addProperty("stride", 24);
                args.addProperty("maxSteps", 14);
            } else if (a.delegate.equals("mine")) {
                args.addProperty("targetY", 30);
                args.addProperty("branch", 12);
                args.addProperty("maxBlocks", 90);
                args.addProperty("ticksPerBlock", 5);
            } else if (a.delegate.equals("shelter")) {
                return false;   // shelters are raised deliberately, not at random
            }
            JsonObject call = new JsonObject();
            call.addProperty("id", a.delegate);
            call.add("args", args);
            ctx.invoke("gadget_run", call);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void runMake(GadgetContext ctx, String faction, Action a) {
        JsonObject store = STORES.get(faction);
        if (store == null || a.result == null) return;
        String[] parts = a.result.split(":");
        try {
            JsonObject args = new JsonObject();
            args.add("chest", store);
            if (parts[0].equals("craft")) {
                args.addProperty("result", parts[1]);
                args.addProperty("count", 4);
            } else {
                args.addProperty("input", parts[1]);
                args.addProperty("count", 6);
            }
            JsonObject call = new JsonObject();
            call.addProperty("id", parts[0].equals("craft") ? "craft" : "smelt");
            call.add("args", args);
            ctx.invoke("gadget_run", call);
        } catch (Throwable ignored) { }
    }

    private boolean advance(GadgetContext ctx, Job job) {
        NpcManager npcs = ctx.plugin().npcManager();
        NpcData d = npcs.get(job.npcId);
        Entity e = d == null ? null : npcs.resolveEntity(d);
        if (e == null) return true;
        World w = e.getWorld();

        if (job.delegate != null) {
            try {
                JsonObject q = new JsonObject();
                q.addProperty("npcId", job.npcId);
                q.addProperty("action", "status");
                JsonObject call = new JsonObject();
                call.addProperty("id", job.delegate);
                call.add("args", q);
                JsonObject st = ctx.invoke("gadget_run", call);
                return !(st.has("running") && st.get("running").getAsBoolean());
            } catch (Throwable t) { return true; }
        }

        if (job.stepsLeft-- <= 0) return true;

        if (!job.targets.isEmpty()) {
            // walk to the next spot before touching anything, and keep waiting while
            // the legs are still going - no work is done at a distance
            if (job.walking) {
                if (stillWalking(ctx, job.npcId)) return false;
                job.walking = false;
            }
            int[] peek = job.targets.get(0);
            double away = e.getLocation().distance(new Location(w, peek[0] + 0.5, peek[1], peek[2] + 0.5));
            if (away > 4.0) {
                if (job.walkTarget == null || job.walkTarget[0] != peek[0] || job.walkTarget[2] != peek[2]) {
                    job.walkTarget = peek;
                    if (walkTo(ctx, job.npcId, peek[0], peek[1], peek[2])) { job.walking = true; return false; }
                    job.targets.remove(0);           // cannot be reached on foot: leave it be
                    return job.targets.isEmpty();
                }
                job.targets.remove(0);
                return job.targets.isEmpty();
            }
            int[] t = job.targets.remove(0);
            job.walkTarget = null;
            Block b = w.getBlockAt(t[0], t[1], t[2]);
            if (job.material == null) {
                // HARVEST: take it, and bank the drop in the line store
                if (b.getType().isAir()) return job.targets.isEmpty();
                w.playSound(b.getLocation(), b.getBlockData().getSoundGroup().getBreakSound(), 0.7f, 1.0f);
                w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 8, 0.2, 0.2, 0.2, b.getBlockData());
                java.util.Collection<ItemStack> drops = b.getDrops();
                b.setType(Material.AIR);
                Inventory inv = store(ctx, factionOfNpc(ctx, job.npcId));
                if (inv != null) for (ItemStack drop : drops) { inv.addItem(drop); job.produced += drop.getAmount(); }
            } else {
                // PLACE: set it down, paid for out of the store
                Inventory inv = store(ctx, factionOfNpc(ctx, job.npcId));
                if (inv == null || !take(inv, job.material)) return true;
                Block target = w.getBlockAt(t[0], t[1], t[2]);
                if (!target.getType().isAir()) return job.targets.isEmpty();
                // Do not build on top of somebody. Paving and flooring put blocks exactly
                // where people stand, which was walling them into the ground.
                boolean someoneThere = false;
                for (Entity other : w.getNearbyEntities(
                        new Location(w, t[0] + 0.5, t[1] + 0.5, t[2] + 0.5), 0.9, 1.4, 0.9)) {
                    if (other instanceof LivingEntity) { someoneThere = true; break; }
                }
                if (someoneThere) return job.targets.isEmpty();
                target.setType(job.material);
                w.playSound(target.getLocation(), target.getBlockData().getSoundGroup().getPlaceSound(), 0.7f, 1.0f);
                job.produced++;
            }
            return job.targets.isEmpty();
        }

        // TEND / MAKE: no world change, just the look of work being done
        Location l = e.getLocation();
        w.spawnParticle(Particle.CLOUD, l.clone().add(0, 1.2, 0), 3, 0.2, 0.2, 0.2, 0.0);
        if (job.actionId.equals("tend_the_fire")) w.playSound(l, Sound.BLOCK_FIRE_AMBIENT, 0.6f, 1.0f);
        else if (job.actionId.equals("spar") || job.actionId.equals("drill")) w.playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.0f);
        else if (job.actionId.equals("hammer_at_the_anvil")) w.playSound(l, Sound.BLOCK_ANVIL_USE, 0.4f, 1.2f);
        else if (job.actionId.equals("celebrate")) w.playSound(l, Sound.ENTITY_VILLAGER_CELEBRATE, 0.6f, 1.0f);
        l.setYaw(l.getYaw() + 35f);
        e.teleport(l);   // turning on the spot only - no change of position
        return false;
    }

    private String factionOfNpc(GadgetContext ctx, String npcId) {
        try {
            JsonObject q = new JsonObject();
            q.addProperty("collection", "npcs");
            q.addProperty("id", npcId);
            JsonObject rec = ctx.invoke("ledger_get", q);
            return factionOf(rec);
        } catch (Throwable t) { return null; }
    }

    private void beat(GadgetContext ctx) throws Exception {
        // advance everything already underway
        for (String npcId : new ArrayList<String>(JOBS.keySet())) {
            Job job = JOBS.get(npcId);
            boolean done;
            try { done = advance(ctx, job); } catch (Throwable t) { done = true; }
            if (done) {
                // Put them back where their circuit expects them. A job can carry an NPC
                // a dozen blocks off, and a circuit resumed from the wrong spot just
                // blocks on a waypoint it can no longer reach.
                try {
                    NpcManager npcs = ctx.plugin().npcManager();
                    NpcData d = npcs.get(npcId);
                    Entity e = d == null ? null : npcs.resolveEntity(d);
                    if (e != null && d.home != null && d.home.getWorld() != null
                            && e.getLocation().distance(d.home) > 6) {
                        walkTo(ctx, npcId, d.home.getBlockX(), d.home.getBlockY(), d.home.getBlockZ());
                    }
                } catch (Throwable ignored) { }
                resumeCircuit(ctx, job);
                JOBS.remove(npcId);
            }
        }

        // Keep the stores breathing. A full chest cannot take a crafted item, so a line
        // that hoards timber quietly loses the ability to make anything at all - which is
        // exactly how every line ended up unable to fashion a hoe.
        for (Map.Entry<String, JsonObject> e : STORES.entrySet()) {
            JsonObject wh = WAREHOUSES.get(e.getKey());
            if (wh == null) continue;
            Inventory inv = store(ctx, e.getKey());
            if (inv == null) continue;
            int free = 0;
            for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) free++;
            if (free > 4) continue;
            JsonObject a = new JsonObject();
            a.add("chest", e.getValue());
            a.addProperty("action", "spill");
            a.add("to", wh);
            a.addProperty("keep", 64);
            JsonObject call = new JsonObject();
            call.addProperty("id", "store");
            call.add("args", a);
            try { ctx.invoke("gadget_run", call); } catch (Throwable ignored) { }
        }

        // and give anyone idle something to do
        JsonObject q = new JsonObject();
        q.addProperty("collection", "npcs");
        JsonArray records = ctx.invoke("ledger_query", q).getAsJsonArray("records");
        NpcManager npcs = ctx.plugin().npcManager();
        for (JsonElement el : records) {
            JsonObject rec = el.getAsJsonObject();
            if (!rec.has("id")) continue;
            String npcId = rec.get("id").getAsString();
            if (JOBS.containsKey(npcId)) continue;
            if (rec.has("alive") && !rec.get("alive").getAsBoolean()) continue;
            NpcData d = npcs.get(npcId);
            if (d == null || d.dead) continue;
            Entity e = npcs.resolveEntity(d);
            if (e == null) continue;
            if (busyElsewhere(ctx, npcId)) continue;
            if (rand(100) > 55) continue;         // not everyone starts at once
            String faction = factionOf(rec);
            int fed = rec.has("hunger") && !rec.get("hunger").isJsonNull() ? rec.get("hunger").getAsInt() : 20;
            Inventory inv = store(ctx, faction);
            Action a = LIVE
                    ? decide(ctx, rec, faction, e.getLocation(), inv)
                    : choose(ctx, npcId, faction, e.getLocation(), inv, fed);
            if (a != null) startJob(ctx, npcId, faction, a, e, e.getLocation(), inv);
        }
    }

    /** Already handed to a specialist gadget by something other than us. */
    private boolean busyElsewhere(GadgetContext ctx, String npcId) {
        String[] gadgets = new String[]{"mine", "forage", "forester", "shelter"};
        for (String g : gadgets) {
            try {
                JsonObject a = new JsonObject();
                a.addProperty("npcId", npcId);
                a.addProperty("action", "status");
                JsonObject call = new JsonObject();
                call.addProperty("id", g);
                call.add("args", a);
                JsonObject st = ctx.invoke("gadget_run", call);
                if (st.has("running") && st.get("running").getAsBoolean()) return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("jobs")) {
            JsonObject out = new JsonObject();
            JsonObject m = new JsonObject();
            for (Map.Entry<String, Job> e : JOBS.entrySet()) m.addProperty(e.getKey(), e.getValue().verb);
            out.add("doing", m);
            JsonArray paused = new JsonArray();
            for (String pid : PAUSED_BY_US) paused.add(pid);
            out.add("pausedByUs", paused);
            return out;
        }
        if (action.equals("why")) {
            // What this NPC is weighing right now, and which want is doing the pulling.
            String npcId = args.get("npcId").getAsString();
            JsonObject q = new JsonObject();
            q.addProperty("collection", "npcs");
            q.addProperty("id", npcId);
            JsonObject rec = ctx.invoke("ledger_get", q);
            String faction = factionOf(rec);
            dev.celestia.mcalive2.npc.NpcData d = ctx.plugin().npcManager().get(npcId);
            org.bukkit.entity.Entity e = d == null ? null : ctx.plugin().npcManager().resolveEntity(d);
            if (e == null) throw new IllegalStateException("cannot see " + npcId);
            List<Scored> ranked = rank(ctx, rec, faction, e.getLocation(), store(ctx, faction));
            JsonObject out = new JsonObject();
            out.addProperty("npc", npcId);
            out.addProperty("mode", LIVE ? "deciding" : "shadowing the ladder");
            JsonArray top = new JsonArray();
            for (int i = 0; i < Math.min(5, ranked.size()); i++) {
                Scored sc = ranked.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("action", sc.action.verb);
                o.addProperty("score", Math.round(sc.score * 1000) / 1000.0);
                o.addProperty("mostly", sc.top == null ? "-" : sc.top);
                top.add(o);
            }
            out.add("weighing", top);
            if (rec.has("needs")) out.add("needs", rec.get("needs"));
            if (rec.has("personality")) out.add("personality", rec.get("personality"));
            return out;
        }
        // Add or drop reserved tradespeople WITHOUT restarting - a restart clears every
        // running job and rotation, which is far too violent for one new farmer.
        if (action.equals("reserve")) {
            if (args.has("add") && args.get("add").isJsonArray()) {
                for (JsonElement e : args.getAsJsonArray("add")) RESERVED.add(e.getAsString());
            }
            if (args.has("remove") && args.get("remove").isJsonArray()) {
                for (JsonElement e : args.getAsJsonArray("remove")) RESERVED.remove(e.getAsString());
            }
            JsonObject out = new JsonObject();
            JsonArray now = new JsonArray();
            for (String r : RESERVED) now.add(r);
            out.add("reserved", now);
            return out;
        }
        if (action.equals("live")) {
            LIVE = args.has("on") ? args.get("on").getAsBoolean() : true;
            JsonObject out = new JsonObject();
            out.addProperty("decidingByScore", LIVE);
            return out;
        }
        if (action.equals("catalogue")) {
            JsonObject out = new JsonObject();
            JsonArray a = new JsonArray();
            for (Action act : CATALOGUE) {
                JsonObject o = new JsonObject();
                o.addProperty("id", act.id);
                o.addProperty("verb", act.verb);
                if (act.lineOnly != null) o.addProperty("only", act.lineOnly);
                a.add(o);
            }
            out.addProperty("actions", CATALOGUE.size());
            out.add("catalogue", a);
            return out;
        }
        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("actionsAvailable", CATALOGUE.size());
            out.addProperty("jobsStarted", started);
            out.addProperty("busyNow", JOBS.size());
            JsonObject counts = new JsonObject();
            for (Map.Entry<String, Integer> e : DONE_COUNT.entrySet()) counts.addProperty(e.getKey(), e.getValue());
            out.add("timesChosen", counts);
            return out;
        }
        if (action.equals("stop")) {
            generation(ctx, true);
            int killed = reap(ctx);
            if (TASK_ID != null) { ctx.cancelTask(TASK_ID.intValue()); TASK_ID = null; }
            for (Job j : JOBS.values()) resumeCircuit(ctx, j);
            JOBS.clear();
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
        RESERVED.clear();
        if (args.has("reserved") && args.get("reserved").isJsonArray()) {
            for (JsonElement e : args.getAsJsonArray("reserved")) RESERVED.add(e.getAsString());
        }
        WAREHOUSES.clear();
        if (args.has("warehouses") && args.get("warehouses").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : args.getAsJsonObject("warehouses").entrySet()) {
                WAREHOUSES.put(e.getKey(), e.getValue().getAsJsonObject());
            }
        }
        // A redefine loses the record of what we paused, so any circuit left stopped by
        // a previous incarnation would stay stopped for good. Start from a clean slate.
        PAUSED_BY_US.clear();
        JOBS.clear();
        // nothing to reconcile: the behavior engine is no longer used for routine life

        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 30;
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
        out.addProperty("actionsAvailable", CATALOGUE.size());
        out.addProperty("lines", STORES.size());
        return out;
    }
}
