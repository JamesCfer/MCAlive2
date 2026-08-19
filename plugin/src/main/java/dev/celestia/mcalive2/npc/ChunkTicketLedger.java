package dev.celestia.mcalive2.npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Refcounted bookkeeping for plugin chunk tickets, shared by every subsystem that
 * keeps chunks loaded ({@link NpcChunkKeeper}, the behavior engine). Bukkit's
 * addPluginChunkTicket/removePluginChunkTicket are NOT refcounted - a single remove
 * drops the plugin's ticket for that chunk no matter how many times it was added - so
 * one subsystem releasing a chunk would silently unload it out from under another.
 * All ticket intent therefore funnels through this ledger: each chunk remembers WHICH
 * owner tokens want it, and only when the last owner lets go does the ledger report
 * the ticket as actually removable.
 *
 * <p>Pure (no Bukkit types - worlds are names, chunks are coordinates), so the diff
 * semantics are unit-testable without a server, in the style of {@link
 * dev.celestia.mcalive2.npc.behavior.BehaviorMath} / {@link JobInventoryMath}. The
 * Bukkit-facing side lives in {@link ChunkTickets}.
 */
public final class ChunkTicketLedger {

    /** One chunk in one world, by chunk coordinates. */
    public record ChunkKey(String world, int cx, int cz) {}

    /** Net changes one update produced: chunks whose Bukkit ticket must now be added/removed. */
    public record Diff(Set<ChunkKey> toAdd, Set<ChunkKey> toRemove) {}

    private final Map<ChunkKey, Set<String>> owners = new HashMap<>();

    /**
     * Declare the full set of chunks {@code owner} currently wants (replacing whatever
     * it wanted before - recompute-and-diff, so a missed pass self-heals). Returns the
     * net ticket changes: chunks nobody wanted before, and chunks this was the last
     * owner of.
     */
    public Diff setDesired(String owner, Set<ChunkKey> desired) {
        Set<ChunkKey> toAdd = new LinkedHashSet<>();
        Set<ChunkKey> toRemove = new LinkedHashSet<>();
        for (Map.Entry<ChunkKey, Set<String>> e : new ArrayList<>(owners.entrySet())) {
            if (!desired.contains(e.getKey()) && e.getValue().remove(owner) && e.getValue().isEmpty()) {
                owners.remove(e.getKey());
                toRemove.add(e.getKey());
            }
        }
        for (ChunkKey key : desired) {
            Set<String> holding = owners.computeIfAbsent(key, k -> new HashSet<>());
            if (holding.isEmpty()) toAdd.add(key);
            holding.add(owner);
        }
        return new Diff(toAdd, toRemove);
    }

    /** Drop every chunk {@code owner} wanted. Unknown owners are a no-op. */
    public Diff release(String owner) {
        return setDesired(owner, Set.of());
    }

    /** Drop everything - the onDisable sweep. Every tracked chunk comes back as removable. */
    public Diff releaseAll() {
        Set<ChunkKey> toRemove = new LinkedHashSet<>(owners.keySet());
        owners.clear();
        return new Diff(Set.of(), toRemove);
    }

    /** Number of chunks currently wanted by at least one owner. */
    public int trackedCount() {
        return owners.size();
    }

    // ---- chunk math ----

    /** The chunk containing the given block position. */
    public static ChunkKey chunkOf(String world, int blockX, int blockZ) {
        return new ChunkKey(world, blockX >> 4, blockZ >> 4);
    }

    /**
     * The square of chunks within {@code radius} chunks of the block position's chunk:
     * radius 0 = just its own chunk, radius 1 = the 3x3 square around it.
     */
    public static Set<ChunkKey> square(String world, int blockX, int blockZ, int radius) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        Set<ChunkKey> keys = new LinkedHashSet<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                keys.add(new ChunkKey(world, x, z));
            }
        }
        return keys;
    }
}
