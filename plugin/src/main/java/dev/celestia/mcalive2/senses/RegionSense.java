package dev.celestia.mcalive2.senses;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.bridge.BridgeServer;
import dev.celestia.mcalive2.ledger.Ledger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Wires the Bukkit-free {@link RegionTracker} to the live server: every tick it samples
 * online player positions and the ledger's current {@code places} collection (read fresh
 * each call — this task runs every 20 ticks, i.e. at most once per second, so nothing
 * here ever holds a stale copy of a region's bounds), and pushes {@code region_enter} /
 * {@code region_exit} events for every transition the tracker reports.
 */
public class RegionSense implements RegionTracker.Listener {

    private final BridgeServer bridge;
    private final Ledger ledger;
    private final RegionTracker tracker = new RegionTracker();

    public RegionSense(BridgeServer bridge, Ledger ledger) {
        this.bridge = bridge;
        this.ledger = ledger;
    }

    /** Call every 20 ticks. */
    public void tick() {
        Map<String, RegionTracker.Position> positions = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            if (loc.getWorld() == null) continue;
            positions.put(player.getName(),
                    new RegionTracker.Position(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()));
        }
        String defaultWorld = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        tracker.tick(positions, ledger.all("places"), defaultWorld, this);
    }

    @Override
    public void onEnter(String player, String regionId, String regionName, RegionTracker.Position position) {
        bridge.broadcastEvent("region_enter", payload(player, regionId, regionName, position));
    }

    @Override
    public void onExit(String player, String regionId, String regionName, RegionTracker.Position position) {
        bridge.broadcastEvent("region_exit", payload(player, regionId, regionName, position));
    }

    private JsonObject payload(String player, String regionId, String regionName, RegionTracker.Position position) {
        JsonObject data = new JsonObject();
        data.addProperty("player", player);
        data.addProperty("regionId", regionId);
        data.addProperty("regionName", regionName);
        JsonObject loc = new JsonObject();
        if (position != null) {
            loc.addProperty("world", position.world());
            loc.addProperty("x", position.x());
            loc.addProperty("y", position.y());
            loc.addProperty("z", position.z());
        }
        data.add("location", loc);
        return data;
    }
}
