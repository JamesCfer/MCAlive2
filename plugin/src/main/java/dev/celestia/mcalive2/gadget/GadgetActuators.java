package dev.celestia.mcalive2.gadget;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import dev.celestia.mcalive2.util.Json;

/**
 * Bridge-facing wiring for the gadget host: registers {@code gadget_define/run/list/
 * get/delete}. Gated by {@code gadgets.enabled} in config.yml - when false, gadget_define
 * and gadget_run refuse outright so no new code compiles or runs, freezing the plugin's
 * server-side capability set at whatever was already loaded.
 */
public class GadgetActuators {

    private final MCAlive2Plugin plugin;
    private final GadgetManager manager;

    public GadgetActuators(MCAlive2Plugin plugin, GadgetManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void register(CommandDispatcher d) {
        d.register("gadget_define", this::define);
        d.register("gadget_run", this::run);
        d.register("gadget_list", this::list);
        d.register("gadget_get", this::get);
        d.register("gadget_delete", this::delete);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("gadgets.enabled", true);
    }

    private JsonObject define(JsonObject args) throws Exception {
        if (!enabled()) throw new IllegalStateException("gadgets are disabled (gadgets.enabled: false in config.yml)");
        String id = Json.reqString(args, "id");
        String source = Json.reqString(args, "source");
        String description = Json.optString(args, "description", "");
        manager.define(id, source, description);
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("registered", "gadget:" + id);
        return out;
    }

    private JsonObject run(JsonObject args) {
        if (!enabled()) throw new IllegalStateException("gadgets are disabled (gadgets.enabled: false in config.yml)");
        String id = Json.reqString(args, "id");
        JsonObject gadgetArgs = args.has("args") && args.get("args").isJsonObject()
                ? args.getAsJsonObject("args") : new JsonObject();
        return manager.run(id, gadgetArgs);
    }

    private JsonObject list(JsonObject args) {
        JsonObject out = new JsonObject();
        out.add("gadgets", manager.list());
        return out;
    }

    private JsonObject get(JsonObject args) {
        String id = Json.reqString(args, "id");
        return manager.get(id);
    }

    private JsonObject delete(JsonObject args) {
        String id = Json.reqString(args, "id");
        manager.delete(id);
        return new JsonObject();
    }
}
