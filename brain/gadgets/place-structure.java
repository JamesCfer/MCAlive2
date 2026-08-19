// MCAlive2 system gadget: place-structure
// Places a REAL Minecraft structure template - Mojang's own village houses, butcher
// shops, libraries, temples, outposts - into the world. These are professionally
// designed, fully furnished buildings that ship with the game, so the director gets
// convincing architecture without generating a single block itself.
//
// Templates are loaded through Paper's StructureManager by namespaced key, e.g.
// "village/plains/houses/plains_medium_house_1". Nothing is downloaded and no extra
// library is needed - this is the game's own content, already on the server.
//
// args: {
//   key            - template key, e.g. "village/plains/houses/plains_butcher_shop_1"
//                    (call with {catalogue:true} to get a list of good ones)
//   x, y, z        - where to put it; y is the intended ground level
//   rotation?      - NONE | CLOCKWISE_90 | CLOCKWISE_180 | COUNTERCLOCKWISE_90
//   mirror?        - NONE | LEFT_RIGHT | FRONT_BACK
//   settle?        - true (default): sit the structure on the real surface under (x,z)
//   centred?       - true (default): treat x/z as the CENTRE of the footprint
//   clearAbove?    - true (default): clear the volume first so nothing pokes through
//   entities?      - false (default): also place the template's entities
//   catalogue?     - true: don't build, just return the curated template list
//   world?
// }
// returns: { ok, key, size, origin, bounds, rotation, mirror, cleaned, notes[] }
package dev.celestia.mcalive2.gadget.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.gadget.GadgetContract;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PlaceStructure implements GadgetContract {

    /** Curated, verified-good templates. Vanilla ships far more; these are the ones
     *  that read as buildings a village would actually have. */
    private static final String[][] CATALOGUE = {
            {"village/plains/houses/plains_small_house_1", "house", "plains"},
            {"village/plains/houses/plains_small_house_2", "house", "plains"},
            {"village/plains/houses/plains_medium_house_1", "house", "plains"},
            {"village/plains/houses/plains_big_house_1", "house", "plains"},
            {"village/plains/houses/plains_butcher_shop_1", "butcher", "plains"},
            {"village/plains/houses/plains_library_1", "library", "plains"},
            {"village/plains/houses/plains_armorer_house_1", "smithy", "plains"},
            {"village/plains/houses/plains_tool_smith_1", "smithy", "plains"},
            {"village/plains/houses/plains_weaponsmith_1", "smithy", "plains"},
            {"village/plains/houses/plains_stable_1", "stable", "plains"},
            {"village/plains/houses/plains_farm_1", "farm", "plains"},
            {"village/plains/town_centers/plains_fountain_01", "town centre", "plains"},
            {"village/plains/town_centers/plains_meeting_point_1", "town centre", "plains"},
            {"village/taiga/houses/taiga_small_house_1", "house", "taiga"},
            {"village/taiga/houses/taiga_medium_house_1", "house", "taiga"},
            {"village/taiga/houses/taiga_armorer_2", "smithy", "taiga"},
            {"village/savanna/houses/savanna_small_house_1", "house", "savanna"},
            {"village/savanna/houses/savanna_butchers_shop_1", "butcher", "savanna"},
            {"village/snowy/houses/snowy_medium_house_1", "house", "snowy"},
            {"village/snowy/houses/snowy_armorer_1", "smithy", "snowy"},
            {"village/desert/houses/desert_small_house_1", "house", "desert"},
            {"village/desert/houses/desert_blacksmith_1", "smithy", "desert"},
            {"pillager_outpost/watchtower", "watchtower", "any"},
            {"igloo/top", "shelter", "snowy"},
    };

    @Override
    public JsonObject run(JsonObject args, GadgetContext ctx) {
        JsonObject out = new JsonObject();
        List<String> notes = new ArrayList<>();

        if (bool(args, "catalogue", false)) {
            JsonArray arr = new JsonArray();
            for (String[] row : CATALOGUE) {
                JsonObject e = new JsonObject();
                e.addProperty("key", row[0]);
                e.addProperty("kind", row[1]);
                e.addProperty("style", row[2]);
                arr.add(e);
            }
            out.addProperty("ok", true);
            out.add("catalogue", arr);
            out.addProperty("note", "any vanilla structure template key works, not just these");
            return out;
        }

        String key = str(args, "key", null);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required (or pass catalogue:true)");
        World w = ctx.world(str(args, "world", null));
        if (w == null) throw new IllegalArgumentException("no such world");

        Structure structure = ctx.server().getStructureManager()
                .loadStructure(key.contains(":") ? NamespacedKey.fromString(key) : NamespacedKey.minecraft(key));
        if (structure == null) throw new IllegalArgumentException("no such structure template: " + key);

        StructureRotation rotation = enumOr(StructureRotation.class, str(args, "rotation", "NONE"), StructureRotation.NONE);
        Mirror mirror = enumOr(Mirror.class, str(args, "mirror", "NONE"), Mirror.NONE);

        BlockVector size = structure.getSize();
        int sx = size.getBlockX(), sy = size.getBlockY(), sz = size.getBlockZ();
        // a 90-degree turn swaps the footprint's axes
        boolean swap = rotation == StructureRotation.CLOCKWISE_90 || rotation == StructureRotation.COUNTERCLOCKWISE_90;
        int fx = swap ? sz : sx, fz = swap ? sx : sz;

        int cx = (int) num(args, "x", 0), cz = (int) num(args, "z", 0);
        int yArg = (int) num(args, "y", 64);
        boolean centred = bool(args, "centred", true);
        int x1 = centred ? cx - fx / 2 : cx;
        int z1 = centred ? cz - fz / 2 : cz;

        int baseY = yArg;
        if (bool(args, "settle", true)) {
            // median surface across the footprint's corners and centre, searched near
            // the requested y so a floating island resolves to the island top.
            int[][] probes = {{x1, z1}, {x1 + fx - 1, z1}, {x1, z1 + fz - 1},
                    {x1 + fx - 1, z1 + fz - 1}, {x1 + fx / 2, z1 + fz / 2}};
            List<Integer> ys = new ArrayList<>();
            for (int[] p : probes) ys.add(surfaceNear(w, p[0], p[1], yArg, notes));
            ys.sort(null);
            baseY = ys.get(ys.size() / 2) + 1; // sit ON the surface
        }

        if (bool(args, "clearAbove", true)) {
            for (int x = x1; x < x1 + fx; x++)
                for (int z = z1; z < z1 + fz; z++)
                    for (int y = baseY; y < baseY + sy + 1; y++)
                        w.getBlockAt(x, y, z).setType(Material.AIR, false);
        }

        Location at = new Location(w, x1, baseY, z1);
        structure.place(at, bool(args, "entities", false), rotation, mirror, -1, 1.0f, new Random());

        // Village templates are jigsaw pieces: they contain jigsaw and structure blocks
        // that vanilla worldgen consumes during assembly. Placed raw they would sit
        // there as visible oddities, so strip them.
        int cleaned = 0;
        for (int x = x1 - 1; x <= x1 + fx; x++) {
            for (int z = z1 - 1; z <= z1 + fz; z++) {
                for (int y = baseY - 1; y <= baseY + sy + 1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m == Material.JIGSAW || m == Material.STRUCTURE_BLOCK || m == Material.STRUCTURE_VOID) {
                        b.setType(Material.AIR, false);
                        cleaned++;
                    }
                }
            }
        }

        out.addProperty("ok", true);
        out.addProperty("key", key);
        out.addProperty("size", sx + "x" + sy + "x" + sz);
        JsonObject o = new JsonObject();
        o.addProperty("x", x1); o.addProperty("y", baseY); o.addProperty("z", z1);
        out.add("origin", o);
        JsonObject b = new JsonObject();
        b.addProperty("x1", x1); b.addProperty("y1", baseY); b.addProperty("z1", z1);
        b.addProperty("x2", x1 + fx - 1); b.addProperty("y2", baseY + sy - 1); b.addProperty("z2", z1 + fz - 1);
        out.add("bounds", b);
        out.addProperty("rotation", rotation.name());
        out.addProperty("mirror", mirror.name());
        out.addProperty("cleaned", cleaned);
        JsonArray na = new JsonArray(); for (String n : notes) na.add(n); out.add("notes", na);
        return out;
    }

    private static int surfaceNear(World w, int x, int z, int yHint, List<String> notes) {
        for (int d = 0; d <= 40; d++) {
            int down = yHint - d, up = yHint + d;
            if (down > w.getMinHeight() && solid(w, x, down, z) && w.getBlockAt(x, down + 1, z).isPassable()) return down;
            if (up < w.getMaxHeight() - 2 && solid(w, x, up, z) && w.getBlockAt(x, up + 1, z).isPassable()) return up;
        }
        if (notes.size() < 6) notes.add("no clear surface near y=" + yHint + " at " + x + "," + z + "; used highest block");
        return w.getHighestBlockYAt(x, z);
    }

    private static boolean solid(World w, int x, int y, int z) {
        Block b = w.getBlockAt(x, y, z);
        return !b.isPassable() && b.getType() != Material.WATER && b.getType() != Material.LAVA;
    }

    private static <T extends Enum<T>> T enumOr(Class<T> type, String name, T def) {
        if (name == null) return def;
        try { return Enum.valueOf(type, name.toUpperCase()); } catch (Exception e) { return def; }
    }

    private static String str(JsonObject o, String k, String def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }

    private static double num(JsonObject o, String k, double def) {
        if (o == null || !o.has(k) || !o.get(k).isJsonPrimitive()) return def;
        try { return o.get(k).getAsDouble(); } catch (Exception e) { return def; }
    }
}
