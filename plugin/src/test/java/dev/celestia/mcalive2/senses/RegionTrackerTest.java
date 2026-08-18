package dev.celestia.mcalive2.senses;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegionTrackerTest {

    private static JsonObject place(String id, String name, Double x1, Double y1, Double z1,
                                     Double x2, Double y2, Double z2) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        if (x1 != null) {
            JsonObject bounds = new JsonObject();
            bounds.addProperty("x1", x1);
            bounds.addProperty("y1", y1);
            bounds.addProperty("z1", z1);
            bounds.addProperty("x2", x2);
            bounds.addProperty("y2", y2);
            bounds.addProperty("z2", z2);
            o.add("bounds", bounds);
        }
        return o;
    }

    private record Transition(String type, String player, String regionId, String regionName) {}

    private static class RecordingListener implements RegionTracker.Listener {
        final List<Transition> transitions = new ArrayList<>();

        @Override
        public void onEnter(String player, String regionId, String regionName, RegionTracker.Position position) {
            transitions.add(new Transition("enter", player, regionId, regionName));
        }

        @Override
        public void onExit(String player, String regionId, String regionName, RegionTracker.Position position) {
            transitions.add(new Transition("exit", player, regionId, regionName));
        }
    }

    @Test
    void firesEnterWhenPlayerStepsInsideBoundedPlace() {
        RegionTracker tracker = new RegionTracker();
        RecordingListener listener = new RecordingListener();
        JsonObject mill = place("place.mill", "The Mill", 0d, 60d, 0d, 10d, 80d, 10d);

        Map<String, RegionTracker.Position> outside = Map.of("Alice",
                new RegionTracker.Position("world", 50, 64, 50));
        tracker.tick(outside, List.of(mill), "world", listener);
        assertTrue(listener.transitions.isEmpty());

        Map<String, RegionTracker.Position> inside = Map.of("Alice",
                new RegionTracker.Position("world", 5, 65, 5));
        tracker.tick(inside, List.of(mill), "world", listener);

        assertEquals(1, listener.transitions.size());
        assertEquals(new Transition("enter", "Alice", "place.mill", "The Mill"), listener.transitions.get(0));
    }

    @Test
    void firesExitWhenPlayerLeavesBoundedPlace() {
        RegionTracker tracker = new RegionTracker();
        RecordingListener listener = new RecordingListener();
        JsonObject mill = place("place.mill", "The Mill", 0d, 60d, 0d, 10d, 80d, 10d);

        tracker.tick(Map.of("Alice", new RegionTracker.Position("world", 5, 65, 5)),
                List.of(mill), "world", listener);
        listener.transitions.clear();

        tracker.tick(Map.of("Alice", new RegionTracker.Position("world", 50, 64, 50)),
                List.of(mill), "world", listener);

        assertEquals(1, listener.transitions.size());
        assertEquals(new Transition("exit", "Alice", "place.mill", "The Mill"), listener.transitions.get(0));
    }

    @Test
    void handlesOverlappingRegionsIndependently() {
        RegionTracker tracker = new RegionTracker();
        RecordingListener listener = new RecordingListener();
        JsonObject town = place("place.town", "Town Square", -50d, 0d, -50d, 50d, 255d, 50d);
        JsonObject well = place("place.well", "The Well", -2d, 60d, -2d, 2d, 66d, 2d);

        // both regions overlap at the origin; entering there should register both
        tracker.tick(Map.of("Alice", new RegionTracker.Position("world", 0, 63, 0)),
                List.of(town, well), "world", listener);

        assertEquals(2, listener.transitions.size());
        assertTrue(listener.transitions.stream().anyMatch(t -> t.regionId().equals("place.town") && t.type().equals("enter")));
        assertTrue(listener.transitions.stream().anyMatch(t -> t.regionId().equals("place.well") && t.type().equals("enter")));

        listener.transitions.clear();
        // stepping outside the well but still inside town should only exit the well
        tracker.tick(Map.of("Alice", new RegionTracker.Position("world", 20, 63, 20)),
                List.of(town, well), "world", listener);

        assertEquals(1, listener.transitions.size());
        assertEquals(new Transition("exit", "Alice", "place.well", "The Well"), listener.transitions.get(0));
    }

    @Test
    void ignoresPlacesWithoutBounds() {
        RegionTracker tracker = new RegionTracker();
        RecordingListener listener = new RecordingListener();
        JsonObject noBounds = place("place.forest", "Forest", null, null, null, null, null, null);

        tracker.tick(Map.of("Alice", new RegionTracker.Position("world", 0, 65, 0)),
                List.of(noBounds), "world", listener);

        assertTrue(listener.transitions.isEmpty());
    }

    @Test
    void firesExitWhenRegionDeletedFromLedgerWhilePlayerStillInside() {
        RegionTracker tracker = new RegionTracker();
        RecordingListener listener = new RecordingListener();
        JsonObject mill = place("place.mill", "The Mill", 0d, 60d, 0d, 10d, 80d, 10d);

        tracker.tick(Map.of("Alice", new RegionTracker.Position("world", 5, 65, 5)),
                List.of(mill), "world", listener);
        listener.transitions.clear();

        // the place record is deleted from the ledger, but the player hasn't moved
        tracker.tick(Map.of("Alice", new RegionTracker.Position("world", 5, 65, 5)),
                List.of(), "world", listener);

        assertEquals(1, listener.transitions.size());
        assertEquals(new Transition("exit", "Alice", "place.mill", "The Mill"), listener.transitions.get(0));
    }

    @Test
    void offlinePlayerStillExitsUsingLastKnownPosition() {
        RegionTracker tracker = new RegionTracker();
        RecordingListener listener = new RecordingListener();
        JsonObject mill = place("place.mill", "The Mill", 0d, 60d, 0d, 10d, 80d, 10d);

        tracker.tick(Map.of("Alice", new RegionTracker.Position("world", 5, 65, 5)),
                List.of(mill), "world", listener);
        listener.transitions.clear();

        // Alice is no longer in the positions map (logged off)
        tracker.tick(new HashMap<>(), List.of(mill), "world", listener);

        assertEquals(1, listener.transitions.size());
        assertEquals(new Transition("exit", "Alice", "place.mill", "The Mill"), listener.transitions.get(0));
    }
}
