package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The building library: what people know how to build. scripts/blueprints.mjs authors,
 * harvests and pushes it (action:"put"); gadget:villages reads the bills of materials to
 * pick what a member can afford (action:"list", filterable by purpose/tier/size);
 * gadget:people fetches the expanded block list to raise it (action:"get").
 *
 * Stored in the COMPACT GRID form - { id, name, purpose, tier, sizeClass, w, h, d,
 * palette:[tokens], layers:["..A.B..", per y, row-major], materials:{token:count},
 * source } - gzipped+base64 as one string in the world's persistent data, so hundreds
 * of buildings cost the world file a few hundred KB, not megabytes. NOT in the ledger:
 * its collections are fixed and its facts feed actor prompts.
 *
 * "get" expands a grid to the classic bottom-up blocks list {dx,dy,dz,m}, which is the
 * contract gadget:people builds against. Material tokens $PLANKS/$LOG/$SLAB/$FENCE
 * resolve at build time against the builder's own wood.
 *
 * Actions: list [{purpose,maxTier,sizeClass}], get {id}, put {blueprints:[grids],
 * replace?}, delete {id}, stats.
 */
public class Blueprints implements GadgetContract {

    private static final String CH = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static Map<String, JsonObject> LIB = null;
    private static final Map<String, JsonObject> EXPANDED = new HashMap<String, JsonObject>();

    private static org.bukkit.persistence.PersistentDataContainer pdc(GadgetContext ctx) {
        return ctx.server().getWorlds().get(0).getPersistentDataContainer();
    }

    private static String gets(JsonObject o, String k, String d) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
    }

    private static int geti(JsonObject o, String k, int d) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : d;
    }

    private static void load(GadgetContext ctx) {
        if (LIB != null) return;
        LIB = new LinkedHashMap<String, JsonObject>();
        try {
            String s = pdc(ctx).get(ctx.key("blueprints-lib"), org.bukkit.persistence.PersistentDataType.STRING);
            if (s == null || s.isEmpty()) return;
            if (!s.startsWith("[")) {
                byte[] gz = Base64.getDecoder().decode(s);
                GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            for (JsonElement el : JsonParser.parseString(s).getAsJsonArray()) {
                JsonObject bp = el.getAsJsonObject();
                if (bp.has("id")) LIB.put(bp.get("id").getAsString(), bp);
            }
        } catch (Throwable ignored) { }
    }

    private static void store(GadgetContext ctx) throws Exception {
        JsonArray arr = new JsonArray();
        for (JsonObject bp : LIB.values()) arr.add(bp);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bytes);
        gz.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        gz.close();
        pdc(ctx).set(ctx.key("blueprints-lib"), org.bukkit.persistence.PersistentDataType.STRING,
                Base64.getEncoder().encodeToString(bytes.toByteArray()));
    }

    /** Grid -> the classic blocks-list blueprint people build from, bottom-up. */
    private static JsonObject expand(JsonObject grid) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : grid.entrySet()) {
            if (!e.getKey().equals("layers") && !e.getKey().equals("palette")) out.add(e.getKey(), e.getValue());
        }
        JsonArray palette = grid.getAsJsonArray("palette");
        JsonArray layers = grid.getAsJsonArray("layers");
        int w = geti(grid, "w", 1), d = geti(grid, "d", 1);
        JsonArray blocks = new JsonArray();
        for (int y = 0; y < layers.size(); y++) {
            String s = layers.get(y).getAsString();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '.') continue;
                int pi = CH.indexOf(c);
                if (pi < 0 || pi >= palette.size()) continue;
                JsonObject b = new JsonObject();
                b.addProperty("dx", i % w);
                b.addProperty("dy", y);
                b.addProperty("dz", i / w);
                b.addProperty("m", palette.get(pi).getAsString());
                blocks.add(b);
            }
        }
        out.add("blocks", blocks);
        return out;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        load(ctx);
        String action = gets(args, "action", "list");
        JsonObject out = new JsonObject();

        if (action.equals("put")) {
            if (!args.has("blueprints") || !args.get("blueprints").isJsonArray())
                throw new IllegalArgumentException("put needs blueprints: [...]");
            if (args.has("replace") && args.get("replace").getAsBoolean()) LIB.clear();
            int n = 0;
            for (JsonElement el : args.getAsJsonArray("blueprints")) {
                JsonObject bp = el.getAsJsonObject();
                if (!bp.has("id") || !bp.has("layers") || !bp.has("palette"))
                    throw new IllegalArgumentException("a blueprint needs id, palette and layers (grid form)");
                LIB.put(bp.get("id").getAsString(), bp);
                EXPANDED.remove(bp.get("id").getAsString());
                n++;
            }
            store(ctx);
            out.addProperty("stored", n);
            out.addProperty("total", LIB.size());
            return out;
        }

        if (action.equals("get")) {
            String id = gets(args, "id", "");
            JsonObject grid = LIB.get(id);
            if (grid == null) throw new IllegalArgumentException("no blueprint: " + id);
            JsonObject bp = EXPANDED.get(id);
            if (bp == null) {
                bp = expand(grid);
                EXPANDED.put(id, bp);
            }
            return bp;
        }

        if (action.equals("delete")) {
            String id = gets(args, "id", "");
            out.addProperty("deleted", LIB.remove(id) != null);
            EXPANDED.remove(id);
            store(ctx);
            return out;
        }

        if (action.equals("stats")) {
            Map<String, Integer> byPurpose = new LinkedHashMap<String, Integer>();
            Map<String, Integer> byTier = new LinkedHashMap<String, Integer>();
            for (JsonObject bp : LIB.values()) {
                String p = gets(bp, "purpose", "misc");
                byPurpose.put(p, byPurpose.containsKey(p) ? byPurpose.get(p) + 1 : 1);
                String t = "t" + geti(bp, "tier", 0);
                byTier.put(t, byTier.containsKey(t) ? byTier.get(t) + 1 : 1);
            }
            out.addProperty("total", LIB.size());
            JsonObject p = new JsonObject();
            for (Map.Entry<String, Integer> e : byPurpose.entrySet()) p.addProperty(e.getKey(), e.getValue());
            out.add("byPurpose", p);
            JsonObject t = new JsonObject();
            for (Map.Entry<String, Integer> e : byTier.entrySet()) t.addProperty(e.getKey(), e.getValue());
            out.add("byTier", t);
            return out;
        }

        // list (the default): metadata only, optionally filtered, so choosers stay cheap
        String purpose = gets(args, "purpose", null);
        String sizeClass = gets(args, "sizeClass", null);
        int maxTier = geti(args, "maxTier", 99);
        JsonArray list = new JsonArray();
        for (JsonObject bp : LIB.values()) {
            if (purpose != null && !purpose.equals(gets(bp, "purpose", "misc"))) continue;
            if (sizeClass != null && !sizeClass.equals(gets(bp, "sizeClass", "small"))) continue;
            if (geti(bp, "tier", 0) > maxTier) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", bp.get("id").getAsString());
            if (bp.has("name")) o.add("name", bp.get("name"));
            if (bp.has("tier")) o.add("tier", bp.get("tier"));
            if (bp.has("purpose")) o.add("purpose", bp.get("purpose"));
            if (bp.has("sizeClass")) o.add("sizeClass", bp.get("sizeClass"));
            if (bp.has("w")) o.add("w", bp.get("w"));
            if (bp.has("d")) o.add("d", bp.get("d"));
            if (bp.has("h")) o.add("h", bp.get("h"));
            if (bp.has("materials")) o.add("materials", bp.get("materials"));
            list.add(o);
        }
        out.add("blueprints", list);
        return out;
    }
}
