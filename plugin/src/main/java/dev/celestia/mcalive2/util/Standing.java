package dev.celestia.mcalive2.util;

import java.util.function.IntPredicate;

/**
 * Pure (Bukkit-free) search for the nearest y at which an entity could stand safely:
 * a passable feet block, a passable head block above it, and a solid (non-passable)
 * block underfoot. Scans outward from the requested y - closest first, ties preferring
 * the candidate below - which is what makes floating islands work: an island at y209
 * sitting over a crater at y90 resolves to the island surface near the requested y,
 * never to the world's absolute top (a naive getHighestBlockYAt) nor to the crater floor
 * far below the search radius.
 */
public final class Standing {

    private Standing() {}

    /**
     * @param requestedY the y the caller asked for
     * @param minY       lowest y the probe may be queried at (inclusive)
     * @param maxY       highest y the probe may be queried at (inclusive)
     * @param radius     how far above/below requestedY to search
     * @param passable   true if the block at a given y can be occupied (air, tall grass, etc)
     * @return the resolved feet-y, as close to requestedY as possible
     * @throws IllegalStateException if no standable y exists within radius of requestedY
     */
    public static int resolve(int requestedY, int minY, int maxY, int radius, IntPredicate passable) {
        if (standable(requestedY, passable)) return requestedY;
        for (int d = 1; d <= radius; d++) {
            int below = requestedY - d;
            if (below >= minY && standable(below, passable)) return below;
            int above = requestedY + d;
            if (above <= maxY && standable(above, passable)) return above;
        }
        throw new IllegalStateException(
                "no standable y within " + radius + " of requested y=" + requestedY);
    }

    private static boolean standable(int y, IntPredicate passable) {
        return passable.test(y) && passable.test(y + 1) && !passable.test(y - 1);
    }
}
