package dev.celestia.mcalive2.npc;

import dev.celestia.mcalive2.npc.ChunkTicketLedger.ChunkKey;
import dev.celestia.mcalive2.npc.ChunkTicketLedger.Diff;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkTicketLedgerTest {

    private static ChunkKey key(int cx, int cz) {
        return new ChunkKey("world", cx, cz);
    }

    // ---- chunk math ----

    @Test
    void chunkOfFloorsBlockCoordinates() {
        assertEquals(key(0, 0), ChunkTicketLedger.chunkOf("world", 0, 15));
        assertEquals(key(1, 0), ChunkTicketLedger.chunkOf("world", 16, 3));
        // negative coords floor toward negative infinity, not toward zero
        assertEquals(key(-1, -1), ChunkTicketLedger.chunkOf("world", -1, -16));
        assertEquals(key(-2, -1), ChunkTicketLedger.chunkOf("world", -17, -5));
    }

    @Test
    void squareAtRadiusOneIsTheThreeByThreeAroundTheBlocksChunk() {
        Set<ChunkKey> square = ChunkTicketLedger.square("world", 40, 40, 1); // chunk (2,2)
        assertEquals(9, square.size());
        for (int cx = 1; cx <= 3; cx++) {
            for (int cz = 1; cz <= 3; cz++) {
                assertTrue(square.contains(key(cx, cz)), "missing " + cx + "," + cz);
            }
        }
    }

    @Test
    void squareAtRadiusZeroIsJustTheOwnChunk() {
        assertEquals(Set.of(key(-1, 0)), ChunkTicketLedger.square("world", -8, 12, 0));
    }

    @Test
    void squaresInDifferentWorldsNeverCollide() {
        Set<ChunkKey> overworld = ChunkTicketLedger.square("world", 0, 0, 1);
        Set<ChunkKey> nether = ChunkTicketLedger.square("world_nether", 0, 0, 1);
        for (ChunkKey k : nether) assertFalse(overworld.contains(k));
    }

    // ---- desired-set diffing ----

    @Test
    void firstDesiredSetAddsEverything() {
        ChunkTicketLedger ledger = new ChunkTicketLedger();
        Diff diff = ledger.setDesired("keeper", Set.of(key(0, 0), key(0, 1)));
        assertEquals(Set.of(key(0, 0), key(0, 1)), diff.toAdd());
        assertTrue(diff.toRemove().isEmpty());
    }

    @Test
    void movedDesiredSetAddsOnlyNewAndRemovesOnlyStale() {
        ChunkTicketLedger ledger = new ChunkTicketLedger();
        ledger.setDesired("keeper", Set.of(key(0, 0), key(0, 1)));
        Diff diff = ledger.setDesired("keeper", Set.of(key(0, 1), key(0, 2)));
        assertEquals(Set.of(key(0, 2)), diff.toAdd());
        assertEquals(Set.of(key(0, 0)), diff.toRemove());
    }

    @Test
    void unchangedDesiredSetIsANoOp() {
        ChunkTicketLedger ledger = new ChunkTicketLedger();
        ledger.setDesired("keeper", Set.of(key(3, 3)));
        Diff diff = ledger.setDesired("keeper", Set.of(key(3, 3)));
        assertTrue(diff.toAdd().isEmpty());
        assertTrue(diff.toRemove().isEmpty());
    }

    // ---- refcounting across owners (the whole reason this class exists) ----

    @Test
    void chunkWantedByTwoOwnersIsOnlyAddedOnceAndOnlyRemovedWhenTheLastLetsGo() {
        ChunkTicketLedger ledger = new ChunkTicketLedger();
        assertEquals(Set.of(key(5, 5)), ledger.setDesired("keeper", Set.of(key(5, 5))).toAdd());
        // second owner wanting the same chunk: no new Bukkit ticket needed
        Diff second = ledger.setDesired("behavior/p1", Set.of(key(5, 5)));
        assertTrue(second.toAdd().isEmpty());
        // first owner leaves: the other still wants it, so nothing is removed
        assertTrue(ledger.release("keeper").toRemove().isEmpty());
        // last owner leaves: NOW the ticket goes
        assertEquals(Set.of(key(5, 5)), ledger.release("behavior/p1").toRemove());
        assertEquals(0, ledger.trackedCount());
    }

    @Test
    void oneOwnerMovingAwayNeverDropsAChunkAnotherOwnerStillWants() {
        ChunkTicketLedger ledger = new ChunkTicketLedger();
        ledger.setDesired("keeper", Set.of(key(0, 0), key(1, 0)));
        ledger.setDesired("behavior/p1", Set.of(key(1, 0)));
        Diff diff = ledger.setDesired("keeper", Set.of(key(9, 9)));
        assertEquals(Set.of(key(0, 0)), diff.toRemove()); // (1,0) survives: behavior still there
        assertEquals(Set.of(key(9, 9)), diff.toAdd());
    }

    @Test
    void releasingAnUnknownOwnerIsANoOp() {
        ChunkTicketLedger ledger = new ChunkTicketLedger();
        ledger.setDesired("keeper", Set.of(key(0, 0)));
        Diff diff = ledger.release("nobody");
        assertTrue(diff.toAdd().isEmpty());
        assertTrue(diff.toRemove().isEmpty());
        assertEquals(1, ledger.trackedCount());
    }

    @Test
    void emptyDesiredSetReleasesTheOwnerCompletely() {
        ChunkTicketLedger ledger = new ChunkTicketLedger();
        ledger.setDesired("keeper", Set.of(key(0, 0), key(0, 1)));
        Diff diff = ledger.setDesired("keeper", Set.of());
        assertEquals(Set.of(key(0, 0), key(0, 1)), diff.toRemove());
        assertEquals(0, ledger.trackedCount());
    }

    @Test
    void releaseAllSweepsEveryOwnerForOnDisable() {
        ChunkTicketLedger ledger = new ChunkTicketLedger();
        ledger.setDesired("keeper", Set.of(key(0, 0), key(1, 1)));
        ledger.setDesired("behavior/p1", Set.of(key(1, 1), key(2, 2)));
        Diff diff = ledger.releaseAll();
        assertEquals(Set.of(key(0, 0), key(1, 1), key(2, 2)), diff.toRemove());
        assertEquals(0, ledger.trackedCount());
        // and the ledger is reusable afterwards
        assertEquals(Set.of(key(1, 1)), ledger.setDesired("keeper", Set.of(key(1, 1))).toAdd());
    }
}
