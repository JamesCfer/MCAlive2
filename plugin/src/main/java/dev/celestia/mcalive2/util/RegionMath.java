package dev.celestia.mcalive2.util;

/** Pure (Bukkit-free) helpers for region-copy math, so they're unit-testable without a server. */
public final class RegionMath {

    private RegionMath() {}

    /**
     * Picks the per-axis iteration direction for an overlap-safe region copy (memmove-style):
     * when the destination is offset to a higher coordinate on this axis (delta &gt; 0), blocks
     * must be copied starting from the high end and working down, so a source block is always
     * read before a later write could clobber it. For delta &lt;= 0 (destination at the same or a
     * lower coordinate), iterating low-to-high is safe. Returns +1 (ascending) or -1 (descending).
     */
    public static int axisStep(int delta) {
        return delta > 0 ? -1 : 1;
    }
}
