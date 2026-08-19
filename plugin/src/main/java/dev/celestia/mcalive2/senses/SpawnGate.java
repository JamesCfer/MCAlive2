package dev.celestia.mcalive2.senses;

import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;

/**
 * The world's spawn regime: creatures exist only as story. Every spawning entity is
 * classified into one of the three {@link SpawnRules.Kind} buckets - villager family
 * first (zombie villagers are both villager-family AND {@link Enemy}, and the villager
 * rule wins), then hostiles, then any other {@link Mob} as peaceful - and the decision
 * is delegated to the pure {@link SpawnRules} table against the modes in config.yml
 * (read fresh per event, so {@code /mcalive2 reload} takes effect immediately).
 *
 * <p>Entities the plugin spawns itself (NPC bodies via {@link NpcManager#spawn}) are
 * never gated: the NPC id PDC tag is only applied AFTER {@code spawnEntity} returns,
 * while this event fires synchronously inside it, so a tag check here would always
 * miss. Instead {@link NpcManager#isSpawningNpc()} exposes a latch held for the
 * duration of the (main-thread, synchronous) spawn call. Mannequin NPCs additionally
 * fall outside all three kinds (not a Mob), so they'd pass regardless.
 *
 * <p>Villager "banned" mode also blocks the two transform paths that mint
 * villager-family entities out of existing ones: curing a zombie villager (CURED) and
 * zombifying a villager (INFECTION).
 */
public class SpawnGate implements Listener {

    private final MCAlive2Plugin plugin;

    public SpawnGate(MCAlive2Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        NpcManager npcs = plugin.npcManager();
        if (npcs != null && npcs.isSpawningNpc()) return; // our own NPC body - never gate
        SpawnRules.Kind kind = classify(event.getEntity());
        if (kind == null) return;
        if (!SpawnRules.allow(kind, event.getSpawnReason(), policy())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityTransform(EntityTransformEvent event) {
        EntityTransformEvent.TransformReason reason = event.getTransformReason();
        if (reason != EntityTransformEvent.TransformReason.CURED
                && reason != EntityTransformEvent.TransformReason.INFECTION) return;
        if (!"banned".equalsIgnoreCase(policy().villagers())) return;
        for (Entity result : event.getTransformedEntities()) {
            if (classify(result) == SpawnRules.Kind.VILLAGER_FAMILY) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Which regime bucket an entity belongs to, or null if it is not gated at all
     *  (players, mannequins, armor stands, items, ...). */
    static SpawnRules.Kind classify(Entity entity) {
        // villager family FIRST: a zombie villager is also an Enemy, but the villager rule wins
        if (entity instanceof AbstractVillager || entity instanceof ZombieVillager) {
            return SpawnRules.Kind.VILLAGER_FAMILY;
        }
        if (entity instanceof Enemy) return SpawnRules.Kind.HOSTILE;
        if (entity instanceof Mob) return SpawnRules.Kind.PEACEFUL;
        return null;
    }

    /** The current spawn-control modes, read fresh so config reloads apply immediately.
     *  The pre-0.6 {@code suppress-hostile-spawns} flag is honored as a deprecated alias:
     *  if the user's config has no explicit {@code spawn-control.hostile} but did set
     *  {@code suppress-hostile-spawns: false}, hostiles fall back to vanilla. */
    private SpawnRules.Policy policy() {
        FileConfiguration cfg = plugin.getConfig();
        String hostile = cfg.getString("spawn-control.hostile", "whitelist");
        if (!cfg.isSet("spawn-control.hostile") && !cfg.getBoolean("suppress-hostile-spawns", true)) {
            hostile = "vanilla";
        }
        return new SpawnRules.Policy(
                hostile,
                cfg.getString("spawn-control.peaceful", "new-chunks"),
                cfg.getString("spawn-control.villagers", "banned"));
    }
}
