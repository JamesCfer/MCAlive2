package dev.celestia.mcalive2.npc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefenseMathTest {

    private static final double EPS = 1e-9;

    // ---- flee point ----

    @Test
    void fleesDirectlyAwayFromAttackerAlongX() {
        // attacker 4 blocks west of the npc: flee 20 blocks further east
        double[] p = DefenseMath.fleePoint(10, 64, 10, 6, 10, 20);
        assertEquals(30, p[0], EPS);
        assertEquals(64, p[1], EPS);
        assertEquals(10, p[2], EPS);
    }

    @Test
    void fleeVectorIsNormalizedOnDiagonals() {
        // attacker at (0,0), npc at (3,4): unit vector (0.6, 0.8), flee 20 -> npc + (12, 16)
        double[] p = DefenseMath.fleePoint(3, 70, 4, 0, 0, 20);
        assertEquals(3 + 12, p[0], EPS);
        assertEquals(70, p[1], EPS);
        assertEquals(4 + 16, p[2], EPS);
    }

    @Test
    void fleeDistanceIsAlwaysExactlyTheRequestedDistance() {
        double[] p = DefenseMath.fleePoint(-5, 12, 7, -8, 9, 20);
        double dist = Math.hypot(p[0] - (-5), p[2] - 7);
        assertEquals(20, dist, EPS);
    }

    @Test
    void attackerStandingExactlyOnNpcStillProducesAFleePoint() {
        // degenerate zero-length vector: an arbitrary direction, not NaN and not "stay put"
        double[] p = DefenseMath.fleePoint(5, 64, 5, 5, 5, 20);
        double dist = Math.hypot(p[0] - 5, p[2] - 5);
        assertEquals(20, dist, EPS);
        assertFalse(Double.isNaN(p[0]) || Double.isNaN(p[2]));
    }

    // ---- disengage predicates ----

    @Test
    void beyondRangeComparesSquaredDistanceAgainstThreshold() {
        assertFalse(DefenseMath.beyondRange(24 * 24, 24));      // exactly at range: still engaged
        assertTrue(DefenseMath.beyondRange(24.5 * 24.5, 24));   // just past: disengage
        assertFalse(DefenseMath.beyondRange(3 * 3, 24));
    }

    @Test
    void exchangeStaleFiresOnlyAfterTheTimeout() {
        long timeout = 12_000;
        assertFalse(DefenseMath.exchangeStale(20_000, 10_000, timeout)); // 10s quiet: keep fighting
        assertFalse(DefenseMath.exchangeStale(22_000, 10_000, timeout)); // exactly 12s: still in
        assertTrue(DefenseMath.exchangeStale(22_001, 10_000, timeout));  // past 12s: stand down
    }

    @Test
    void cooldownGatesRepeatedAttacks() {
        long cooldown = 1_000; // 20 ticks
        assertTrue(DefenseMath.cooldownReady(5_000, 0, cooldown));       // never attacked yet
        assertFalse(DefenseMath.cooldownReady(5_500, 5_000, cooldown));  // mid-cooldown
        assertTrue(DefenseMath.cooldownReady(6_000, 5_000, cooldown));   // exactly elapsed: swing
    }
}
