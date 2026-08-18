package dev.celestia.mcalive2.senses;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Box-test + diff logic for named ledger {@code places} that carry a {@code bounds}
 * box. Computes which bounded places each player is currently inside (y-inclusive box
 * test; a player can be in several at once), diffs against the previous tick's set per
 * player, and reports enter/exit transitions via {@link Listener}.
 *
 * Bukkit-free and stateless w.r.t. the ledger: every {@link #tick} call is handed the
 * ledger's current {@code places} records fresh (read through {@code Ledger#all("places")}
 * by the caller), so a place edited or deleted in the ledger takes effect on the very
 * next tick — nothing here caches a stale copy of a region's bounds.
 */
public final class RegionTracker {

    /** A player's current position. Omit the player from the tick's map entirely if offline. */
    public record Position(String world, double x, double y, double z) {}

    public interface Listener {
        void onEnter(String player, String regionId, String regionName, Position position);
        void onExit(String player, String regionId, String regionName, Position position);
    }

    private record Membership(String regionId, String regionName) {}

    private record Region(String id, String name, String world,
                           double x1, double y1, double z1, double x2, double y2, double z2) {
        boolean contains(Position p) {
            if (!world.equals(p.world())) return false;
            return p.x() >= Math.min(x1, x2) && p.x() <= Math.max(x1, x2)
                    && p.y() >= Math.min(y1, y2) && p.y() <= Math.max(y1, y2)
                    && p.z() >= Math.min(z1, z2) && p.z() <= Math.max(z1, z2);
        }
    }

    private final Map<String, List<Membership>> membershipsByPlayer = new HashMap<>();
    private final Map<String, Position> lastKnownPosition = new HashMap<>();

    /**
     * @param playerPositions current position of every online player, by name; a player
     *                        simply absent from this map is treated as offline/gone
     * @param places          the raw {@code places} ledger records for this tick; records
     *                        without a complete {@code bounds} box are ignored entirely
     * @param defaultWorld    world name to assume for a place that doesn't itself carry one
     *                        (mirrors the rest of the plugin defaulting to the first world)
     * @param listener        receives one callback per enter/exit transition this tick
     */
    public void tick(Map<String, Position> playerPositions, List<JsonObject> places,
                      String defaultWorld, Listener listener) {
        List<Region> regions = parseRegions(places, defaultWorld);

        Set<String> players = new LinkedHashSet<>(membershipsByPlayer.keySet());
        players.addAll(playerPositions.keySet());

        for (String player : players) {
            Position pos = playerPositions.get(player);
            List<Membership> previous = membershipsByPlayer.getOrDefault(player, List.of());
            List<Membership> current = new ArrayList<>();
            if (pos != null) {
                for (Region r : regions) {
                    if (r.contains(pos)) current.add(new Membership(r.id(), r.name()));
                }
            }

            // a player who went offline this tick still gets exit events at their last known spot
            Position exitPosition = pos != null ? pos : lastKnownPosition.get(player);
            for (Membership m : previous) {
                if (current.stream().noneMatch(c -> c.regionId().equals(m.regionId()))) {
                    listener.onExit(player, m.regionId(), m.regionName(), exitPosition);
                }
            }
            for (Membership m : current) {
                if (previous.stream().noneMatch(p -> p.regionId().equals(m.regionId()))) {
                    listener.onEnter(player, m.regionId(), m.regionName(), pos);
                }
            }

            if (pos != null) {
                lastKnownPosition.put(player, pos);
            } else {
                lastKnownPosition.remove(player);
            }
            if (current.isEmpty()) {
                membershipsByPlayer.remove(player);
            } else {
                membershipsByPlayer.put(player, current);
            }
        }
    }

    private static List<Region> parseRegions(List<JsonObject> places, String defaultWorld) {
        List<Region> regions = new ArrayList<>();
        if (places == null) return regions;
        for (JsonObject place : places) {
            if (place == null || !place.has("id") || place.get("id").isJsonNull()) continue;
            if (!place.has("bounds") || !place.get("bounds").isJsonObject()) continue;
            JsonObject b = place.getAsJsonObject("bounds");
            if (!hasAll(b, "x1", "y1", "z1", "x2", "y2", "z2")) continue;

            String id = place.get("id").getAsString();
            String name = place.has("name") && !place.get("name").isJsonNull()
                    ? place.get("name").getAsString() : id;
            String world = worldOf(place, b, defaultWorld);
            regions.add(new Region(id, name, world,
                    b.get("x1").getAsDouble(), b.get("y1").getAsDouble(), b.get("z1").getAsDouble(),
                    b.get("x2").getAsDouble(), b.get("y2").getAsDouble(), b.get("z2").getAsDouble()));
        }
        return regions;
    }

    private static boolean hasAll(JsonObject o, String... keys) {
        for (String k : keys) {
            if (!o.has(k) || o.get(k).isJsonNull()) return false;
        }
        return true;
    }

    private static String worldOf(JsonObject place, JsonObject bounds, String defaultWorld) {
        if (bounds.has("world") && !bounds.get("world").isJsonNull()) return bounds.get("world").getAsString();
        if (place.has("world") && !place.get("world").isJsonNull()) return place.get("world").getAsString();
        if (place.has("origin") && place.get("origin").isJsonObject()) {
            JsonObject origin = place.getAsJsonObject("origin");
            if (origin.has("world") && !origin.get("world").isJsonNull()) return origin.get("world").getAsString();
        }
        return defaultWorld;
    }
}
