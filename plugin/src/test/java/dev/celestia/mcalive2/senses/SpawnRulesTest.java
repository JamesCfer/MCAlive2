package dev.celestia.mcalive2.senses;

import dev.celestia.mcalive2.senses.SpawnRules.Kind;
import dev.celestia.mcalive2.senses.SpawnRules.Policy;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnRulesTest {

    /** The regime as shipped in config.yml. */
    private static final Policy DEFAULT = new Policy("whitelist", "new-chunks", "banned");
    private static final Policy ALL_VANILLA = new Policy("vanilla", "vanilla", "vanilla");

    /** Every reason the decision table cares about (a representative superset). */
    private static final Set<SpawnReason> REASONS = EnumSet.of(
            SpawnReason.NATURAL, SpawnReason.CHUNK_GEN, SpawnReason.BREEDING,
            SpawnReason.CUSTOM, SpawnReason.COMMAND, SpawnReason.SPAWNER_EGG,
            SpawnReason.EGG, SpawnReason.DISPENSE_EGG, SpawnReason.SPAWNER,
            SpawnReason.TRIAL_SPAWNER, SpawnReason.PATROL, SpawnReason.RAID,
            SpawnReason.REINFORCEMENTS, SpawnReason.NETHER_PORTAL, SpawnReason.JOCKEY,
            SpawnReason.DROWNED, SpawnReason.CURED, SpawnReason.INFECTION,
            SpawnReason.VILLAGE_DEFENSE, SpawnReason.VILLAGE_INVASION, SpawnReason.TRAP,
            SpawnReason.DEFAULT);

    // ---- villager family ----

    @Test
    void bannedVillagersDenyEveryReasonIncludingDeliberatePlacement() {
        for (SpawnReason reason : REASONS) {
            assertFalse(SpawnRules.allow(Kind.VILLAGER_FAMILY, reason, DEFAULT),
                    "banned villagers must deny " + reason);
        }
    }

    @Test
    void vanillaVillagersAllowEveryReason() {
        for (SpawnReason reason : REASONS) {
            assertTrue(SpawnRules.allow(Kind.VILLAGER_FAMILY, reason, ALL_VANILLA),
                    "vanilla villagers must allow " + reason);
        }
    }

    // ---- hostile ----

    @Test
    void hostileWhitelistAllowsOnlyDeliberatePlacement() {
        Set<SpawnReason> allowed = EnumSet.of(
                SpawnReason.CUSTOM, SpawnReason.COMMAND, SpawnReason.SPAWNER_EGG);
        for (SpawnReason reason : REASONS) {
            boolean expect = allowed.contains(reason);
            assertTrue(expect == SpawnRules.allow(Kind.HOSTILE, reason, DEFAULT),
                    "hostile whitelist: " + reason + " should be " + (expect ? "allowed" : "denied"));
        }
    }

    @Test
    void hostileVanillaAllowsEveryReason() {
        for (SpawnReason reason : REASONS) {
            assertTrue(SpawnRules.allow(Kind.HOSTILE, reason, ALL_VANILLA),
                    "vanilla hostiles must allow " + reason);
        }
    }

    // ---- peaceful ----

    @Test
    void peacefulNewChunksAllowsOnlyGenerationHusbandryAndDeliberatePlacement() {
        Set<SpawnReason> allowed = EnumSet.of(
                SpawnReason.CHUNK_GEN, SpawnReason.BREEDING, SpawnReason.CUSTOM,
                SpawnReason.COMMAND, SpawnReason.SPAWNER_EGG, SpawnReason.EGG,
                SpawnReason.DISPENSE_EGG);
        for (SpawnReason reason : REASONS) {
            boolean expect = allowed.contains(reason);
            assertTrue(expect == SpawnRules.allow(Kind.PEACEFUL, reason, DEFAULT),
                    "peaceful new-chunks: " + reason + " should be " + (expect ? "allowed" : "denied"));
        }
    }

    @Test
    void peacefulVanillaAllowsEveryReason() {
        for (SpawnReason reason : REASONS) {
            assertTrue(SpawnRules.allow(Kind.PEACEFUL, reason, ALL_VANILLA),
                    "vanilla peaceful must allow " + reason);
        }
    }

    // ---- precedence & mode independence ----

    @Test
    void villagerBanWinsEvenWhenHostileModeWouldAllow() {
        // a zombie villager placed via CUSTOM is fine under hostile whitelist, but the
        // caller classifies it VILLAGER_FAMILY first - and the ban must still deny it
        assertTrue(SpawnRules.allow(Kind.HOSTILE, SpawnReason.CUSTOM, DEFAULT));
        assertFalse(SpawnRules.allow(Kind.VILLAGER_FAMILY, SpawnReason.CUSTOM, DEFAULT));
    }

    @Test
    void modesAreIndependentPerKind() {
        Policy onlyVillagersBanned = new Policy("vanilla", "vanilla", "banned");
        assertTrue(SpawnRules.allow(Kind.HOSTILE, SpawnReason.NATURAL, onlyVillagersBanned));
        assertTrue(SpawnRules.allow(Kind.PEACEFUL, SpawnReason.NATURAL, onlyVillagersBanned));
        assertFalse(SpawnRules.allow(Kind.VILLAGER_FAMILY, SpawnReason.NATURAL, onlyVillagersBanned));
    }
}
