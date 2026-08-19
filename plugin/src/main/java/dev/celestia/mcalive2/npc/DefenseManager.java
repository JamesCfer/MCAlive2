package dev.celestia.mcalive2.npc;

import dev.celestia.mcalive2.MCAlive2Plugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes NPCs answer being attacked instead of standing there taking it. Per the NPC's
 * {@code defense} mode ("fight" | "flee" | "none", see {@link NpcData#defense}) an
 * engagement loop runs every 10 ticks:
 *
 * <ul>
 *   <li>fight: close on the attacker via {@link NpcManager#walkTo} (mob pathfinding or
 *       the mannequin step-walker), and swing for {@code npc-defense.damage} (or the
 *       NPC's own {@link NpcData#attackDamage}) whenever within melee range and off
 *       cooldown;</li>
 *   <li>flee: run {@link DefenseMath#FLEE_DISTANCE} blocks straight away from the
 *       attacker, re-aiming every loop.</li>
 * </ul>
 *
 * The loop refreshes {@code manualOverrideUntilMs} each tick (the same stand-down
 * mechanism JobManager uses) so the daily routine never fights the fight. Engagements
 * end when the attacker dies, vanishes, changes world, gets far enough away, or the
 * exchange goes quiet for {@code disengage-seconds}. One engagement per NPC: a new
 * attacker mid-fight retargets only if it is closer than the current one. Like jobs,
 * engagements are pure in-memory state and do not survive a restart.
 */
public class DefenseManager {

    private static final long LOOP_PERIOD_TICKS = 10L;
    /** Fleeing gives up at a longer range than fighting - keep running until truly clear. */
    private static final double FLEE_DISENGAGE_DISTANCE = 32;
    /** manualOverrideUntilMs is refreshed by this much every loop, comfortably longer than one period. */
    private static final long OVERRIDE_BUFFER_MS = 90_000;

    private final MCAlive2Plugin plugin;
    private final NpcManager npcs;
    private final Map<String, Engagement> engagements = new ConcurrentHashMap<>();

    public DefenseManager(MCAlive2Plugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
        npcs.onRemoval(this::disengage);
    }

    private static final class Engagement {
        LivingEntity attacker;
        boolean flee;
        BukkitTask task;
        long lastExchangeMs = System.currentTimeMillis();
        long lastAttackMs = 0;
    }

    /** True if this NPC is currently fighting or fleeing (JobManager pauses its jobs on this). */
    public boolean isEngaged(String npcId) {
        return engagements.containsKey(npcId);
    }

    /**
     * React to {@code npc} being hurt by {@code attacker}. No-ops if defense is disabled
     * in config, the NPC's mode is "none", or the NPC is dead. Called on every damage
     * event, so an ongoing engagement also gets its damage-exchange clock refreshed here.
     */
    public void engage(NpcData npc, LivingEntity attacker) {
        if (!plugin.getConfig().getBoolean("npc-defense.enabled", true)) return;
        if (npc == null || npc.dead || attacker == null || !attacker.isValid()) return;
        String mode = npc.defense == null ? "fight" : npc.defense;
        if ("none".equalsIgnoreCase(mode)) return;

        Engagement existing = engagements.get(npc.id);
        if (existing != null) {
            existing.lastExchangeMs = System.currentTimeMillis();
            if (existing.attacker != attacker) {
                // a second attacker: retarget only if the newcomer is closer
                Entity entity = npcs.resolveEntity(npc);
                if (entity != null && attacker.getWorld() == entity.getWorld()
                        && (existing.attacker == null || !existing.attacker.isValid()
                            || existing.attacker.getWorld() != entity.getWorld()
                            || attacker.getLocation().distanceSquared(entity.getLocation())
                               < existing.attacker.getLocation().distanceSquared(entity.getLocation()))) {
                    existing.attacker = attacker;
                }
            }
            return;
        }

        Engagement e = new Engagement();
        e.attacker = attacker;
        e.flee = "flee".equalsIgnoreCase(mode);
        engagements.put(npc.id, e);
        e.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> loopTick(npc.id, e),
                LOOP_PERIOD_TICKS, LOOP_PERIOD_TICKS);
    }

    /** End one NPC's engagement, cancelling its loop and letting routines resume. */
    public void disengage(String npcId) {
        Engagement e = engagements.remove(npcId);
        if (e == null) return;
        if (e.task != null) e.task.cancel();
        npcs.cancelWalk(npcId);
        NpcData data = npcs.get(npcId);
        if (data != null) data.manualOverrideUntilMs = 0; // stand-down: routine may resume now
    }

    /** End every engagement - called on plugin disable. */
    public void disengageAll() {
        for (String id : engagements.keySet()) disengage(id);
    }

    // ---- the 10-tick loop ----

    private void loopTick(String npcId, Engagement e) {
        NpcData data = npcs.get(npcId);
        if (data == null || data.dead) { disengage(npcId); return; }
        Entity entity = npcs.resolveEntity(data);
        if (entity == null) { disengage(npcId); return; }
        data.manualOverrideUntilMs = System.currentTimeMillis() + OVERRIDE_BUFFER_MS;

        LivingEntity attacker = e.attacker;
        if (attacker == null || !attacker.isValid() || attacker.isDead()
                || attacker.getWorld() != entity.getWorld()) {
            disengage(npcId);
            return;
        }
        long now = System.currentTimeMillis();
        double distSq = attacker.getLocation().distanceSquared(entity.getLocation());
        double disengageDistance = e.flee ? FLEE_DISENGAGE_DISTANCE
                : plugin.getConfig().getDouble("npc-defense.disengage-distance", 24);
        long disengageMs = plugin.getConfig().getLong("npc-defense.disengage-seconds", 12) * 1000L;
        if (DefenseMath.beyondRange(distSq, disengageDistance)
                || (!e.flee && DefenseMath.exchangeStale(now, e.lastExchangeMs, disengageMs))) {
            disengage(npcId);
            return;
        }

        if (e.flee) {
            fleeTick(data, entity, attacker);
        } else {
            fightTick(data, entity, attacker, e, now, distSq);
        }
    }

    private void fightTick(NpcData data, Entity entity, LivingEntity attacker,
                            Engagement e, long now, double distSq) {
        double meleeRange = plugin.getConfig().getDouble("npc-defense.melee-range", 2.5);
        if (distSq <= meleeRange * meleeRange) {
            long cooldownMs = plugin.getConfig().getLong("npc-defense.cooldown-ticks", 20) * 50L;
            if (!DefenseMath.cooldownReady(now, e.lastAttackMs, cooldownMs)) return;
            e.lastAttackMs = now;
            e.lastExchangeMs = now;
            double damage = data.attackDamage > 0 ? data.attackDamage
                    : plugin.getConfig().getDouble("npc-defense.damage", 3.0);
            npcs.face(entity, attacker.getLocation());
            attacker.damage(damage, entity);
            if (entity instanceof LivingEntity living) living.swingMainHand();
            entity.getWorld().playSound(entity.getLocation(), "entity.player.attack.strong", 1.0f, 1.0f);
        } else {
            walkQuietly(data, attacker.getLocation(), 1.1);
        }
    }

    private void fleeTick(NpcData data, Entity entity, LivingEntity attacker) {
        Location npcLoc = entity.getLocation();
        Location atkLoc = attacker.getLocation();
        double[] p = DefenseMath.fleePoint(npcLoc.getX(), npcLoc.getY(), npcLoc.getZ(),
                atkLoc.getX(), atkLoc.getZ(), DefenseMath.FLEE_DISTANCE);
        walkQuietly(data, new Location(entity.getWorld(), p[0], p[1], p[2]), 1.3);
    }

    /** walkTo snaps targets through safeStanding, which throws over unwalkable terrain
     *  (mid-ocean, deep ravine walls); during a fight that just means "hold position
     *  this tick" rather than an error worth surfacing. */
    private void walkQuietly(NpcData data, Location target, double speed) {
        try {
            npcs.walkTo(data, target, speed);
        } catch (IllegalStateException ignored) {
            // no safe footing toward the target this tick; re-evaluated next loop
        }
    }
}
