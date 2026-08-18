package dev.celestia.mcalive2.gadget;

import com.google.gson.JsonObject;

/**
 * Contract every runtime-compiled gadget must implement. A gadget is a small piece of
 * Java source, sent over the (token-authed) bridge, compiled and loaded in-process, and
 * registered as a dispatcher command ({@code gadget:<id>}). See {@link GadgetCompiler}
 * and {@link GadgetManager}.
 */
public interface GadgetContract {

    /**
     * Run the gadget. Invoked on the main server thread by {@link GadgetManager}, so
     * implementations may freely touch the Bukkit API (directly, or via
     * {@link GadgetContext#invoke}).
     *
     * @param args the "args" object passed to gadget_run, never null (empty object if omitted)
     * @param ctx  access to the plugin, server, world lookup, other bridge commands, and
     *             the Bukkit scheduler
     * @return the JSON result returned to the caller as the command's "data" field
     */
    JsonObject run(JsonObject args, GadgetContext ctx) throws Exception;
}
