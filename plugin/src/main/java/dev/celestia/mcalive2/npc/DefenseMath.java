package dev.celestia.mcalive2.npc;

/**
 * Pure (Bukkit-free) geometry and timing for NPC self-defense - the flee vector and
 * the disengage/cooldown predicates {@link DefenseManager} evaluates every loop tick.
 * Keeping these here means the whole decision surface is unit-testable without a server.
 */
public final class DefenseMath {

    /** How far (blocks) a fleeing NPC runs per flee order, directly away from the attacker. */
    public static final double FLEE_DISTANCE = 20;

    private DefenseMath() {}

    /**
     * The point {@code distance} blocks from the NPC along the attacker-&gt;npc unit
     * vector (XZ plane; y is carried through unchanged - the caller snaps to the surface
     * afterwards). If attacker and NPC share a column, an arbitrary +x direction is used
     * rather than dividing by zero.
     *
     * @return {@code {x, y, z}} of the flee destination
     */
    public static double[] fleePoint(double npcX, double npcY, double npcZ,
                                      double attackerX, double attackerZ, double distance) {
        double dx = npcX - attackerX;
        double dz = npcZ - attackerZ;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) { // attacker standing exactly on us: pick a direction, any direction
            dx = 1;
            dz = 0;
            len = 1;
        }
        return new double[] { npcX + dx / len * distance, npcY, npcZ + dz / len * distance };
    }

    /** True if the attacker has moved beyond {@code range} blocks (squared-distance compare). */
    public static boolean beyondRange(double distanceSquared, double range) {
        return distanceSquared > range * range;
    }

    /** True if no damage has been exchanged (either direction) for longer than {@code timeoutMs}. */
    public static boolean exchangeStale(long nowMs, long lastExchangeMs, long timeoutMs) {
        return nowMs - lastExchangeMs > timeoutMs;
    }

    /** True if enough time has passed since the last swing to attack again. */
    public static boolean cooldownReady(long nowMs, long lastAttackMs, long cooldownMs) {
        return nowMs - lastAttackMs >= cooldownMs;
    }
}
