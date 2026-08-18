package dev.celestia.mcalive2.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegionMathTest {

    @Test
    void positiveDeltaIteratesDescending() {
        assertEquals(-1, RegionMath.axisStep(1));
        assertEquals(-1, RegionMath.axisStep(64));
    }

    @Test
    void nonPositiveDeltaIteratesAscending() {
        assertEquals(1, RegionMath.axisStep(0));
        assertEquals(1, RegionMath.axisStep(-1));
        assertEquals(1, RegionMath.axisStep(-64));
    }
}
