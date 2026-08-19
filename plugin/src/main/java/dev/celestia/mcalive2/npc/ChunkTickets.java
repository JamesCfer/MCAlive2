package dev.celestia.mcalive2.npc;

import dev.celestia.mcalive2.npc.ChunkTicketLedger.ChunkKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * The Bukkit-facing side of {@link ChunkTicketLedger}: the single place plugin chunk
 * tickets are actually added and removed. Every subsystem that wants chunks loaded
 * declares its desired set here under its own owner token; the shared ledger makes
 * sure a chunk's ticket is only dropped once NO owner wants it anymore (Bukkit's
 * ticket API is not refcounted - see the ledger's class doc).
 */
public class ChunkTickets {

    private final Plugin plugin;
    private final ChunkTicketLedger ledger = new ChunkTicketLedger();

    public ChunkTickets(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Replace {@code owner}'s wanted set with {@code desired}, applying the net ticket changes. */
    public void setDesired(String owner, Set<ChunkKey> desired) {
        apply(ledger.setDesired(owner, desired));
    }

    /** Release every chunk {@code owner} wanted. */
    public void release(String owner) {
        apply(ledger.release(owner));
    }

    /** Release every ticket every owner holds - the onDisable sweep. */
    public void releaseAll() {
        apply(ledger.releaseAll());
    }

    private void apply(ChunkTicketLedger.Diff diff) {
        for (ChunkKey key : diff.toAdd()) {
            World world = Bukkit.getWorld(key.world());
            if (world != null) world.addPluginChunkTicket(key.cx(), key.cz(), plugin);
        }
        for (ChunkKey key : diff.toRemove()) {
            World world = Bukkit.getWorld(key.world());
            if (world != null) world.removePluginChunkTicket(key.cx(), key.cz(), plugin);
        }
    }
}
