package dev.celestia.mcalive2.npc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JobInventoryMathTest {

    @Test
    void allSatisfiedReturnsEmpty() {
        Map<String, Integer> available = Map.of("IRON_INGOT", 10, "STICK", 5);
        List<JobInventoryMath.Requirement> required = List.of(
                new JobInventoryMath.Requirement("IRON_INGOT", 3),
                new JobInventoryMath.Requirement("STICK", 2));
        assertTrue(JobInventoryMath.missing(available, required).isEmpty());
    }

    @Test
    void exactlyEnoughIsSatisfied() {
        Map<String, Integer> available = Map.of("WHEAT", 3);
        List<JobInventoryMath.Requirement> required = List.of(new JobInventoryMath.Requirement("WHEAT", 3));
        assertTrue(JobInventoryMath.missing(available, required).isEmpty());
    }

    @Test
    void reportsShortfallForInsufficientMaterial() {
        Map<String, Integer> available = Map.of("IRON_INGOT", 1);
        List<JobInventoryMath.Requirement> required = List.of(new JobInventoryMath.Requirement("IRON_INGOT", 4));
        List<JobInventoryMath.Requirement> missing = JobInventoryMath.missing(available, required);
        assertEquals(1, missing.size());
        assertEquals("IRON_INGOT", missing.get(0).material());
        assertEquals(3, missing.get(0).count());
    }

    @Test
    void reportsFullCountForAbsentMaterial() {
        Map<String, Integer> available = Map.of();
        List<JobInventoryMath.Requirement> required = List.of(new JobInventoryMath.Requirement("COAL", 5));
        List<JobInventoryMath.Requirement> missing = JobInventoryMath.missing(available, required);
        assertEquals(1, missing.size());
        assertEquals(5, missing.get(0).count());
    }

    @Test
    void isAllOrNothingAcrossMultipleRequirements() {
        Map<String, Integer> available = Map.of("IRON_INGOT", 10, "STICK", 1);
        List<JobInventoryMath.Requirement> required = List.of(
                new JobInventoryMath.Requirement("IRON_INGOT", 3),
                new JobInventoryMath.Requirement("STICK", 2));
        List<JobInventoryMath.Requirement> missing = JobInventoryMath.missing(available, required);
        assertEquals(1, missing.size());
        assertEquals("STICK", missing.get(0).material());
        assertEquals(1, missing.get(0).count());
    }

    @Test
    void emptyRequirementsAreAlwaysSatisfied() {
        assertTrue(JobInventoryMath.missing(Map.of(), List.of()).isEmpty());
    }
}
