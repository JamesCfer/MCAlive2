// MCAlive2 system gadget: build-structure
// Builds a REAL building - foundation, walls with corner posts, windows, a working
// door, a gabled roof, and a furnished interior appropriate to what the building is
// for - instead of the hollow boxes you get from placing blocks by hand. An inn has
// a bar, tables and beds; a smithy has an anvil, furnaces and a water trough; a house
// has a bed, hearth and table. Every building is lit.
//
// args: {
//   x, y, z            - centre of the footprint, y near the intended ground level
//   width?, depth?     - footprint (default 9 x 7, min 5, max 31)
//   role?              - house | inn | smithy | hall | shop | barn | watchtower
//   palette?           - oak | spruce | birch | stone | copper (default oak)
//   facing?            - north | south | east | west: which way the door faces (default south)
//   storeys?           - 1 or 2 (default: 2 for inn/hall, else 1)
//   world?             - world name
//   settle?            - true (default): drop the build onto the real surface under (x,z)
// }
// returns: { ok, role, palette, facing, storeys, origin:{x,y,z}, bounds:{...},
//            blocksPlaced, interior:[what was furnished], notes:[...] }
package dev.celestia.mcalive2.gadget.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.gadget.GadgetContract;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

public class BuildStructure implements GadgetContract {

    private World w;
    private int placed = 0;
    private final List<String> notes = new ArrayList<>();
    private final List<String> interior = new ArrayList<>();

    // palette-resolved materials
    private String wall, floor, post, roof, roofSlab, door, fence, pane, base, trim;

