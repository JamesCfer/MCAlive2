package dev.celestia.mcalive2.senses;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.BridgeServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Fires a {@code world_turn} bridge event every {@code world-turn-minutes} (0 disables) -
 * the slow "the world itself gets a turn" heartbeat: the brain can use it to advance
 * off-screen life on a cadence independent of anything players do. Fires only while at
 * least one player is online; an empty server accrues no turns (the elapsed clock is
 * simply not reset, so the first player to join may be greeted by an immediately-due
 * turn, which is the intended "the world moved on while you were away" feel).
 *
 * The payload is deliberately minimal ({@code uptimeMinutes} + {@code onlinePlayers});
 * richer world-state fields belong to a future behavior engine, added additively.
 */
public class WorldTurnTracker {

    private final MCAlive2Plugin plugin;
    private final BridgeServer bridge;
    private final long startedMs = System.currentTimeMillis();
    private long lastFireMs = startedMs;

    public WorldTurnTracker(MCAlive2Plugin plugin, BridgeServer bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    public void tick() {
        long periodMillis = plugin.getConfig().getLong("world-turn-minutes", 90) * 60_000L;
        if (periodMillis <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastFireMs < periodMillis) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        lastFireMs = now;

        JsonObject data = new JsonObject();
        data.addProperty("uptimeMinutes", (now - startedMs) / 60_000L);
        JsonArray players = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
        data.add("onlinePlayers", players);
        bridge.broadcastEvent("world_turn", data);
    }
}
