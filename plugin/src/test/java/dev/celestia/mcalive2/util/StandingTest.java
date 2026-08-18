package dev.celestia.mcalive2.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandingTest {

    private static final int MIN_Y = -64;
    private static final int MAX_Y = 319;

    /** Solid everywhere except the given set of "passable" (air-like) y's. */
    private static IntPredicate solidExcept(Set<Integer> passableYs) {
        return passableYs::contains;
    }

    @Test
    void prefersRequestedYWhenAlreadyStandable() {
        // passable at 10 and 11 (feet+head), solid at 9 (ground)
        IntPredicate passable = solidExcept(Set.of(10, 11));
        assertEquals(10, Standing.resolve(10, MIN_Y, MAX_Y, 12, passable));
    }

    @Test
    void resolvesNearestBelowWhenRequestedYIsNotStandable() {
        // requested y=10 is solid (not passable); only y=7 is standable (7,8 passable, 6 solid)
        IntPredicate passable = solidExcept(Set.of(7, 8));
        assertEquals(7, Standing.resolve(10, MIN_Y, MAX_Y, 12, passable));
    }

    @Test
    void resolvesNearestAboveWhenNothingBelowIsStandable() {
        // requested y=10; only standable spot is y=13 (13,14 passable, 12 solid)
        IntPredicate passable = solidExcept(Set.of(13, 14));
        assertEquals(13, Standing.resolve(10, MIN_Y, MAX_Y, 12, passable));
    }

    @Test
    void tiesPreferBelow() {
        // both y=8 (2 below requested 10) and y=12 (2 above requested 10) are standable
        IntPredicate passable = solidExcept(Set.of(8, 9, 12, 13));
        assertEquals(8, Standing.resolve(10, MIN_Y, MAX_Y, 12, passable));
    }

    @Test
    void islandOverVoidResolvesToIslandNotCraterBelow() {
        // A floating island: solid band at y=200..210 (so a passable pocket sits at 211+),
        // with the entity standing on top of the island at y=211 (211,212 passable, 210 solid).
        // Below the island is nothing but void (always "passable"/non-solid) all the way
        // down to a distant crater floor around y=90 - far outside any reasonable scan
        // radius, and critically NOT what should be picked even if it were in range.
        Set<Integer> passableYs = new HashSet<>();
        for (int y = MIN_Y; y < 200; y++) passableYs.add(y); // void below the island
        passableYs.add(211);
        passableYs.add(212);
        // y 200..210 remain solid (island mass); y=90 crater floor is irrelevant since it's
        // just more "void" above it in this simplified model - the key assertion is that
        // requesting near the island top lands ON the island, not drifting into the void.
        IntPredicate passable = solidExcept(passableYs);
        assertEquals(211, Standing.resolve(209, MIN_Y, MAX_Y, 12, passable));
    }

    @Test
    void noCandidateThrows() {
        IntPredicate neverPassable = y -> false;
        assertThrows(IllegalStateException.class,
                () -> Standing.resolve(50, MIN_Y, MAX_Y, 12, neverPassable));
    }
}
