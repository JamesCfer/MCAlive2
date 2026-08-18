package dev.celestia.mcalive2.bridge;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.util.Json;
import org.bukkit.Bukkit;
import org.java_websocket.WebSocket;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps command names to handlers and runs them on the main server thread,
 * replying to the WebSocket client when done.
 */
public class CommandDispatcher {

    @FunctionalInterface
    public interface Handler {
        /** Runs on the main thread. Return value becomes the "data" field of the reply. */
        JsonObject handle(JsonObject args) throws Exception;
    }

    private final MCAlive2Plugin plugin;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();

    public CommandDispatcher(MCAlive2Plugin plugin) {
        this.plugin = plugin;
        register("list_commands", args -> {
            JsonObject data = new JsonObject();
            data.add("commands", Json.toArray(new TreeMap<>(handlers).keySet()));
            return data;
        });
    }

    public void register(String name, Handler handler) {
        handlers.put(name, handler);
    }

    /** Removes a previously registered command, if present. Used by gadget_delete. */
    public void unregister(String name) {
        handlers.remove(name);
    }

    /** Whether a command name has a registered handler (used by formula_define to validate steps). */
    public boolean isRegistered(String name) {
        return handlers.containsKey(name);
    }

    /**
     * Invoke a registered handler directly, in-process, bypassing the WebSocket reply
     * path. Used by the formula engine to run steps through the exact same handlers the
     * bridge uses, without needing a client connection. Caller is responsible for being
     * on the main thread (handlers assume Bukkit API access).
     */
    public JsonObject invoke(String cmd, JsonObject args) throws Exception {
        Handler handler = handlers.get(cmd);
        if (handler == null) throw new IllegalArgumentException("unknown command: " + cmd);
        JsonObject data = handler.handle(args);
        return data == null ? new JsonObject() : data;
    }

    public void dispatch(WebSocket conn, String id, String cmd, JsonObject args) {
        Handler handler = handlers.get(cmd);
        if (handler == null) {
            conn.send(Json.error(id, "unknown command: " + cmd));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                JsonObject data = handler.handle(args);
                conn.send(Json.ok(id, data == null ? new JsonObject() : data));
            } catch (Exception e) {
                conn.send(Json.error(id, e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }
}
