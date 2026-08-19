package dev.celestia.mcalive2.npc;

import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.npc.ChunkTicketLedger.ChunkKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Keeps every living NPC's surroundings loaded: each alive NPC holds plugin chunk
 * tickets (via the shared {@link ChunkTickets} ledger) on its own chunk plus
 * {@code npc-chunk-loading.radius} chunks on every side (radius 1 = a 3x3 square), so
 * NPCs keep walking their routines, defending themselves, and working with no player
 * anywhere near.
 *
 * <p>Each pass recomputes the FULL desired set across all NPCs and diffs it against
 * what is held (self-healing - a missed pass or a moved/removed/killed NPC corrects
 * itself next pass). An NPC whose entity is unresolved uses its last-known position
 * (then home, then work), so an NPC stranded in an unloaded chunk gets its chunks
 * brought back and stays simulated rather than frozen. Dead NPCs hold nothing.
 */
public class NpcChunkKeeper {

    /** Owner token in the shared ticket ledger. */
    private static final String OWNER = "npc-keeper";

    private final MCAlive2Plugin plugin;
    private final NpcManager npcs;
    private final ChunkTickets tickets;

    public NpcChunkKeeper(MCAlive2Plugin plugin, NpcManager npcs, ChunkTickets tickets) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.tickets = tickets;
    }

    /** Called every 100 ticks (same cadence as {@link NpcManager#tickRoutines}). */
    public void tick() {
        if (!plugin.getConfig().getBoolean("npc-chunk-loading.enabled", true)) {
            tickets.release(OWNER);
            return;
        }
        int radius = Math.max(0, plugin.getConfig().getInt("npc-chunk-loading.radius", 1));
        Set<ChunkKey> desired = new LinkedHashSet<>();
        for (NpcData data : npcs.all()) {
            if (data.dead) continue;
            Location loc = positionOf(data);
            if (loc == null || loc.getWorld() == null) continue;
            desired.addAll(ChunkTicketLedger.square(loc.getWorld().getName(),
                    loc.getBlockX(), loc.getBlockZ(), radius));
        }
        tickets.setDesired(OWNER, desired);
    }

    /** The live entity's position when resolvable, else the stored last-known position
     *  (falling back to home, then work) - deliberately WITHOUT forcing a chunk load or
     *  respawn: the ticket itself is what brings the chunk back. */
    private Location positionOf(NpcData data) {
        if (data.entityUuid != null) {
            Entity e = Bukkit.getEntity(data.entityUuid);
            if (e != null && e.isValid()) return e.getLocation();
        }
        return data.lastLocation != null ? data.lastLocation
                : data.home != null ? data.home : data.work;
    }
}
