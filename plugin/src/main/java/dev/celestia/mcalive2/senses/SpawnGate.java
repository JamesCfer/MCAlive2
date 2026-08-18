package dev.celestia.mcalive2.senses;

import dev.celestia.mcalive2.MCAlive2Plugin;
import org.bukkit.entity.Enemy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Monsters exist only as story: the brain (or an op) places every hostile mob
 * deliberately, via {@code spawn_entity}, a spawn egg, or a command. When
 * {@code suppress-hostile-spawns} is enabled, this gate cancels every other spawn path
 * for anything implementing {@link Enemy} - natural spawns, spawners, patrols, raids,
 * reinforcements, and nether portal spawns. CUSTOM/COMMAND/SPAWNER_EGG/BREEDING spawns
 * are left alone, since those are exactly how the brain and ops place monsters on purpose.
 */
public class SpawnGate implements Listener {

    private static final Set<CreatureSpawnEvent.SpawnReason> BLOCKED = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.RAID,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.NETHER_PORTAL);

    private final MCAlive2Plugin plugin;

    public SpawnGate(MCAlive2Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("suppress-hostile-spawns", true)) return;
        if (!(event.getEntity() instanceof Enemy)) return;
        if (BLOCKED.contains(event.getSpawnReason())) {
            event.setCancelled(true);
        }
    }
}
