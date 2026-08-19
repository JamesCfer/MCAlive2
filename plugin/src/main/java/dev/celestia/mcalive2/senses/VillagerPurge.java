package dev.celestia.mcalive2.senses;

import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Sweeps pre-existing villagers out of the world as their chunks stream in. The
 * {@link SpawnGate} stops NEW villager-family spawns, but a world generated before the
 * ban (or before the plugin) still has villages full of extras standing around; this
 * removes every villager-family entity that is not one of ours (i.e. lacks the NPC id
 * PDC tag) whenever its chunk's entities load. Only active while
 * {@code spawn-control.villagers} is "banned" (read fresh, reload-aware).
 */
public class VillagerPurge implements Listener {

    private final MCAlive2Plugin plugin;
    private final NpcManager npcs;

    public VillagerPurge(MCAlive2Plugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!"banned".equalsIgnoreCase(plugin.getConfig().getString("spawn-control.villagers", "banned"))) return;
        int removed = 0;
        for (Entity e : event.getEntities()) {
            if (SpawnGate.classify(e) != SpawnRules.Kind.VILLAGER_FAMILY) continue;
            if (e.getPersistentDataContainer().has(npcs.key(), PersistentDataType.STRING)) continue;
            e.remove();
            removed++;
        }
        if (removed > 0) {
            plugin.getLogger().info("Purged " + removed + " non-NPC villager(s) from loading chunk "
                    + event.getChunk().getX() + "," + event.getChunk().getZ());
        }
    }
}
