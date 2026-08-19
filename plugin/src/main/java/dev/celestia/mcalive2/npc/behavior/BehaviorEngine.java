package dev.celestia.mcalive2.npc.behavior;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import dev.celestia.mcalive2.ledger.BlueprintParser;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs behavior programs (see {@link BehaviorProgram}): the director authors a goal
 * ONCE - "this crew gathers 64 oak logs and builds the granary blueprint" - and this
 * engine executes it indefinitely with zero AI involvement, block by visible block,
 * surviving restarts, waking the director only via {@code behavior_done} /
 * {@code behavior_blocked} events.
 *
 * <p>One 20-tick task advances every active program's every cursor one small beat:
 * walk-and-wait movement reuses {@link NpcManager#walkTo} exactly like JobManager
 * (re-issuing the walk if the defense loop hijacked it), gathering REALLY removes
 * blocks from the world into a per-NPC carrying map, building places REAL blocks
 * against a registered {@link BlueprintRegistry} blueprint with shared placed/claim
 * bookkeeping so crew members never double-place, and deposit/withdraw move REAL
 * ItemStacks through real containers. {@code manualOverrideUntilMs} is refreshed while
 * a step is active so the daily routine never fights the program, and a cursor whose
 * NPC is busy defending itself (see DefenseManager) pauses rather than cancels.
 *
 * <p>With {@code behavior.keep-chunks-loaded} on, each cursor's current focus chunks
 * get plugin chunk tickets (capped per program) so crews keep working with no player
 * nearby; stale tickets are released as targets move and on pause/delete/disable.
 */
public class BehaviorEngine {

    private static final long TICK_PERIOD = 20L;
    /** Arrival radius for walk-and-wait targets, matching JobManager. */
    private static final double ARRIVAL_RADIUS_SQ = 2.5 * 2.5;
    /** Polls (at TICK_PERIOD) before an unreached walk target blocks the program - 60s, matching JobManager. */
    private static final int CANT_REACH_POLLS = 60;
    /** Re-issue an in-flight walk every this many polls, in case the walker gave up quietly. */
    private static final int WALK_REISSUE_POLLS = 5;
    /** Horizontal reach (squared) for digging/placing a claimed block. */
    private static final double WORK_RANGE_SQ = 3.0 * 3.0;
    /** Vertical reach for digging/placing - lets a crew fell a whole tree or raise a wall from the ground. */
    private static final double WORK_RANGE_Y = 12;
    /** manualOverrideUntilMs is refreshed by this much every tick, comfortably longer than one period. */
    private static final long OVERRIDE_BUFFER_MS = 90_000;
    /** Hard cap on the gather search radius - the cube scan is O(r^3). */
    private static final int MAX_GATHER_RADIUS = 24;
    /** Cap on |dx|,|dy|,|dz| for a registered blueprint entry (mirrors WorldActuators). */
    static final int BLUEPRINT_MAX_OFFSET = 256;

    private final MCAlive2Plugin plugin;
    private final NpcManager npcs;
    /** True while an NPC is busy defending itself (see DefenseManager) - its cursor
     *  pauses (no progress, no timeout accrual) rather than cancels, and resumes after. */
    private final java.util.function.Predicate<String> engaged;
    private final BehaviorStore store = new BehaviorStore();
    private final BlueprintRegistry blueprints = new BlueprintRegistry();

    /** Transient per-cursor walk state, keyed programId + "/" + npcId. Never persisted -
     *  on restart, walks simply re-issue. */
    private final Map<String, Runtime> runtime = new HashMap<>();
    /** Chunk tickets currently held per program. */
    private final Map<String, Set<TicketKey>> tickets = new HashMap<>();
    private BukkitTask task;

    private static final class Runtime {
        Location walkTarget;
        int walkPolls = 0;
        int reissueCounter = 0;
        boolean wasEngaged = false;
    }

    private record TicketKey(String world, int cx, int cz) {}

    public BehaviorEngine(MCAlive2Plugin plugin, NpcManager npcs, java.util.function.Predicate<String> engaged) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.engaged = engaged;
        // no onRemoval hook needed: the tick loop itself drops dead/removed crew members
    }

    public BehaviorStore store() {
        return store;
    }

    public BlueprintRegistry blueprints() {
        return blueprints;
    }

    // ---- lifecycle ----

    /** Start the 20-tick engine loop. Call only when config behavior.enabled is true. */
    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    }

    /** Stop the loop and release every chunk ticket - onDisable and config-off path. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (String programId : new ArrayList<>(tickets.keySet())) releaseTickets(programId);
        runtime.clear();
    }

    /** Drop transient walk state (and tickets) for a program whose definition changed. */
    public void clearRuntime(String programId) {
        runtime.keySet().removeIf(k -> k.startsWith(programId + "/"));
        releaseTickets(programId);
    }

    private File behaviorsFile() {
        return new File(plugin.getDataFolder(), "behaviors.json");
    }

    private File blueprintsFile() {
        return new File(plugin.getDataFolder(), "blueprints.json");
    }

    public void save() {
        try {
            plugin.getDataFolder().mkdirs();
            store.save(behaviorsFile());
            blueprints.save(blueprintsFile());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save behaviors/blueprints: " + e.getMessage());
        }
    }

    public void load() {
        try {
            blueprints.load(blueprintsFile(), fillCap(), BLUEPRINT_MAX_OFFSET);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load blueprints.json: " + e.getMessage());
        }
        try {
            store.load(behaviorsFile());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load behaviors.json: " + e.getMessage());
        }
    }

    int fillCap() {
        return (int) plugin.getConfig().getLong("max-fill-volume", 100000);
    }

    // ---- tick loop ----

    private void tick() {
        Set<String> active = new HashSet<>();
        for (BehaviorProgram p : store.all()) {
            if (p.paused) {
                releaseTickets(p.id);
                continue;
            }
            active.add(p.id);
            try {
                tickProgram(p);
            } catch (Exception e) {
                plugin.getLogger().warning("Behavior tick failed for " + p.id + ": " + e.getMessage());
            }
        }
        // release tickets held for programs that vanished or paused mid-tick
        for (String programId : new ArrayList<>(tickets.keySet())) {
            if (!active.contains(programId) || isPausedOrGone(programId)) releaseTickets(programId);
        }
    }

    private boolean isPausedOrGone(String programId) {
        BehaviorProgram p = store.get(programId);
        return p == null || p.paused;
    }

    private void tickProgram(BehaviorProgram p) {
        boolean keepLoaded = plugin.getConfig().getBoolean("behavior.keep-chunks-loaded", true);
        int stepCount = p.steps.size();

        for (String npcId : new ArrayList<>(p.cursors.keySet())) {
            BehaviorProgram.Cursor c = p.cursors.get(npcId);
            NpcData data = npcs.get(npcId);
            if (data == null || data.dead) {
                p.cursors.remove(npcId);
                runtime.remove(runtimeKey(p.id, npcId));
                continue;
            }
            if (BehaviorMath.isDone(c.step, stepCount)) continue; // waiting for crewmates
            Runtime rt = runtime.computeIfAbsent(runtimeKey(p.id, npcId), k -> new Runtime());
            if (engaged.test(npcId)) {
                rt.wasEngaged = true; // defending itself: pause, don't time out
                continue;
            }
            Entity entity = resolveFor(data, keepLoaded);
            if (entity == null) continue; // unresolved/chunk unloaded: skip this beat
            data.manualOverrideUntilMs = System.currentTimeMillis() + OVERRIDE_BUFFER_MS;

            JsonObject step = p.steps.get(c.step);
            String type = step.get("type").getAsString().toLowerCase(Locale.ROOT);
            switch (type) {
                case "gather" -> tickGather(p, c, npcId, data, entity, step, rt);
                case "build" -> tickBuild(p, c, npcId, data, entity, step, rt);
                case "deposit" -> tickDeposit(p, c, npcId, data, entity, step, rt);
                case "withdraw" -> tickWithdraw(p, c, npcId, data, entity, step, rt);
                case "work_station" -> tickWorkStation(p, c, npcId, data, entity, step, rt);
                case "goto" -> tickGoto(p, c, npcId, data, entity, step, rt);
                case "wait" -> tickWait(p, c, rt);
                case "follow" -> tickFollow(p, c, data, entity, step, rt);
                default -> { /* unreachable: validated on parse */ }
            }
            if (p.paused) break; // a handler blocked the program: stop ticking it
        }

        if (p.cursors.isEmpty()) {
            blocked(p, null, -1, "crew_dead");
            return;
        }
        if (p.paused) return;

        List<Integer> cursorSteps = p.cursors.values().stream().map(c -> c.step).toList();
        if (BehaviorMath.allDone(cursorSteps, stepCount)) {
            store.remove(p.id);
            releaseTickets(p.id);
            clearRuntime(p.id);
            JsonObject data = new JsonObject();
            data.addProperty("programId", p.id);
            data.addProperty("name", p.name);
            plugin.bridge().broadcastEvent("behavior_done", data);
            save();
            return;
        }
        if (keepLoaded) updateTickets(p);
        else releaseTickets(p.id);
    }

    // ---- step handlers ----

    private void tickGather(BehaviorProgram p, BehaviorProgram.Cursor c, String npcId, NpcData data,
                            Entity entity, JsonObject step, Runtime rt) {
        String material = Json.reqString(step, "material").toUpperCase(Locale.ROOT);
        int count = step.get("count").getAsInt();
        int radius = Math.min(step.get("radius").getAsInt(), MAX_GATHER_RADIUS);
        int breakTicks = Json.optInt(step, "breakTicks", 60);
        if (BehaviorMath.has(c.carrying, material, count)) {
            completeStep(p, c, rt);
            return;
        }
        World world = entity.getWorld();

        // validate the currently claimed block still exists
        if (c.progress.has("tx")) {
            Block b = world.getBlockAt(c.progress.get("tx").getAsInt(),
                    c.progress.get("ty").getAsInt(), c.progress.get("tz").getAsInt());
            if (b.getType() != Material.matchMaterial(material)) clearGatherTarget(c);
        }
        // claim a new block: nearest match in radius of the anchor, skipping crewmates' claims
        if (!c.progress.has("tx")) {
            JsonObject anchor = step.getAsJsonObject("anchor");
            int[] found = findNearestBlock(world, anchor.get("x").getAsInt(), anchor.get("y").getAsInt(),
                    anchor.get("z").getAsInt(), radius, Material.matchMaterial(material),
                    gatherClaims(p, npcId), entity.getLocation());
            if (found == null) {
                blocked(p, npcId, c.step, "no_resources");
                return;
            }
            c.progress.addProperty("tx", found[0]);
            c.progress.addProperty("ty", found[1]);
            c.progress.addProperty("tz", found[2]);
            rt.walkTarget = null;
        }

        int tx = c.progress.get("tx").getAsInt();
        int ty = c.progress.get("ty").getAsInt();
        int tz = c.progress.get("tz").getAsInt();
        Location blockLoc = new Location(world, tx + 0.5, ty + 0.5, tz + 0.5);
        if (!withinWorkRange(entity, blockLoc)) {
            walkToward(p, c, npcId, data, entity, blockLoc, rt);
            return;
        }

        Block block = world.getBlockAt(tx, ty, tz);
        int broke = Json.optInt(c.progress, "broke", 0) + (int) TICK_PERIOD;
        c.progress.addProperty("broke", broke);
        npcs.face(entity, blockLoc);
        world.playSound(blockLoc, block.getBlockData().getSoundGroup().getHitSound(), 1.0f, 0.9f);
        world.spawnParticle(Particle.BLOCK, blockLoc, 12, 0.25, 0.25, 0.25, block.getBlockData());
        if (broke >= breakTicks) {
            world.playSound(blockLoc, block.getBlockData().getSoundGroup().getBreakSound(), 1.0f, 1.0f);
            block.setType(Material.AIR);
            BehaviorMath.add(c.carrying, material, 1);
            clearGatherTarget(c);
        }
    }

    private void clearGatherTarget(BehaviorProgram.Cursor c) {
        c.progress.remove("tx");
        c.progress.remove("ty");
        c.progress.remove("tz");
        c.progress.remove("broke");
    }

    private void tickBuild(BehaviorProgram p, BehaviorProgram.Cursor c, String npcId, NpcData data,
                           Entity entity, JsonObject step, Runtime rt) {
        String blueprintId = Json.reqString(step, "blueprintId");
        BlueprintRegistry.Blueprint bp = blueprints.get(blueprintId);
        if (bp == null) {
            blocked(p, npcId, c.step, "no_blueprint");
            return;
        }
        int placeTicks = Json.optInt(step, "placeTicks", 40);
        int blocksPerTrip = Json.optInt(step, "blocksPerTrip", 8);
        Set<Integer> placed = p.placedFor(c.step);
        if (placed.size() >= bp.blocks().size()) {
            c.progress.remove("claim");
            c.progress.remove("placing");
            completeStep(p, c, rt);
            return;
        }
        World world = entity.getWorld();
        Set<Integer> claimedByOthers = buildClaims(p, npcId, c.step);
        Location npcLoc = entity.getLocation();

        int claim = Json.optInt(c.progress, "claim", -1);
        if (claim >= 0 && placed.contains(claim)) {
            c.progress.remove("claim");
            c.progress.remove("placing");
            claim = -1;
        }
        if (claim < 0) {
            claim = BehaviorMath.nextBlockFor(bp.blocks(), placed, claimedByOthers, c.carrying,
                    npcLoc.getX(), npcLoc.getY(), npcLoc.getZ(), bp.ox(), bp.oy(), bp.oz());
            if (claim < 0) {
                // not carrying anything useful: resupply or block
                Map<String, Integer> needed = BehaviorMath.neededMaterials(bp.blocks(), placed, claimedByOthers);
                if (needed.isEmpty()) return; // crewmates claimed all remaining blocks: idle a beat
                JsonObject chestSpec = step.has("supplyChest") && step.get("supplyChest").isJsonObject()
                        ? step.getAsJsonObject("supplyChest") : null;
                if (chestSpec == null) {
                    blocked(p, npcId, c.step, "missing_inputs");
                    return;
                }
                Location chestLoc = locationOf(world, chestSpec);
                if (!walkToward(p, c, npcId, data, entity, chestLoc, rt)) return;
                Inventory inv = containerInventoryAt(chestLoc);
                if (inv == null) {
                    blocked(p, npcId, c.step, "missing_inputs");
                    return;
                }
                Map<String, Integer> plan = BehaviorMath.withdrawPlan(needed, countByMaterial(inv), blocksPerTrip);
                if (plan.isEmpty()) {
                    blocked(p, npcId, c.step, "missing_inputs");
                    return;
                }
                for (Map.Entry<String, Integer> e : plan.entrySet()) {
                    withdrawItems(inv, e.getKey(), e.getValue());
                    BehaviorMath.add(c.carrying, e.getKey(), e.getValue());
                }
                return; // next beat: claim a block with the fresh materials
            }
            c.progress.addProperty("claim", claim);
            rt.walkTarget = null;
        }

        BlueprintParser.BlockEntry blockEntry = bp.blocks().get(claim);
        Location target = new Location(world, bp.ox() + blockEntry.dx() + 0.5,
                bp.oy() + blockEntry.dy() + 0.5, bp.oz() + blockEntry.dz() + 0.5);
        if (!withinWorkRange(entity, target)) {
            walkToward(p, c, npcId, data, entity, target, rt);
            return;
        }

        int placing = Json.optInt(c.progress, "placing", 0) + (int) TICK_PERIOD;
        if (placing < placeTicks) {
            c.progress.addProperty("placing", placing);
            npcs.face(entity, target);
            return;
        }
        String materialName = blockEntry.material().toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(materialName);
        if (material == null || !BehaviorMath.consume(c.carrying, materialName, 1)) {
            // carried stock vanished (or bad material): drop the claim and replan next beat
            c.progress.remove("claim");
            c.progress.remove("placing");
            return;
        }
        Block block = world.getBlockAt(bp.ox() + blockEntry.dx(), bp.oy() + blockEntry.dy(), bp.oz() + blockEntry.dz());
        if (blockEntry.data() != null) {
            block.setBlockData(Bukkit.createBlockData(blockEntry.data()));
        } else {
            block.setType(material);
        }
        world.playSound(target, block.getBlockData().getSoundGroup().getPlaceSound(), 1.0f, 1.0f);
        placed.add(claim);
        c.progress.remove("claim");
        c.progress.remove("placing");
    }

    private void tickDeposit(BehaviorProgram p, BehaviorProgram.Cursor c, String npcId, NpcData data,
                             Entity entity, JsonObject step, Runtime rt) {
        Location chestLoc = locationOf(entity.getWorld(), step.getAsJsonObject("chest"));
        if (!walkToward(p, c, npcId, data, entity, chestLoc, rt)) return;

        Map<String, Integer> toDeposit = new LinkedHashMap<>();
        JsonElement items = step.get("items");
        if (items.isJsonPrimitive()) { // "all"
            toDeposit.putAll(c.carrying);
        } else {
            for (JsonElement el : items.getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                String material = Json.reqString(o, "material").toUpperCase(Locale.ROOT);
                int want = Json.optInt(o, "count", 1);
                int have = c.carrying.getOrDefault(material, 0);
                if (have > 0) toDeposit.put(material, Math.min(want, have));
            }
        }
        Inventory inv = containerInventoryAt(chestLoc);
        for (Map.Entry<String, Integer> e : toDeposit.entrySet()) {
            Material material = Material.matchMaterial(e.getKey());
            if (material == null) continue;
            BehaviorMath.consume(c.carrying, e.getKey(), e.getValue());
            int remaining = e.getValue();
            while (remaining > 0) {
                int batch = Math.min(remaining, material.getMaxStackSize());
                remaining -= batch;
                ItemStack stack = new ItemStack(material, batch);
                if (inv == null) {
                    chestLoc.getWorld().dropItemNaturally(chestLoc, stack);
                    continue;
                }
                for (ItemStack over : inv.addItem(stack).values()) {
                    // chest full: drop the overflow at the chest, and note it
                    chestLoc.getWorld().dropItemNaturally(chestLoc, over);
                    plugin.getLogger().info("Behavior " + p.id + ": chest at " + chestLoc.getBlockX() + ","
                            + chestLoc.getBlockY() + "," + chestLoc.getBlockZ() + " full, " + npcId
                            + " dropped " + over.getAmount() + "x " + over.getType() + " beside it");
                }
            }
        }
        completeStep(p, c, rt);
    }

    private void tickWithdraw(BehaviorProgram p, BehaviorProgram.Cursor c, String npcId, NpcData data,
                              Entity entity, JsonObject step, Runtime rt) {
        Location chestLoc = locationOf(entity.getWorld(), step.getAsJsonObject("chest"));
        if (!walkToward(p, c, npcId, data, entity, chestLoc, rt)) return;

        Inventory inv = containerInventoryAt(chestLoc);
        if (inv == null) {
            blocked(p, npcId, c.step, "missing_inputs");
            return;
        }
        // all-or-nothing, like JobManager's input withdrawal
        Map<String, Integer> available = countByMaterial(inv);
        for (JsonElement el : step.getAsJsonArray("items")) {
            JsonObject o = el.getAsJsonObject();
            String material = Json.reqString(o, "material").toUpperCase(Locale.ROOT);
            if (available.getOrDefault(material, 0) < Json.optInt(o, "count", 1)) {
                blocked(p, npcId, c.step, "missing_inputs");
                return;
            }
        }
        for (JsonElement el : step.getAsJsonArray("items")) {
            JsonObject o = el.getAsJsonObject();
            String material = Json.reqString(o, "material").toUpperCase(Locale.ROOT);
            int count = Json.optInt(o, "count", 1);
            withdrawItems(inv, material, count);
            BehaviorMath.add(c.carrying, material, count);
        }
        completeStep(p, c, rt);
    }

    private void tickWorkStation(BehaviorProgram p, BehaviorProgram.Cursor c, String npcId, NpcData data,
                                 Entity entity, JsonObject step, Runtime rt) {
        Location station = locationOf(entity.getWorld(), step.getAsJsonObject("station"));
        if (!walkToward(p, c, npcId, data, entity, station, rt)) return;

        int workTicks = Json.optInt(step, "workTicks", 100);
        String sound = Json.optString(step, "sound", "block.anvil.use");
        int worked = Json.optInt(c.progress, "worked", 0) + (int) TICK_PERIOD;
        entity.getWorld().playSound(station, sound, 1.0f, 1.0f);
        if (worked >= workTicks) {
            completeStep(p, c, rt);
        } else {
            c.progress.addProperty("worked", worked);
        }
    }

    private void tickGoto(BehaviorProgram p, BehaviorProgram.Cursor c, String npcId, NpcData data,
                          Entity entity, JsonObject step, Runtime rt) {
        Location target = new Location(entity.getWorld(), step.get("x").getAsDouble(),
                step.get("y").getAsDouble(), step.get("z").getAsDouble());
        if (walkToward(p, c, npcId, data, entity, target, rt)) completeStep(p, c, rt);
    }

    private void tickWait(BehaviorProgram p, BehaviorProgram.Cursor c, Runtime rt) {
        int ticks = Json.optInt(c.progress, "waited", 0) + (int) TICK_PERIOD;
        if (ticks >= step(p, c).get("ticks").getAsInt()) {
            completeStep(p, c, rt);
        } else {
            c.progress.addProperty("waited", ticks);
        }
    }

    private void tickFollow(BehaviorProgram p, BehaviorProgram.Cursor c, NpcData data,
                            Entity entity, JsonObject step, Runtime rt) {
        int duration = step.get("ticks").getAsInt();
        double distance = Json.optDouble(step, "distance", 3);
        int followed = Json.optInt(c.progress, "followed", 0) + (int) TICK_PERIOD;
        if (followed >= duration) {
            completeStep(p, c, rt);
            return;
        }
        c.progress.addProperty("followed", followed);
        // re-target the followed NPC's position every 40 ticks (every other beat)
        if ((followed / (int) TICK_PERIOD) % 2 != 0) return;
        NpcData target = npcs.get(Json.reqString(step, "npcId"));
        if (target == null || target.dead) return; // nothing to follow: run out the clock
        Entity targetEntity = target.entityUuid == null ? null : Bukkit.getEntity(target.entityUuid);
        if (targetEntity == null || !targetEntity.isValid() || targetEntity.getWorld() != entity.getWorld()) return;
        if (entity.getLocation().distanceSquared(targetEntity.getLocation()) > distance * distance) {
            try {
                npcs.walkTo(data, targetEntity.getLocation(), 1.0);
            } catch (Exception ignored) { /* no safe standing near the target: try again next beat */ }
        }
    }

    // ---- walking ----

    /**
     * Walk-and-wait toward a target, one beat at a time: returns true once the NPC has
     * arrived (within {@link #ARRIVAL_RADIUS_SQ}); otherwise (re-)issues the walk via
     * {@link NpcManager#walkTo} - like JobManager, re-aiming after the defense loop
     * hijacked walking - and blocks the program with "cant_reach" if the target stays
     * unreached for {@link #CANT_REACH_POLLS} beats.
     */
    private boolean walkToward(BehaviorProgram p, BehaviorProgram.Cursor c, String npcId, NpcData data,
                               Entity entity, Location target, Runtime rt) {
        if (entity.getWorld().equals(target.getWorld())
                && entity.getLocation().distanceSquared(target) <= ARRIVAL_RADIUS_SQ) {
            rt.walkTarget = null;
            rt.walkPolls = 0;
            return true;
        }
        boolean newTarget = rt.walkTarget == null
                || rt.walkTarget.getBlockX() != target.getBlockX()
                || rt.walkTarget.getBlockY() != target.getBlockY()
                || rt.walkTarget.getBlockZ() != target.getBlockZ();
        if (newTarget) rt.walkPolls = 0;
        if (newTarget || rt.wasEngaged || ++rt.reissueCounter >= WALK_REISSUE_POLLS) {
            rt.reissueCounter = 0;
            rt.wasEngaged = false;
            rt.walkTarget = target.clone();
            try {
                npcs.walkTo(data, target, 1.0);
            } catch (Exception ignored) { /* no safe standing near the target: the timeout below reports it */ }
        }
        if (++rt.walkPolls >= CANT_REACH_POLLS) {
            blocked(p, npcId, c.step, "cant_reach");
        }
        return false;
    }

    private boolean withinWorkRange(Entity entity, Location block) {
        Location loc = entity.getLocation();
        if (!loc.getWorld().equals(block.getWorld())) return false;
        double dx = loc.getX() - block.getX();
        double dz = loc.getZ() - block.getZ();
        return dx * dx + dz * dz <= WORK_RANGE_SQ && Math.abs(loc.getY() - block.getY()) <= WORK_RANGE_Y;
    }

    // ---- program state transitions ----

    private void completeStep(BehaviorProgram p, BehaviorProgram.Cursor c, Runtime rt) {
        c.step = BehaviorMath.advance(c.step, p.steps.size(), p.loop);
        c.progress = new JsonObject();
        if (rt != null) {
            rt.walkTarget = null;
            rt.walkPolls = 0;
        }
    }

    /**
     * A cursor hit a wall the world cannot resolve on its own: broadcast
     * {@code behavior_blocked} ONCE and pause the program - the director decides what
     * happens next (behavior_resume clears the pause).
     */
    private void blocked(BehaviorProgram p, String npcId, int step, String reason) {
        p.paused = true;
        releaseTickets(p.id);
        JsonObject data = new JsonObject();
        data.addProperty("programId", p.id);
        data.addProperty("name", p.name);
        if (npcId != null) data.addProperty("npcId", npcId);
        if (step >= 0) data.addProperty("step", step);
        data.addProperty("reason", reason);
        plugin.bridge().broadcastEvent("behavior_blocked", data);
        save();
    }

    // ---- claims (read straight off crewmates' persisted cursors) ----

    /** Block positions (as packed keys) claimed by OTHER cursors of this program's gather steps. */
    private Set<Long> gatherClaims(BehaviorProgram p, String exceptNpcId) {
        Set<Long> claims = new HashSet<>();
        for (Map.Entry<String, BehaviorProgram.Cursor> e : p.cursors.entrySet()) {
            if (e.getKey().equals(exceptNpcId)) continue;
            JsonObject prog = e.getValue().progress;
            if (prog.has("tx")) {
                claims.add(packBlock(prog.get("tx").getAsInt(), prog.get("ty").getAsInt(), prog.get("tz").getAsInt()));
            }
        }
        return claims;
    }

    /** Blueprint block indices claimed by OTHER cursors currently on the same build step. */
    private Set<Integer> buildClaims(BehaviorProgram p, String exceptNpcId, int stepIndex) {
        Set<Integer> claims = new LinkedHashSet<>();
        for (Map.Entry<String, BehaviorProgram.Cursor> e : p.cursors.entrySet()) {
            if (e.getKey().equals(exceptNpcId)) continue;
            BehaviorProgram.Cursor other = e.getValue();
            if (other.step == stepIndex && other.progress.has("claim")) {
                claims.add(other.progress.get("claim").getAsInt());
            }
        }
        return claims;
    }

    // ---- world helpers ----

    private Entity resolveFor(NpcData data, boolean keepLoaded) {
        if (keepLoaded) return npcs.resolveEntity(data);
        // keep-chunks-loaded off: never force chunk loads just to tick a program
        Entity e = data.entityUuid == null ? null : Bukkit.getEntity(data.entityUuid);
        return e != null && e.isValid() ? e : null;
    }

    /** Nearest block of the wanted material within radius of the anchor, skipping claimed
     *  positions. Returns {x,y,z} or null when nothing matches anywhere in the radius. */
    private int[] findNearestBlock(World world, int ax, int ay, int az, int radius, Material material,
                                   Set<Long> claimed, Location nearestTo) {
        if (material == null) return null;
        int minY = Math.max(world.getMinHeight(), ay - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, ay + radius);
        int[] best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int x = ax - radius; x <= ax + radius; x++) {
            for (int z = az - radius; z <= az + radius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (world.getBlockAt(x, y, z).getType() != material) continue;
                    if (claimed.contains(packBlock(x, y, z))) continue;
                    double dx = x + 0.5 - nearestTo.getX();
                    double dy = y + 0.5 - nearestTo.getY();
                    double dz = z + 0.5 - nearestTo.getZ();
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    private static long packBlock(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }

    private Location locationOf(World world, JsonObject pos) {
        return new Location(world, pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble());
    }

    private Inventory containerInventoryAt(Location loc) {
        loc.getChunk().load();
        Block block = loc.getBlock();
        BlockState state = block.getState();
        return state instanceof Container container ? container.getInventory() : null;
    }

    private Map<String, Integer> countByMaterial(Inventory inv) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack item : inv.getContents()) {
            if (item == null) continue;
            counts.merge(item.getType().name(), item.getAmount(), Integer::sum);
        }
        return counts;
    }

    private void withdrawItems(Inventory inv, String materialName, int count) {
        Material mat = Material.matchMaterial(materialName);
        int remaining = count;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != mat) continue;
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            inv.setItem(i, item.getAmount() <= 0 ? null : item);
            remaining -= take;
        }
    }

    private JsonObject step(BehaviorProgram p, BehaviorProgram.Cursor c) {
        return p.steps.get(c.step);
    }

    private String runtimeKey(String programId, String npcId) {
        return programId + "/" + npcId;
    }

    // ---- chunk tickets ----

    /** Hold plugin chunk tickets at each cursor's current focus (its NPC's chunk and its
     *  walk target's chunk), capped per program, releasing whatever went stale. */
    private void updateTickets(BehaviorProgram p) {
        int cap = Math.max(1, plugin.getConfig().getInt("behavior.max-chunk-tickets-per-program", 4));
        Set<TicketKey> desired = new LinkedHashSet<>();
        for (Map.Entry<String, BehaviorProgram.Cursor> e : p.cursors.entrySet()) {
            if (desired.size() >= cap) break;
            NpcData data = npcs.get(e.getKey());
            if (data == null || data.dead) continue;
            Entity entity = data.entityUuid == null ? null : Bukkit.getEntity(data.entityUuid);
            if (entity != null && entity.isValid()) {
                Location loc = entity.getLocation();
                desired.add(new TicketKey(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
            }
            Runtime rt = runtime.get(runtimeKey(p.id, e.getKey()));
            if (rt != null && rt.walkTarget != null && desired.size() < cap) {
                desired.add(new TicketKey(rt.walkTarget.getWorld().getName(),
                        rt.walkTarget.getBlockX() >> 4, rt.walkTarget.getBlockZ() >> 4));
            }
        }
        while (desired.size() > cap) desired.remove(desired.iterator().next());

        Set<TicketKey> held = tickets.computeIfAbsent(p.id, k -> new HashSet<>());
        for (TicketKey stale : new ArrayList<>(held)) {
            if (!desired.contains(stale)) {
                removeTicket(stale);
                held.remove(stale);
            }
        }
        for (TicketKey key : desired) {
            if (held.add(key)) {
                World world = Bukkit.getWorld(key.world());
                if (world != null) world.addPluginChunkTicket(key.cx(), key.cz(), plugin);
                else held.remove(key);
            }
        }
    }

    /** Release every chunk ticket a program holds - on pause/block/done/delete/disable. */
    public void releaseTickets(String programId) {
        Set<TicketKey> held = tickets.remove(programId);
        if (held == null) return;
        for (TicketKey key : held) removeTicket(key);
    }

    private void removeTicket(TicketKey key) {
        World world = Bukkit.getWorld(key.world());
        if (world == null) return;
        // another program may still want this chunk: only drop the plugin ticket if no
        // other program's held set contains the same key
        for (Map.Entry<String, Set<TicketKey>> e : tickets.entrySet()) {
            if (e.getValue().contains(key)) return;
        }
        world.removePluginChunkTicket(key.cx(), key.cz(), plugin);
    }
}
