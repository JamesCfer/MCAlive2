package dev.celestia.mcalive2.senses;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.BridgeServer;
import dev.celestia.mcalive2.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Fires a light {@code player_idle_scene} "heartbeat" for a player once a whole
 * {@code idle-scene-minutes} period has passed without any other broadcast event
 * involving them (join/quit/chat/explored/combat/etc — tracked centrally in
 * {@link BridgeServer#lastEventAt}, updated wherever events are broadcast).
 *
 * A player who joined less than one period ago never fires: their {@code player_join}
 * broadcast itself sets {@code lastEventAt}, so the elapsed-quiet check can't yet
 * satisfy a full period. Firing also updates {@code lastEventAt} for that player (since
 * the payload carries a {@code player} field), which naturally caps this at most once
 * per player per period.
 */
public class IdleSceneTracker {

    private final MCAlive2Plugin plugin;
    private final BridgeServer bridge;

    public IdleSceneTracker(MCAlive2Plugin plugin, BridgeServer bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
    }

    public void tick() {
        long periodMillis = plugin.getConfig().getLong("idle-scene-minutes", 12) * 60_000L;
        if (periodMillis <= 0) return;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long last = bridge.lastEventAt(player.getName());
            if (last == null) continue; // no join event recorded yet; play it safe and skip
            long quietMillis = now - last;
            if (quietMillis < periodMillis) continue;

            JsonObject data = new JsonObject();
            data.addProperty("player", player.getName());
            data.add("location", Json.locationJson(player.getLocation()));
            data.addProperty("minutesQuiet", quietMillis / 60_000L);
            bridge.broadcastEvent("player_idle_scene", data);
        }
    }
}
