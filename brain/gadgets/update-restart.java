// MCAlive2 system gadget: update-restart
// Applies staged plugin updates automatically by restarting the server, so nobody
// has to babysit releases. Paper only swaps in a jar from plugins/update/ at
// startup, so "applying an update" means a controlled shutdown + relaunch.
//
// SAFETY SENTINEL (why this can't strand you): Bukkit's shutdown() only stops the
// JVM. If nothing relaunches it, the server stays down. So this gadget REFUSES to
// shut down unless the sentinel file written by scripts/run-server.cmd
// ("restart-loop.active", in the server working directory) exists - proof that a
// restart loop is supervising the process. No loop, no shutdown, just a log line.
//
// args: {
//   checkSeconds?: number  (default 30)  - how often to look for a staged update
//   graceSeconds?: number  (default 30)  - warning countdown when players are online
//   sentinel?: string      (default "restart-loop.active")
//   enabled?: boolean      (default true) - false stops any running watcher
// }
// returns: { ok, watching, staged, sentinelPresent, checkSeconds, graceSeconds }
package dev.celestia.mcalive2.gadget.system;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.gadget.GadgetContract;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.io.File;

public class UpdateRestart implements GadgetContract {

    private static final String TASK_PROP = "mcalive2.updrestart.task";
    private static final String ARMED_PROP = "mcalive2.updrestart.armed";

    @Override
    public JsonObject run(JsonObject args, GadgetContext ctx) {
        long checkSeconds = num(args, "checkSeconds", 30L, 5L);
        long graceSeconds = num(args, "graceSeconds", 30L, 0L);
        String sentinelName = args != null && args.has("sentinel") && args.get("sentinel").isJsonPrimitive()
                ? args.get("sentinel").getAsString() : "restart-loop.active";
        boolean enabled = !(args != null && args.has("enabled")
                && args.get("enabled").isJsonPrimitive() && !args.get("enabled").getAsBoolean());

        // Cancel any previously running watcher (idempotent across brain restarts:
        // a fresh classloader loses statics, so the task id lives in a system property).
        String prev = System.getProperty(TASK_PROP);
        if (prev != null) {
            try { ctx.cancelTask(Integer.parseInt(prev)); } catch (Exception ignored) {}
            System.clearProperty(TASK_PROP);
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("staged", stagedUpdate(ctx) != null);
        out.addProperty("sentinelPresent", new File(sentinelName).isFile());
        out.addProperty("checkSeconds", checkSeconds);
        out.addProperty("graceSeconds", graceSeconds);

        if (!enabled) {
            out.addProperty("watching", false);
            return out;
        }

        final long period = checkSeconds * 20L;
        final long grace = graceSeconds;
        final String sentinel = sentinelName;

        int taskId = ctx.runTimer(period, () -> {
            if ("1".equals(System.getProperty(ARMED_PROP))) return; // countdown already running
            File staged = stagedUpdate(ctx);
            if (staged == null) return;

            if (!loopSupervising(sentinel)) {
                ctx.plugin().getLogger().warning(
                        "MCAlive2 update staged (" + staged.getName() + ") but no restart loop detected ("
                                + sentinel + " missing or stale) - NOT restarting. Launch the server via "
                                + "scripts/run-server.cmd to enable automatic update restarts.");
                return;
            }

            System.setProperty(ARMED_PROP, "1");
            ctx.plugin().getLogger().warning(
                    "MCAlive2 update staged - restarting in " + grace + "s to apply it.");
            countdown(ctx, grace);
        });
        System.setProperty(TASK_PROP, Integer.toString(taskId));

        out.addProperty("watching", true);
        return out;
    }

    /** Warn any online players once per remaining 10s step, then shut down. */
    private static void countdown(GadgetContext ctx, long secondsLeft) {
        if (secondsLeft <= 0) {
            ctx.plugin().getLogger().warning("MCAlive2: shutting down now to apply the staged update.");
            ctx.server().shutdown();
            return;
        }
        // Operational notice, not story narration - players deserve to know the
        // server is about to bounce under them.
        if (secondsLeft % 10 == 0 || secondsLeft <= 5) {
            Component msg = Component.text(
                    "Server restarting in " + secondsLeft + "s to apply an update.", NamedTextColor.GRAY);
            for (Player p : ctx.server().getOnlinePlayers()) p.sendMessage(msg);
        }
        ctx.runLater(20L, () -> countdown(ctx, secondsLeft - 1));
    }

    /**
     * True only if the sentinel was written for THIS server launch. run-server.cmd
     * writes it immediately before starting the JVM, so a valid sentinel's mtime sits
     * just around JVM start. A leftover file from an earlier session is far older and
     * is ignored - so a bare (unsupervised) launch can never be shut down and stranded.
     */
    private static boolean loopSupervising(String sentinel) {
        File f = new File(sentinel);
        if (!f.isFile()) return false;
        long jvmStart = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        return f.lastModified() >= jvmStart - 120_000L;
    }

    /** The staged jar Paper would apply on next boot, or null if there isn't one. */
    private static File stagedUpdate(GadgetContext ctx) {
        File updateDir = new File(ctx.plugin().getDataFolder().getParentFile(), "update");
        if (!updateDir.isDirectory()) return null;
        File jar = new File(updateDir, "MCAlive2.jar");
        return jar.isFile() ? jar : null;
    }

    private static long num(JsonObject args, String key, long def, long min) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) return def;
        try { return Math.max(min, args.get(key).getAsLong()); } catch (Exception e) { return def; }
    }
}