    @Override
    public JsonObject run(JsonObject args, GadgetContext ctx) {
        w = ctx.world(str(args, "world", null));
        if (w == null) throw new IllegalArgumentException("no such world");

        int cx = (int) num(args, "x", 0, -30000000, 30000000);
        int cz = (int) num(args, "z", 0, -30000000, 30000000);
        int yArg = (int) num(args, "y", 64, -64, 320);
        int width = (int) num(args, "width", 9, 5, 31);
        int depth = (int) num(args, "depth", 7, 5, 31);
        String role = str(args, "role", "house").toLowerCase();
        String palette = str(args, "palette", "oak").toLowerCase();
        String facing = str(args, "facing", "south").toLowerCase();
        boolean settle = !(args != null && args.has("settle")
                && args.get("settle").isJsonPrimitive() && !args.get("settle").getAsBoolean());
        int storeys = (int) num(args, "storeys",
                (role.equals("inn") || role.equals("hall")) ? 2 : 1, 1, 2);
        if (role.equals("watchtower")) storeys = 2;

        resolvePalette(palette);

        // Ground: the surface under the centre, searched near the requested y so a
        // floating island resolves to the island top rather than the ground far below.
        int groundY = settle ? surfaceNear(cx, cz, yArg) : yArg - 1;

        int x1 = cx - width / 2, x2 = x1 + width - 1;
        int z1 = cz - depth / 2, z2 = z1 + depth - 1;
        int wallH = 5;                       // interior head-room per storey
        int floorY = groundY + 1;            // first interior floor level
        int topFloorY = floorY + (storeys - 1) * wallH;
        int eaveY = topFloorY + wallH;       // where the roof starts

        // 1. clear the build volume so nothing pokes through the walls
        fill(x1 - 1, floorY, z1 - 1, x2 + 1, eaveY + Math.max(width, depth) / 2 + 2, z2 + 1, "air");

        // 2. foundation: a course of stone under the whole footprint, dug down to ground
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = groundY; y >= groundY - 3; y--) {
                    if (isAirLike(x, y, z)) set(x, y, z, base); else break;
                }
                set(x, groundY, z, base);
                set(x, floorY - 1 + 1 - 1, z, base); // ensure the course under the floor
            }
        }

        // 3. floor(s)
        for (int s = 0; s < storeys; s++) {
            int fy = floorY + s * wallH;
            if (s == 0) {
                for (int x = x1 + 1; x <= x2 - 1; x++)
                    for (int z = z1 + 1; z <= z2 - 1; z++) set(x, fy, z, floor);
            } else {
                // upper floor, leaving a stairwell hole at the back-left corner
                for (int x = x1 + 1; x <= x2 - 1; x++)
                    for (int z = z1 + 1; z <= z2 - 1; z++) {
                        boolean stairwell = x <= x1 + 2 && z <= z1 + 2;
                        if (!stairwell) set(x, fy, z, floor);
                    }
                buildStairs(x1 + 1, floorY, z1 + 1, wallH);
            }
        }

        // 4. walls + corner posts + windows, per storey
        for (int s = 0; s < storeys; s++) {
            int fy = floorY + s * wallH;
            for (int y = fy; y < fy + wallH; y++) {
                for (int x = x1; x <= x2; x++) { set(x, y, z1, wall); set(x, y, z2, wall); }
                for (int z = z1; z <= z2; z++) { set(x1, y, z, wall); set(x2, y, z, wall); }
                // corner posts read as timber framing
                set(x1, y, z1, post); set(x2, y, z1, post);
                set(x1, y, z2, post); set(x2, y, z2, post);
            }
            int winY = fy + 2;
            for (int x = x1 + 2; x <= x2 - 2; x += 3) {
                set(x, winY, z1, pane); set(x, winY, z2, pane);
            }
            for (int z = z1 + 2; z <= z2 - 2; z += 3) {
                set(x1, winY, z, pane); set(x2, winY, z, pane);
            }
        }

        // 5. door on the facing wall, centred, with a lantern beside it
        int[] d = doorSpot(facing, x1, z1, x2, z2, cx, cz);
        int dx = d[0], dz = d[1];
        set(dx, floorY, dz, door + "[half=lower,facing=" + doorFacing(facing) + ",hinge=left,open=false]");
        set(dx, floorY + 1, dz, door + "[half=upper,facing=" + doorFacing(facing) + ",hinge=left,open=false]");
        int[] out = outward(facing);
        set(dx + out[0], floorY + 2, dz + out[1], "wall_torch[facing=" + doorFacing(facing) + "]");
        // a small threshold slab outside the door
        set(dx + out[0], groundY, dz + out[1], base);

        // 6. gabled roof along the longer axis, with an overhang
        buildRoof(x1, z1, x2, z2, eaveY);

        // 7. interior fittings appropriate to the building's purpose
        furnish(role, x1, z1, x2, z2, floorY, topFloorY, storeys, facing);

        // 8. light: lanterns hung from the ceiling of each storey
        for (int s = 0; s < storeys; s++) {
            int fy = floorY + s * wallH;
            set(cx, fy + wallH - 1, cz, "lantern[hanging=true]");
            if (width >= 9) {
                set(x1 + 2, fy + wallH - 1, cz, "lantern[hanging=true]");
                set(x2 - 2, fy + wallH - 1, cz, "lantern[hanging=true]");
            }
        }

        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("role", role);
        o.addProperty("palette", palette);
        o.addProperty("facing", facing);
        o.addProperty("storeys", storeys);
        JsonObject origin = new JsonObject();
        origin.addProperty("x", cx); origin.addProperty("y", floorY); origin.addProperty("z", cz);
        o.add("origin", origin);
        JsonObject b = new JsonObject();
        b.addProperty("x1", x1); b.addProperty("y1", groundY); b.addProperty("z1", z1);
        b.addProperty("x2", x2); b.addProperty("y2", eaveY + Math.max(width, depth) / 2); b.addProperty("z2", z2);
        o.add("bounds", b);
        o.addProperty("blocksPlaced", placed);
        JsonArray ia = new JsonArray(); for (String s : interior) ia.add(s); o.add("interior", ia);
        JsonArray na = new JsonArray(); for (String s : notes) na.add(s); o.add("notes", na);
        return o;
    }

    // ---------- interiors ----------

    private void furnish(String role, int x1, int z1, int x2, int z2,
                         int floorY, int topFloorY, int storeys, String facing) {
        int cx = (x1 + x2) / 2, cz = (z1 + z2) / 2;
        switch (role) {
            case "inn" -> {
                // ground floor: bar counter along the back wall, tables, hearth
                for (int x = x1 + 2; x <= x2 - 2; x++) {
                    set(x, floorY, z1 + 1, trim);
                    set(x, floorY + 1, z1 + 1, "oak_slab[type=top]");
                }
                set(x1 + 2, floorY + 1, z1 + 2, "barrel[facing=up]");
                set(x1 + 3, floorY + 1, z1 + 2, "barrel[facing=up]");
                set(x2 - 2, floorY + 1, z1 + 2, "cauldron");
                table(cx - 2, floorY, cz + 1);
                table(cx + 2, floorY, cz + 1);
                hearth(x2 - 2, floorY, z2 - 2);
                interior.add("bar counter, barrels, two tables with seating, hearth");
                if (storeys > 1) {
                    // upstairs: guest beds, each with a chest at its foot
                    int by = topFloorY;
                    bed(x1 + 2, by, z2 - 2, "north");
                    bed(x2 - 2, by, z2 - 2, "north");
                    set(x1 + 2, by, z2 - 4, "chest[facing=south]");
                    set(x2 - 2, by, z2 - 4, "chest[facing=south]");
                    interior.add("two guest beds with chests upstairs");
                }
            }
            case "smithy" -> {
                set(x1 + 2, floorY, z1 + 2, "anvil[facing=north]");
                set(x1 + 3, floorY, z1 + 2, "furnace[facing=south,lit=false]");
                set(x1 + 4, floorY, z1 + 2, "blast_furnace[facing=south,lit=false]");
                set(x2 - 2, floorY, z1 + 2, "water_cauldron[level=3]");
                set(x2 - 2, floorY, z2 - 2, "chest[facing=west]");
                set(x2 - 3, floorY, z2 - 2, "barrel[facing=up]");
                set(cx, floorY, z2 - 2, "grindstone[face=floor,facing=north]");
                set(cx + 1, floorY, z2 - 2, "smithing_table");
                hearth(cx, floorY, cz);
                interior.add("anvil, furnace and blast furnace, water trough, grindstone, smithing table, chest, forge hearth");
            }
            case "hall" -> {
                // long table down the middle with benches either side
                for (int z = z1 + 2; z <= z2 - 2; z++) {
                    set(cx, floorY + 1, z, "oak_slab[type=top]");
                    set(cx, floorY, z, fence);
                    set(cx - 1, floorY, z, "oak_stairs[facing=east,half=bottom]");
                    set(cx + 1, floorY, z, "oak_stairs[facing=west,half=bottom]");
                }
                set(x1 + 2, floorY, z1 + 2, "lectern[facing=south]");
                set(x2 - 2, floorY, z1 + 2, "bookshelf");
                hearth(x2 - 2, floorY, z2 - 2);
                interior.add("long table with benches, lectern, bookshelf, hearth");
                if (storeys > 1) interior.add("upper gallery");
            }
            case "shop" -> {
                for (int x = x1 + 2; x <= x2 - 2; x++) {
                    set(x, floorY, cz, trim);
                    set(x, floorY + 1, cz, "oak_slab[type=top]");
                }
                set(x1 + 2, floorY, z1 + 2, "barrel[facing=up]");
                set(x1 + 3, floorY, z1 + 2, "chest[facing=south]");
                set(x2 - 2, floorY, z1 + 2, "barrel[facing=up]");
                interior.add("serving counter, stock barrels and chest");
            }
            case "barn" -> {
                for (int z = z1 + 2; z <= z2 - 2; z += 2) {
                    set(x1 + 2, floorY, z, fence);
                    set(x2 - 2, floorY, z, fence);
                }
                set(cx, floorY, z1 + 2, "hay_block");
                set(cx + 1, floorY, z1 + 2, "hay_block");
                set(cx, floorY + 1, z1 + 2, "hay_block");
                set(x2 - 2, floorY, z2 - 2, "water_cauldron[level=3]");
                interior.add("stalls, hay store, water trough");
            }
            case "watchtower" -> {
                set(x1 + 1, floorY, z1 + 1, "ladder[facing=south]");
                set(x1 + 1, floorY + 1, z1 + 1, "ladder[facing=south]");
                set(cx, topFloorY, cz, "campfire[lit=true]");
                set(x1 + 2, topFloorY, z2 - 2, "chest[facing=west]");
                interior.add("ladder to the top, signal fire, supply chest");
            }
            default -> { // house
                bed(x1 + 2, floorY, z2 - 2, "north");
                set(x1 + 2, floorY, z2 - 4, "chest[facing=south]");
                table(cx + 1, floorY, cz);
                set(x2 - 2, floorY, z1 + 2, "crafting_table");
                set(x2 - 3, floorY, z1 + 2, "barrel[facing=up]");
                hearth(x2 - 2, floorY, z2 - 2);
                set(x1 + 2, floorY, z1 + 2, "flower_pot");
                interior.add("bed, chest, table with chairs, crafting table, barrel, hearth");
            }
        }
    }

    /** A table: fence-post pedestal, slab top, a chair (stairs) either side. */
    private void table(int x, int y, int z) {
        set(x, y, z, fence);
        set(x, y + 1, z, "oak_slab[type=top]");
        set(x - 1, y, z, "oak_stairs[facing=east,half=bottom]");
        set(x + 1, y, z, "oak_stairs[facing=west,half=bottom]");
    }

    /** A hearth: brick surround with a lit campfire, safe against a wall. */
    private void hearth(int x, int y, int z) {
        set(x, y - 1, z, "bricks");
        set(x, y, z, "campfire[lit=true]");
        set(x - 1, y, z, "bricks");
        set(x + 1, y, z, "bricks");
    }

    private void bed(int x, int y, int z, String facing) {
        set(x, y, z, "white_bed[part=foot,facing=" + facing + "]");
        int[] o = outward(facing);
        set(x + o[0], y, z + o[1], "white_bed[part=head,facing=" + facing + "]");
    }

    /** A simple straight stair run between storeys. */
    private void buildStairs(int x, int fromY, int z, int wallH) {
        for (int i = 0; i < wallH; i++) {
            set(x + i, fromY + i, z, "oak_stairs[facing=east,half=bottom]");
            set(x + i, fromY + i + 1, z, "air");
        }
    }

    /** Gabled roof of stairs along the longer axis, with a one-block overhang. */
    private void buildRoof(int x1, int z1, int x2, int z2, int eaveY) {
        int width = x2 - x1 + 1, depth = z2 - z1 + 1;
        boolean ridgeAlongX = width >= depth;
        int layers = (ridgeAlongX ? depth : width) / 2 + 1;
        for (int i = 0; i < layers; i++) {
            int y = eaveY + i;
            if (ridgeAlongX) {
                int zA = z1 - 1 + i, zB = z2 + 1 - i;
                for (int x = x1 - 1; x <= x2 + 1; x++) {
                    if (zA <= zB) {
                        set(x, y, zA, roof + "[facing=south,half=bottom,shape=straight]");
                        set(x, y, zB, roof + "[facing=north,half=bottom,shape=straight]");
                        for (int z = zA + 1; z <= zB - 1; z++) set(x, y, z, "air");
                    }
                }
                if (zA >= zB) for (int x = x1 - 1; x <= x2 + 1; x++) set(x, y, zA, roofSlab);
            } else {
                int xA = x1 - 1 + i, xB = x2 + 1 - i;
                for (int z = z1 - 1; z <= z2 + 1; z++) {
                    if (xA <= xB) {
                        set(xA, y, z, roof + "[facing=east,half=bottom,shape=straight]");
                        set(xB, y, z, roof + "[facing=west,half=bottom,shape=straight]");
                        for (int x = xA + 1; x <= xB - 1; x++) set(x, y, z, "air");
                    }
                }
                if (xA >= xB) for (int z = z1 - 1; z <= z2 + 1; z++) set(xA, y, z, roofSlab);
            }
        }
    }

    // ---------- helpers ----------

    private void resolvePalette(String p) {
        switch (p) {
            case "spruce" -> { wall = "spruce_planks"; floor = "spruce_planks"; post = "spruce_log[axis=y]";
                roof = "spruce_stairs"; roofSlab = "spruce_slab[type=bottom]"; door = "spruce_door";
                fence = "spruce_fence"; pane = "glass_pane"; base = "cobblestone"; trim = "stripped_spruce_log[axis=y]"; }
            case "birch" -> { wall = "birch_planks"; floor = "birch_planks"; post = "birch_log[axis=y]";
                roof = "birch_stairs"; roofSlab = "birch_slab[type=bottom]"; door = "birch_door";
                fence = "birch_fence"; pane = "glass_pane"; base = "cobblestone"; trim = "stripped_birch_log[axis=y]"; }
            case "stone" -> { wall = "stone_bricks"; floor = "stone_bricks"; post = "polished_andesite";
                roof = "stone_brick_stairs"; roofSlab = "stone_brick_slab[type=bottom]"; door = "oak_door";
                fence = "oak_fence"; pane = "glass_pane"; base = "cobblestone"; trim = "chiseled_stone_bricks"; }
            case "copper" -> { wall = "oak_planks"; floor = "oak_planks"; post = "copper_block";
                roof = "cut_copper_stairs"; roofSlab = "cut_copper_slab[type=bottom]"; door = "oak_door";
                fence = "oak_fence"; pane = "glass_pane"; base = "cobblestone"; trim = "cut_copper"; }
            default -> { wall = "oak_planks"; floor = "oak_planks"; post = "oak_log[axis=y]";
                roof = "oak_stairs"; roofSlab = "oak_slab[type=bottom]"; door = "oak_door";
                fence = "oak_fence"; pane = "glass_pane"; base = "cobblestone"; trim = "stripped_oak_log[axis=y]"; }
        }
    }

    /** Door position on the chosen wall, centred. Returns {x,z}. */
    private int[] doorSpot(String facing, int x1, int z1, int x2, int z2, int cx, int cz) {
        return switch (facing) {
            case "north" -> new int[]{cx, z1};
            case "east" -> new int[]{x2, cz};
            case "west" -> new int[]{x1, cz};
            default -> new int[]{cx, z2}; // south
        };
    }

    /** Doors face INTO the building from the outside wall they sit in. */
    private String doorFacing(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "east" -> "west";
            case "west" -> "east";
            default -> "north";
        };
    }

    private int[] outward(String facing) {
        return switch (facing) {
            case "north" -> new int[]{0, -1};
            case "east" -> new int[]{1, 0};
            case "west" -> new int[]{-1, 0};
            default -> new int[]{0, 1};
        };
    }

    /** Highest solid block at or near the hint - island-aware, like the NPC ground snap. */
    private int surfaceNear(int x, int z, int yHint) {
        for (int d = 0; d <= 40; d++) {
            int down = yHint - d, up = yHint + d;
            if (down > w.getMinHeight() && solid(x, down, z) && isAirLike(x, down + 1, z)) return down;
            if (up < w.getMaxHeight() - 2 && solid(x, up, z) && isAirLike(x, up + 1, z)) return up;
        }
        notes.add("no clear surface found near y=" + yHint + "; used the column's highest block");
        return w.getHighestBlockYAt(x, z);
    }

    private boolean solid(int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        return !b.isPassable() && b.getType() != Material.WATER && b.getType() != Material.LAVA;
    }

    private boolean isAirLike(int x, int y, int z) {
        return w.getBlockAt(x, y, z).isPassable();
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, String data) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, data);
    }

    /** Place one block from a block-data string, tolerating anything this server
     *  does not recognise rather than aborting a whole building for one block. */
    private void set(int x, int y, int z, String data) {
        try {
            BlockData bd = Bukkit.createBlockData(data.startsWith("minecraft:") ? data : "minecraft:" + data);
            w.getBlockAt(x, y, z).setBlockData(bd, false);
            placed++;
        } catch (Exception e) {
            if (notes.size() < 12) notes.add("skipped unknown block: " + data);
        }
    }

    private static String str(JsonObject o, String k, String def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static double num(JsonObject o, String k, double def, double min, double max) {
        if (o == null || !o.has(k) || !o.get(k).isJsonPrimitive()) return def;
        try { return Math.max(min, Math.min(max, o.get(k).getAsDouble())); } catch (Exception e) { return def; }
    }
}
