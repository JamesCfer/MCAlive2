package dev.celestia.mcalive2.senses;

import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.util.EnumSet;
import java.util.Set;

/**
 * Pure (server-free) spawn decision table for the three creature kinds the world
 * regime cares about. {@link SpawnGate} classifies each spawning entity into a
 * {@link Kind} and asks this class whether the configured {@link Policy} allows it.
 *
 * <ul>
 *   <li>VILLAGER_FAMILY + "banned": nothing gets through - not even CUSTOM or COMMAND.
 *       The cast is mannequin NPCs only; a stray villager would be an off-script extra.</li>
 *   <li>HOSTILE + "whitelist": only the deliberate placement paths the brain and ops
 *       use (CUSTOM, COMMAND, SPAWNER_EGG) are allowed.</li>
 *   <li>PEACEFUL + "new-chunks": animals arrive with fresh terrain (CHUNK_GEN) and
 *       multiply by husbandry (BREEDING, EGG, DISPENSE_EGG) or deliberate placement -
 *       but ambient repopulation (NATURAL etc) is off, so herds are finite.</li>
 *   <li>"vanilla" for any kind: that kind is never touched.</li>
 * </ul>
 */
public final class SpawnRules {

    /** Which of the three regime buckets an entity falls into. */
    public enum Kind { VILLAGER_FAMILY, HOSTILE, PEACEFUL }

    /** The three config modes, read fresh from config.yml per event by {@link SpawnGate}.
     *  @param hostile   "whitelist" or "vanilla"
     *  @param peaceful  "new-chunks" or "vanilla"
     *  @param villagers "banned" or "vanilla" */
    public record Policy(String hostile, String peaceful, String villagers) {}

    private static final Set<SpawnReason> HOSTILE_ALLOWED = EnumSet.of(
            SpawnReason.CUSTOM,
            SpawnReason.COMMAND,
            SpawnReason.SPAWNER_EGG);

    private static final Set<SpawnReason> PEACEFUL_ALLOWED = EnumSet.of(
            SpawnReason.CHUNK_GEN,
            SpawnReason.BREEDING,
            SpawnReason.CUSTOM,
            SpawnReason.COMMAND,
            SpawnReason.SPAWNER_EGG,
            SpawnReason.EGG,
            SpawnReason.DISPENSE_EGG);

    private SpawnRules() {}

    /** True if a spawn of the given kind, arriving via the given reason, should proceed. */
    public static boolean allow(Kind kind, SpawnReason reason, Policy policy) {
        return switch (kind) {
            // villager family is checked before hostility (zombie villagers are both), and
            // "banned" means banned: no reason gets through, CUSTOM and COMMAND included
            case VILLAGER_FAMILY -> !"banned".equalsIgnoreCase(policy.villagers());
            case HOSTILE -> !"whitelist".equalsIgnoreCase(policy.hostile())
                    || HOSTILE_ALLOWED.contains(reason);
            case PEACEFUL -> !"new-chunks".equalsIgnoreCase(policy.peaceful())
                    || PEACEFUL_ALLOWED.contains(reason);
        };
    }
}
