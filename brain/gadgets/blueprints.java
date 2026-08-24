package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;

import java.util.HashMap;
import java.util.Map;

/**
 * The house library: what people know how to build. scripts/blueprints.mjs authors and
 * pushes it (action:"put"); gadget:villages reads the bills of materials to pick what a
 * member can afford (action:"list"); gadget:people fetches the block list to raise it
 * (action:"get"). Stored as one JSON string in the world's persistent data - NOT in the
 * ledger, whose collections are fixed and whose facts feed actor prompts.
 *
 * A blueprint: { id, name, tier, w, h, d, blocks:[{dx,dy,dz,m}] bottom-up,
 * materials:{token:count}, source }. Material tokens $PLANKS/$LOG/$SLAB/$FENCE resolve
 * at build time against the builder's own wood.
 */
public class Blueprints implements GadgetContract {

    private static Map<String, JsonObject> LIB = null;

    private static org.bukkit.persistence.PersistentDataContainer pdc(GadgetContext ctx) {
        return ctx.server().getWorlds().get(0).getPersistentDataContainer();
    }

    private static void load(GadgetContext ctx) {
        if (LIB != null) return;
        LIB = new HashMap<String, JsonObject>();
        try {
            String s = pdc(ctx).get(ctx.key("blueprints-lib"), org.bukkit.persistence.PersistentDataType.STRING);
            if (s == null || s.isEmpty()) return;
            JsonArray arr = JsonParser.parseString(s).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject bp = el.getAsJsonObject();
                if (bp.has("id")) LIB.put(bp.get("id").getAsString(), bp);
            }
        } catch (Throwable ignored) { }
    }

    private static void store(GadgetContext ctx) {
        JsonArray arr = new JsonArray();
        for (JsonObject bp : LIB.values()) arr.add(bp);
        pdc(ctx).set(ctx.key("blueprints-lib"), org.bukkit.persistence.PersistentDataType.STRING, arr.toString());
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        load(ctx);
        String action = args.has("action") && !args.get("action").isJsonNull() ? args.get("action").getAsString() : "list";
        JsonObject out = new JsonObject();

        if (action.equals("put")) {
            if (!args.has("blueprints") || !args.get("blueprints").isJsonArray())
                throw new IllegalArgumentException("put needs blueprints: [...]");
            int n = 0;
            for (JsonElement el : args.getAsJsonArray("blueprints")) {
                JsonObject bp = el.getAsJsonObject();
                if (!bp.has("id") || !bp.has("blocks")) throw new IllegalArgumentException("a blueprint needs id and blocks");
                LIB.put(bp.get("id").getAsString(), bp);
                n++;
            }
            store(ctx);
            out.addProperty("stored", n);
            out.addProperty("total", LIB.size());
            return out;
        }

        if (action.equals("get")) {
            String id = args.has("id") ? args.get("id").getAsString() : "";
            JsonObject bp = LIB.get(id);
            if (bp == null) throw new IllegalArgumentException("no blueprint: " + id);
            return bp;
        }

        if (action.equals("delete")) {
            String id = args.has("id") ? args.get("id").getAsString() : "";
            out.addProperty("deleted", LIB.remove(id) != null);
            store(ctx);
            return out;
        }

        // list (the default): everything but the block lists, so choosers stay cheap
        JsonArray list = new JsonArray();
        for (JsonObject bp : LIB.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", bp.get("id").getAsString());
            if (bp.has("name")) o.add("name", bp.get("name"));
            if (bp.has("tier")) o.add("tier", bp.get("tier"));
            if (bp.has("w")) o.add("w", bp.get("w"));
            if (bp.has("d")) o.add("d", bp.get("d"));
            if (bp.has("materials")) o.add("materials", bp.get("materials"));
            o.addProperty("blocks", bp.getAsJsonArray("blocks").size());
            list.add(o);
        }
        out.add("blueprints", list);
        return out;
    }
}
