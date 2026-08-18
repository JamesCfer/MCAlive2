package dev.celestia.mcalive2.gadget;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host-provided handle passed to every {@link GadgetContract#run}. Gives gadgets access
 * to the plugin, the Bukkit server, world lookup, every other registered bridge command
 * (via the same {@link CommandDispatcher} the WebSocket bridge itself uses), and
 * scheduler helpers tagged to the plugin so tasks get cleaned up with it.
 */
public class GadgetContext {

    private final MCAlive2Plugin plugin;
    private final CommandDispatcher dispatcher;
    private final Map<Integer, BukkitTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskIds = new AtomicInteger();

    public GadgetContext(MCAlive2Plugin plugin, CommandDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
    }

    public MCAlive2Plugin plugin() {
        return plugin;
    }

    public Server server() {
        return plugin.getServer();
    }

    /** Resolve a world by name, or the first loaded world if {@code nameOrNull} is null. */
    public World world(String nameOrNull) {
        World world = nameOrNull == null ? Bukkit.getWorlds().get(0) : Bukkit.getWorld(nameOrNull);
        if (world == null) throw new IllegalArgumentException("unknown world: " + nameOrNull);
        return world;
    }

    /** Invoke any other registered bridge command in-process. Must be called on the main thread. */
    public JsonObject invoke(String cmd, JsonObject args) throws Exception {
        return dispatcher.invoke(cmd, args);
    }

    public void runLater(long delayTicks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, r, delayTicks);
    }

    /** Schedules a repeating task and returns a handle usable with {@link #cancelTask(int)}. */
    public int runTimer(long periodTicks, Runnable r) {
        int id = taskIds.incrementAndGet();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, r, periodTicks, periodTicks);
        tasks.put(id, task);
        return id;
    }

    public void cancelTask(int id) {
        BukkitTask task = tasks.remove(id);
        if (task != null) task.cancel();
    }

    public NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }
}
