package dev.celestia.mcalive2.npc;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.util.Standing;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry, persistence, and routine ticking for living NPCs.
 *
 * Safety lessons baked in (see DESIGN.md "lessons already paid for in blood"):
 * mannequins walk via cliff-safe stepped movement; entity resolution always
 * chunk-loads + adopts by PDC tag before ever respawning, with a 60s cooldown, so a
 * slow-loading chunk can never cause a duplicate; an orphan sweep runs on startup and
 * on every EntitiesLoadEvent; death is permanent (see markDead) and never
 * auto-reverses.
 */
public class NpcManager {

    private static final long RESPAWN_COOLDOWN_MS = 60_000;
    /** How far above/below a requested y {@link #safeStanding} will scan for solid ground. */
    private static final int STANDING_SCAN_RADIUS = 12;

    private final MCAlive2Plugin plugin;
    private final NamespacedKey npcKey;
    private final Map<String, NpcData> npcs = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> walkers = new ConcurrentHashMap<>();
    private final Random random = new Random();
    /** Depth counter held across our own (synchronous, main-thread) spawnEntity calls, so
     *  SpawnGate can recognize plugin-spawned NPC bodies. The NPC id PDC tag is only
     *  applied AFTER spawnEntity returns, but CreatureSpawnEvent fires inside it - a tag
     *  check at event time would always miss, hence this latch. */
    private int spawningNpcDepth = 0;
    /** Notified (with the NPC id) whenever an NPC is permanently killed or removed - e.g. JobManager uses this to cancel any running job. */
    private final List<java.util.function.Consumer<String>> removalListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public NpcManager(MCAlive2Plugin plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "npc_id");
    }

    public NamespacedKey key() {
        return npcKey;
    }

    /** True while this manager is inside one of its own spawnEntity calls (main thread
     *  only) - SpawnGate uses this to exempt plugin-spawned NPC bodies from the regime. */
    public boolean isSpawningNpc() {
        return spawningNpcDepth > 0;
    }

    public NpcData get(String id) {
        return npcs.get(id);
    }

    public NpcData byEntity(Entity entity) {
        String id = entity.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
        return id == null ? null : npcs.get(id);
    }

    public List<NpcData> all() {
        return new ArrayList<>(npcs.values());
    }

    public int count() {
        return npcs.size();
    }

    /** Register a callback fired (with the NPC id) whenever an NPC is permanently killed or removed. */
    public void onRemoval(java.util.function.Consumer<String> listener) {
        removalListeners.add(listener);
    }

    private void notifyRemoved(String id) {
        for (java.util.function.Consumer<String> l : removalListeners) l.accept(id);
    }

    /** Spawn the backing entity for an NPC record and register it, snapping to safe ground. */
    public Entity spawn(NpcData data, Location loc) {
        return spawn(data, loc, true);
    }

    /** Spawn the backing entity for an NPC record and register it.
     *  @param snap if true (the default), the location is passed through {@link #safeStanding}
     *              first so the NPC never spawns floating or embedded in a wall. */
    public Entity spawn(NpcData data, Location loc, boolean snap) {
        EntityType type;
        try {
            type = EntityType.valueOf(data.entityType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown entity type: " + data.entityType);
        }
        loc.getChunk().load();
        Location target = snap ? safeStanding(loc) : loc;
        // never allow two entities for the same NPC: clear any loaded stragglers first
        removeTaggedEntities(data.id, null);
        Entity entity;
        spawningNpcDepth++;
        try {
            entity = loc.getWorld().spawnEntity(target, type);
        } finally {
            spawningNpcDepth--;
        }
        applyIdentity(entity, data);
        data.entityUuid = entity.getUniqueId();
        data.lastLocation = target.clone();
        npcs.put(data.id, data);
        return entity;
    }

    /**
     * Resolve the nearest safe standing location to {@code loc}: scans y +/- {@link
     * #STANDING_SCAN_RADIUS} for a spot with passable feet and head blocks and solid ground
     * underfoot (see {@link Standing}). Deliberately NOT {@code getHighestBlockYAt} - that
     * would drop an NPC standing near a floating island straight into the crater below it
     * instead of onto the island surface.
     */
    public Location safeStanding(Location loc) {
        World world = loc.getWorld();
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int requestedY = Math.max(minY, Math.min(maxY, loc.getBlockY()));
        int resolvedY;
        try {
            resolvedY = Standing.resolve(requestedY, minY, maxY, STANDING_SCAN_RADIUS,
                    y -> y >= minY && y <= maxY && world.getBlockAt(bx, y, bz).isPassable());
        } catch (IllegalStateException e) {
            throw new IllegalStateException("no safe standing spot near " + world.getName()
                    + " " + bx + "," + requestedY + "," + bz + " (scanned +/-" + STANDING_SCAN_RADIUS
                    + " blocks): " + e.getMessage());
        }
        Location safe = loc.clone();
        safe.setY(resolvedY);
        return safe;
    }

    private void applyIdentity(Entity entity, NpcData data) {
        entity.customName(Component.text(data.name, NamedTextColor.GOLD));
        entity.setCustomNameVisible(true);
        entity.setPersistent(true);
        entity.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, data.id);
        if (entity instanceof LivingEntity living) {
            living.setRemoveWhenFarAway(false);
        }
        if (entity instanceof Mannequin mannequin) {
            mannequin.setImmovable(false);
            applySkin(mannequin, data.skin);
        }
        if (entity instanceof Villager villager && data.profession != null) {
            Villager.Profession prof = switch (data.profession.toLowerCase(Locale.ROOT)) {
                case "armorer" -> Villager.Profession.ARMORER;
                case "butcher" -> Villager.Profession.BUTCHER;
                case "cartographer" -> Villager.Profession.CARTOGRAPHER;
                case "cleric" -> Villager.Profession.CLERIC;
                case "farmer" -> Villager.Profession.FARMER;
                case "fisherman" -> Villager.Profession.FISHERMAN;
                case "fletcher" -> Villager.Profession.FLETCHER;
                case "leatherworker" -> Villager.Profession.LEATHERWORKER;
                case "librarian" -> Villager.Profession.LIBRARIAN;
                case "mason" -> Villager.Profession.MASON;
                case "nitwit" -> Villager.Profession.NITWIT;
                case "shepherd" -> Villager.Profession.SHEPHERD;
                case "toolsmith" -> Villager.Profession.TOOLSMITH;
                case "weaponsmith" -> Villager.Profession.WEAPONSMITH;
                default -> Villager.Profession.NONE;
            };
            villager.setProfession(prof);
        }
    }

    /** Dress a mannequin in the skin of the given Minecraft username (async texture fetch). */
    private void applySkin(Mannequin mannequin, String skinName) {
        if (skinName == null || skinName.isBlank()) return;
        // name-only profile shows immediately; textures are patched in once fetched
        mannequin.setProfile(ResolvableProfile.resolvableProfile().name(skinName).build());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerProfile profile = Bukkit.createProfile(skinName);
            if (!profile.complete()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (mannequin.isValid()) {
                    mannequin.setProfile(ResolvableProfile.resolvableProfile(profile));
                }
            });
        });
    }

    /** Remove an NPC and its entity. Returns true if it existed. */
    public boolean remove(String id) {
        NpcData data = npcs.remove(id);
        if (data == null) return false;
        cancelWalk(id);
        if (data.entityUuid != null) {
            Entity entity = Bukkit.getEntity(data.entityUuid);
            if (entity != null) entity.remove();
        }
        removeTaggedEntities(id, null);
        notifyRemoved(id);
        return true;
    }

    /** Remove the NPC's current entity (keeping the record) and spawn a fresh one, e.g. after a type/skin change. */
    public Entity respawn(NpcData data) {
        cancelWalk(data.id);
        Location loc = data.lastLocation != null ? data.lastLocation
                : data.home != null ? data.home : data.work;
        if (data.entityUuid != null) {
            Entity old = Bukkit.getEntity(data.entityUuid);
            if (old != null) {
                loc = old.getLocation();
                old.remove();
            }
            data.entityUuid = null;
        }
        if (loc == null || loc.getWorld() == null) throw new IllegalStateException("NPC has no known location");
        return spawn(data, loc.clone());
    }

    /** Permanently kill an NPC: it will never be auto-respawned again until revive() is called. */
    public void markDead(NpcData data) {
        data.dead = true;
        data.diedAt = java.time.Instant.now().toString();
        cancelWalk(data.id);
        data.entityUuid = null;
        save();
        notifyRemoved(data.id);
    }

    /** Bring a previously dead NPC back, spawning a fresh entity at the given location. */
    public void revive(NpcData data, Location at) {
        data.dead = false;
        data.diedAt = null;
        spawn(data, at);
        save();
    }

    /**
     * A player head representing this NPC, wearing their skin if known. Suitable as a
     * memorial drop, and PDC-stamped with the NPC's id (same {@link #npcKey} used to tag
     * the living entity) so it doubles as a provable revival token - see
     * {@code npc_head_taken} / {@code npc_head_check}.
     */
    public ItemStack headOf(NpcData data) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        Component name = Component.text(data.name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false);
        Component lore = Component.text("In memoriam.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
        Component lingers = Component.text("Something of them lingers.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
        if (data.skin != null) {
            item.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().name(data.skin).build());
        }
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(List.of(lore, lingers));
            meta.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, data.id);
        });
        return item;
    }

    /** The live entity backing an NPC, respawning it if it is truly gone. Null if unresolvable or permanently dead. */
    public Entity resolveEntity(NpcData data) {
        if (data.dead) return null;
        if (data.entityUuid != null) {
            Entity e = Bukkit.getEntity(data.entityUuid);
            if (e != null && e.isValid()) return e;
        }
        Location loc = data.lastLocation != null ? data.lastLocation
                : data.home != null ? data.home : data.work;
        if (loc == null || loc.getWorld() == null) return null;
        // The entity may simply live in an unloaded chunk. Load it and look again -
        // entities can arrive a moment after the chunk, so also adopt by tag.
        loc.getChunk().load();
        if (data.entityUuid != null) {
            Entity e = Bukkit.getEntity(data.entityUuid);
            if (e != null && e.isValid()) return e;
        }
        for (Entity e : loc.getWorld().getNearbyEntities(loc, 64, 64, 64)) {
            if (e.isValid() && data.id.equals(e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING))) {
                data.entityUuid = e.getUniqueId();
                return e;
            }
        }
        // Truly missing (died to a creeper, fell in lava, /kill ...). Respawn, but never
        // more than once a minute so a slow-loading entity can't multiply.
        long now = System.currentTimeMillis();
        if (now - data.lastRespawnMs < RESPAWN_COOLDOWN_MS) return null;
        data.lastRespawnMs = now;
        try {
            Entity e = spawn(data, loc.clone());
            plugin.getLogger().info("Respawned missing NPC " + data.id + " at " + loc);
            return e;
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not respawn NPC " + data.id + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Reconcile a batch of just-loaded (or startup) entities against the registry:
     * adopt entities whose NPC lost track of them, remove duplicates and orphans.
     */
    public int sweepEntities(Collection<Entity> entities) {
        int removed = 0;
        for (Entity e : entities) {
            String id = e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
            if (id == null) continue;
            NpcData data = npcs.get(id);
            if (data == null) {
                e.remove();
                removed++;
                continue;
            }
            if (e.getUniqueId().equals(data.entityUuid)) continue;
            Entity canonical = data.entityUuid == null ? null : Bukkit.getEntity(data.entityUuid);
            if (canonical != null && canonical.isValid()) {
                e.remove();
                removed++;
            } else {
                data.entityUuid = e.getUniqueId();
            }
        }
        return removed;
    }

    /** Sweep every loaded entity in every world. */
    public int sweepAllLoaded() {
        int removed = 0;
        for (World w : Bukkit.getWorlds()) removed += sweepEntities(w.getEntities());
        return removed;
    }

    private void removeTaggedEntities(String id, Entity except) {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e == except) continue;
                if (id.equals(e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING))) {
                    e.remove();
                }
            }
        }
    }

    // ---- movement ----

    /** Walk an NPC toward a target (snapping to safe ground first): mob pathfinding when
     *  available, stepped walking for mannequins. */
    public boolean walkTo(NpcData data, Location target, double speed) {
        return walkTo(data, target, speed, true);
    }

    /** Walk an NPC toward a target: mob pathfinding when available, stepped walking for
     *  mannequins.
     *  @param snap if true (the default), the target is passed through {@link #safeStanding}
     *              first so a walk order never sends the NPC into a wall or off into the air. */
    public boolean walkTo(NpcData data, Location target, double speed, boolean snap) {
        Entity entity = resolveEntity(data);
        if (entity == null || target == null || target.getWorld() != entity.getWorld()) return false;
        Location dest = snap ? safeStanding(target) : target;
        if (entity instanceof Mob mob) {
            return mob.getPathfinder().moveTo(dest, speed);
        }
        if (entity instanceof LivingEntity living) {
            startStepWalk(data.id, living, dest.clone(), speed);
            return true;
        }
        return false;
    }

    public void cancelWalk(String id) {
        BukkitTask t = walkers.remove(id);
        if (t != null) t.cancel();
    }

    /** Simple surface walking for entities without AI (mannequins): small steps every tick,
     *  refusing cliffs (drop &gt; 3) and gaps too short to stand in (&lt; 2 tall). */
    private void startStepWalk(String id, LivingEntity entity, Location target, double speed) {
        cancelWalk(id);
        double step = 0.14 * Math.max(0.2, speed);
        long maxTicks = 20L * 90;
        long[] ticks = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ticks[0]++;
            if (!entity.isValid() || ticks[0] > maxTicks) { cancelWalk(id); return; }
            Location cur = entity.getLocation();
            double dx = target.getX() - cur.getX();
            double dz = target.getZ() - cur.getZ();
            double dist = Math.hypot(dx, dz);
            if (dist < 0.7) { cancelWalk(id); return; }
            Location next = cur.clone().add(dx / dist * step, 0, dz / dist * step);
            // step up one block or settle down up to three, staying on solid ground
            if (!next.getBlock().isPassable()) {
                next.add(0, 1, 0);
                if (!next.getBlock().isPassable()) { cancelWalk(id); return; } // wall
            } else {
                int drops = 0;
                while (drops < 3 && next.getBlockY() > next.getWorld().getMinHeight()
                        && next.clone().add(0, -1, 0).getBlock().isPassable()) {
                    next.add(0, -1, 0);
                    drops++;
                }
                // a drop deeper than we can step down is a cliff: stay put rather than walk off it
                if (next.clone().add(0, -1, 0).getBlock().isPassable()) { cancelWalk(id); return; }
            }
            // never step into a space too short to stand in (suffocation / a gap < 2 tall)
            if (!next.clone().add(0, 1, 0).getBlock().isPassable()) { cancelWalk(id); return; }
            next.setDirection(new Vector(dx, 0, dz));
            entity.teleport(next);
        }, 1L, 1L);
        walkers.put(id, task);
    }

    /** Turn an NPC to face a target (works for mobs and mannequins). */
    public void face(Entity entity, Location at) {
        if (entity instanceof Mob mob) {
            mob.lookAt(at);
        } else {
            Location l = entity.getLocation();
            l.setDirection(at.toVector().subtract(l.toVector()));
            entity.teleport(l);
        }
    }

    // ---- routine ticking ----

    /** Called every 100 ticks: advance every NPC's daily routine. */
    public void tickRoutines() {
        for (NpcData data : npcs.values()) {
            try {
                tickOne(data);
            } catch (Exception e) {
                plugin.getLogger().warning("NPC tick failed for " + data.id + ": " + e.getMessage());
            }
        }
    }

    private void tickOne(NpcData data) {
        Entity entity = resolveEntity(data);
        if (entity == null) return;
        data.lastLocation = entity.getLocation().clone();
        if (System.currentTimeMillis() < data.manualOverrideUntilMs) return;

        NpcData.ScheduleEntry entry = data.activeEntry(entity.getWorld().getTime());
        if (entry == null) return;
        switch (entry.action) {
            case "goto_home" -> walkTowardsIfFar(data, entity, data.home);
            case "goto_work" -> walkTowardsIfFar(data, entity, data.work);
            case "wander" -> {
                if (random.nextDouble() < 0.35) {
                    Location anchor = data.work != null ? data.work : entity.getLocation();
                    Location target = anchor.clone().add(
                            (random.nextDouble() * 2 - 1) * entry.radius,
                            0,
                            (random.nextDouble() * 2 - 1) * entry.radius);
                    target.setY(anchor.getWorld().getHighestBlockYAt(target) + 1);
                    walkTo(data, target, 1.0);
                }
            }
            default -> { /* idle */ }
        }
    }

    private void walkTowardsIfFar(NpcData data, Entity entity, Location target) {
        if (target == null || target.getWorld() != entity.getWorld()) return;
        if (entity.getLocation().distanceSquared(target) > 9) {
            walkTo(data, target, 1.0);
        }
    }

    // ---- persistence ----

    private File file() {
        return new File(plugin.getDataFolder(), "npcs.json");
    }

    public void save() {
        try {
            plugin.getDataFolder().mkdirs();
            JsonArray arr = new JsonArray();
            for (NpcData d : npcs.values()) arr.add(d.toJson());
            Files.write(file().toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save npcs.json: " + e.getMessage());
        }
    }

    public void load() {
        File f = file();
        if (!f.exists()) return;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (JsonElement e : JsonParser.parseString(s).getAsJsonArray()) {
                NpcData d = NpcData.fromJson(e.getAsJsonObject());
                npcs.put(d.id, d);
            }
            plugin.getLogger().info("Loaded " + npcs.size() + " NPCs");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load npcs.json: " + e.getMessage());
        }
    }

    public void putRaw(NpcData data) {
        npcs.put(data.id, data);
    }

    public JsonObject toJson(NpcData d) {
        JsonObject o = d.toJson();
        Entity e = d.entityUuid == null ? null : Bukkit.getEntity(d.entityUuid);
        o.addProperty("alive", !d.dead && e != null && e.isValid());
        return o;
    }
}
