package dev.celestia.mcalive2.npc.behavior;

import dev.celestia.mcalive2.ledger.BlueprintParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorMathTest {

    // ---- nearest selection ----

    @Test
    void nearestPicksTheClosestCandidate() {
        List<int[]> candidates = List.of(new int[]{10, 64, 0}, new int[]{2, 64, 0}, new int[]{50, 64, 0});
        assertEquals(1, BehaviorMath.nearest(candidates, 0, 64, 0, Set.of()));
    }

    @Test
    void nearestSkipsExcludedIndices() {
        List<int[]> candidates = List.of(new int[]{2, 64, 0}, new int[]{10, 64, 0});
        assertEquals(1, BehaviorMath.nearest(candidates, 0, 64, 0, Set.of(0)));
    }

    @Test
    void nearestReturnsMinusOneWhenNothingQualifies() {
        assertEquals(-1, BehaviorMath.nearest(List.of(), 0, 0, 0, Set.of()));
        assertEquals(-1, BehaviorMath.nearest(List.of(new int[]{1, 1, 1}), 0, 0, 0, Set.of(0)));
    }

    // ---- carrying arithmetic ----

    @Test
    void addAccumulatesAndHasChecksThresholds() {
        Map<String, Integer> carrying = new HashMap<>();
        BehaviorMath.add(carrying, "OAK_LOG", 3);
        BehaviorMath.add(carrying, "OAK_LOG", 2);
        assertEquals(5, carrying.get("OAK_LOG"));
        assertTrue(BehaviorMath.has(carrying, "OAK_LOG", 5));
        assertFalse(BehaviorMath.has(carrying, "OAK_LOG", 6));
        assertFalse(BehaviorMath.has(carrying, "STONE", 1));
    }

    @Test
    void consumeIsAllOrNothing() {
        Map<String, Integer> carrying = new HashMap<>();
        BehaviorMath.add(carrying, "OAK_LOG", 4);
        assertFalse(BehaviorMath.consume(carrying, "OAK_LOG", 5));
        assertEquals(4, carrying.get("OAK_LOG"));
        assertTrue(BehaviorMath.consume(carrying, "OAK_LOG", 3));
        assertEquals(1, carrying.get("OAK_LOG"));
        assertTrue(BehaviorMath.consume(carrying, "OAK_LOG", 1));
        assertFalse(carrying.containsKey("OAK_LOG"));
    }

    // ---- cursor advancement + done detection ----

    @Test
    void advanceStepsForwardAndLandsOnDoneSentinel() {
        assertEquals(1, BehaviorMath.advance(0, 3, false));
        assertEquals(2, BehaviorMath.advance(1, 3, false));
        assertEquals(3, BehaviorMath.advance(2, 3, false));
        assertTrue(BehaviorMath.isDone(3, 3));
        assertFalse(BehaviorMath.isDone(2, 3));
    }

    @Test
    void advanceWrapsToZeroWhenLooping() {
        assertEquals(1, BehaviorMath.advance(0, 3, true));
        assertEquals(0, BehaviorMath.advance(2, 3, true));
    }

    @Test
    void allDoneOnlyWhenEveryCursorFinished() {
        assertTrue(BehaviorMath.allDone(List.of(3, 3), 3));
        assertFalse(BehaviorMath.allDone(List.of(3, 2), 3));
        assertFalse(BehaviorMath.allDone(List.of(), 3));
    }

    // ---- blueprint claims ----

    private List<BlueprintParser.BlockEntry> houseBlocks() {
        return List.of(
                new BlueprintParser.BlockEntry(0, 0, 0, "OAK_LOG", null),
                new BlueprintParser.BlockEntry(5, 0, 0, "OAK_LOG", null),
                new BlueprintParser.BlockEntry(0, 0, 5, "OAK_PLANKS", null));
    }

    @Test
    void nextBlockForPicksNearestUnplacedUnclaimedCarriedMaterial() {
        Map<String, Integer> carrying = Map.of("OAK_LOG", 2);
        // NPC standing at the origin: index 0 (dist 0) beats index 1 (dist 5); index 2 not carried
        assertEquals(0, BehaviorMath.nextBlockFor(houseBlocks(), Set.of(), Set.of(), carrying,
                0.5, 0.5, 0.5, 0, 0, 0));
        // index 0 placed, index 1 claimed by a crewmate: nothing left the NPC can place
        assertEquals(-1, BehaviorMath.nextBlockFor(houseBlocks(), Set.of(0), Set.of(1), carrying,
                0.5, 0.5, 0.5, 0, 0, 0));
        // null carrying = any material qualifies
        assertEquals(2, BehaviorMath.nextBlockFor(houseBlocks(), Set.of(0), Set.of(1), null,
                0.5, 0.5, 0.5, 0, 0, 0));
    }

    @Test
    void nextBlockForResolvesAgainstTheOrigin() {
        Map<String, Integer> carrying = Map.of("OAK_LOG", 1);
        // origin at x=100: NPC at x=105.5 stands on top of index 1 (100+5)
        assertEquals(1, BehaviorMath.nextBlockFor(houseBlocks(), Set.of(), Set.of(), carrying,
                105.5, 0.5, 0.5, 100, 0, 0));
    }

    @Test
    void neededMaterialsCountsOnlyUnplacedUnclaimedBlocks() {
        Map<String, Integer> needed = BehaviorMath.neededMaterials(houseBlocks(), Set.of(0), Set.of(2));
        assertEquals(Map.of("OAK_LOG", 1), needed);
        assertTrue(BehaviorMath.neededMaterials(houseBlocks(), Set.of(0, 1, 2), Set.of()).isEmpty());
    }

    @Test
    void withdrawPlanRespectsTripBudgetAndChestStock() {
        Map<String, Integer> needed = Map.of("OAK_LOG", 10);
        assertEquals(Map.of("OAK_LOG", 8), BehaviorMath.withdrawPlan(needed, Map.of("OAK_LOG", 64), 8));
        assertEquals(Map.of("OAK_LOG", 3), BehaviorMath.withdrawPlan(needed, Map.of("OAK_LOG", 3), 8));
        assertTrue(BehaviorMath.withdrawPlan(needed, Map.of("STONE", 64), 8).isEmpty());
        assertTrue(BehaviorMath.withdrawPlan(Map.of(), Map.of("OAK_LOG", 64), 8).isEmpty());
    }

    @Test
    void withdrawPlanSpreadsBudgetAcrossMaterials() {
        Map<String, Integer> needed = new java.util.LinkedHashMap<>();
        needed.put("OAK_LOG", 5);
        needed.put("OAK_PLANKS", 5);
        Map<String, Integer> plan = BehaviorMath.withdrawPlan(needed,
                Map.of("OAK_LOG", 64, "OAK_PLANKS", 64), 8);
        assertEquals(Map.of("OAK_LOG", 5, "OAK_PLANKS", 3), plan);
    }
}
