package dev.celestia.mcalive2.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import dev.celestia.mcalive2.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Makes an NPC physically work a station using REAL container inventories: walk to an
 * input chest (if any), withdraw the required inputs all-or-nothing, walk to the station
 * and work there for a while, then walk to an output chest (if any) and deposit the
 * results - or drop them at the station if there's no output chest. One job per NPC;
 * assigning a new job replaces whatever was running.
 *
 * <p>Jobs are pure in-memory state (see {@link #jobs}) and do NOT survive a server
 * restart - unlike NPC records and the ledger, nothing here is persisted to disk. After a
 * restart, an NPC simply has no job running until the brain calls {@code npc_assign_job}
 * again.
 */
public class JobManager {

    private static final double ARRIVAL_RADIUS_SQ = 2.5 * 2.5;
    private static final long ARRIVAL_POLL_TICKS = 20L;
    private static final long ARRIVAL_TIMEOUT_TICKS = 20L * 60; // 60 seconds
    private static final long WORK_TICK_PERIOD = 20L;
    /** manualOverrideUntilMs is refreshed by this much every step, comfortably longer than one poll/work interval. */
    private static final long OVERRIDE_BUFFER_MS = 90_000;

    private final MCAlive2Plugin plugin;
    private final NpcManager npcs;
    /** True while an NPC is busy defending itself (see DefenseManager) - its job loops
     *  pause (no progress, no timeout accrual) rather than cancel, and resume after. */
    private final java.util.function.Predicate<String> engaged;
    private final Map<String, Job> jobs = new HashMap<>();

    public JobManager(MCAlive2Plugin plugin, NpcManager npcs, java.util.function.Predicate<String> engaged) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.engaged = engaged;
        npcs.onRemoval(this::cancelJob);
    }

    public void register(CommandDispatcher d) {
        d.register("npc_assign_job", this::assign);
        d.register("npc_job_cancel", this::cancel);
    }

    private static final class Job {
        String npcId;
        Location station;
        Location inputChest;
        Location outputChest;
        List<JobInventoryMath.Requirement> inputs;
        List<JobInventoryMath.Requirement> outputs;
        long workTicks;
        int repeats; // 0 = repeat until inputs run out
        String workSound;
        int cyclesDone = 0;
        BukkitTask activeTask;
        boolean cancelled = false;
    }

    // ---- commands ----

    private JsonObject assign(JsonObject args) {
        String npcId = Json.reqString(args, "npcId");
        if (npcs.get(npcId) == null) throw new IllegalArgumentException("no NPC with id: " + npcId);
        JsonObject jobSpec = Json.reqObject(args, "job");

        Job job = new Job();
        job.npcId = npcId;
        job.station = Json.location(Json.reqObject(jobSpec, "station"));
        job.inputChest = jobSpec.has("inputChest") && jobSpec.get("inputChest").isJsonObject()
                ? Json.location(jobSpec.getAsJsonObject("inputChest")) : null;
        job.outputChest = jobSpec.has("outputChest") && jobSpec.get("outputChest").isJsonObject()
                ? Json.location(jobSpec.getAsJsonObject("outputChest")) : null;
        job.inputs = parseRequirements(jobSpec, "inputs");
        job.outputs = parseRequirements(jobSpec, "outputs");
        job.workTicks = jobSpec.has("workTicks") && !jobSpec.get("workTicks").isJsonNull()
                ? jobSpec.get("workTicks").getAsLong() : 100L;
        job.repeats = jobSpec.has("repeats") && !jobSpec.get("repeats").isJsonNull()
                ? jobSpec.get("repeats").getAsInt() : 1;
        job.workSound = Json.optString(jobSpec, "workSound", "block.anvil.use");

        cancelJob(npcId); // one job per NPC: reassigning replaces
        jobs.put(npcId, job);
        startCycle(job);
        return new JsonObject();
    }

    private JsonObject cancel(JsonObject args) {
        cancelJob(Json.reqString(args, "npcId"));
        return new JsonObject();
    }

    /** Cancel any running job for this NPC. Also invoked automatically on NPC death/removal. */
    public void cancelJob(String npcId) {
        Job job = jobs.remove(npcId);
        if (job == null) return;
        job.cancelled = true;
        if (job.activeTask != null) job.activeTask.cancel();
    }

    private List<JobInventoryMath.Requirement> parseRequirements(JsonObject jobSpec, String key) {
        List<JobInventoryMath.Requirement> out = new ArrayList<>();
        if (!jobSpec.has(key) || !jobSpec.get(key).isJsonArray()) return out;
        for (JsonElement el : jobSpec.getAsJsonArray(key)) {
            JsonObject o = el.getAsJsonObject();
            String material = Json.reqString(o, "material").toUpperCase(Locale.ROOT);
            if (Material.matchMaterial(material) == null) {
                throw new IllegalArgumentException("unknown material: " + material);
            }
            int count = Json.optInt(o, "count", 1);
            out.add(new JobInventoryMath.Requirement(material, count));
        }
        return out;
    }

    // ---- cycle state machine ----

    private void startCycle(Job job) {
        if (job.cancelled) return;
        NpcData data = npcs.get(job.npcId);
        if (data == null || data.dead) { blocked(job, "npc_dead", null); return; }
        refreshOverride(data);

        if (job.inputChest != null) {
            walkAndWait(job, job.inputChest, "cant_reach", () -> withdrawInputs(job));
        } else {
            goToStation(job);
        }
    }

    private void withdrawInputs(Job job) {
        if (job.cancelled) return;
        if (job.inputs.isEmpty()) { goToStation(job); return; }
        Inventory inv = containerInventoryAt(job.inputChest);
        if (inv == null) { blocked(job, "missing_inputs", job.inputs); return; }

        Map<String, Integer> available = countByMaterial(inv);
        List<JobInventoryMath.Requirement> missing = JobInventoryMath.missing(available, job.inputs);
        if (!missing.isEmpty()) { blocked(job, "missing_inputs", missing); return; }

        for (JobInventoryMath.Requirement req : job.inputs) {
            withdraw(inv, req.material(), req.count());
        }
        goToStation(job);
    }

    private void goToStation(Job job) {
        if (job.cancelled) return;
        NpcData data = npcs.get(job.npcId);
        if (data == null || data.dead) { blocked(job, "npc_dead", null); return; }
        walkAndWait(job, job.station, "cant_reach", () -> work(job));
    }

    private void work(Job job) {
        if (job.cancelled) return;
        NpcData data = npcs.get(job.npcId);
        if (data == null || data.dead) { blocked(job, "npc_dead", null); return; }
        refreshOverride(data);

        long[] elapsed = {0};
        job.activeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (job.cancelled) { cancelTask(job); return; }
            if (engaged.test(job.npcId)) return; // defending itself: pause, don't cancel
            NpcData d = npcs.get(job.npcId);
            Entity entity = d == null ? null : npcs.resolveEntity(d);
            if (d == null || d.dead || entity == null) { cancelTask(job); blocked(job, "npc_dead", null); return; }
            elapsed[0] += WORK_TICK_PERIOD;
            entity.getWorld().playSound(job.station, job.workSound, 1.0f, 1.0f);
            if (elapsed[0] >= job.workTicks) {
                cancelTask(job);
                if (job.outputChest != null) {
                    walkAndWait(job, job.outputChest, "cant_reach", () -> depositOutputs(job));
                } else {
                    dropOutputsAt(job.station, job.outputs);
                    completeCycle(job);
                }
            }
        }, WORK_TICK_PERIOD, WORK_TICK_PERIOD);
    }

    private void depositOutputs(Job job) {
        if (job.cancelled) return;
        Inventory inv = containerInventoryAt(job.outputChest);
        if (inv == null) {
            dropOutputsAt(job.outputChest, job.outputs);
        } else {
            deposit(inv, job.outputs, job.outputChest);
        }
        completeCycle(job);
    }

    private void completeCycle(Job job) {
        job.cyclesDone++;
        boolean untilInputsRunOut = job.repeats == 0;
        if (!untilInputsRunOut && job.cyclesDone >= job.repeats) {
            jobs.remove(job.npcId);
            JsonObject data = new JsonObject();
            data.addProperty("npcId", job.npcId);
            data.addProperty("cycles", job.cyclesDone);
            plugin.bridge().broadcastEvent("npc_job_done", data);
            return;
        }
        startCycle(job);
    }

    private void cancelTask(Job job) {
        if (job.activeTask != null) { job.activeTask.cancel(); job.activeTask = null; }
    }

    private void blocked(Job job, String reason, List<JobInventoryMath.Requirement> missing) {
        cancelTask(job);
        jobs.remove(job.npcId);
        job.cancelled = true;
        JsonObject data = new JsonObject();
        data.addProperty("npcId", job.npcId);
        data.addProperty("reason", reason);
        if (missing != null) {
            JsonArray arr = new JsonArray();
            for (JobInventoryMath.Requirement r : missing) {
                JsonObject o = new JsonObject();
                o.addProperty("material", r.material());
                o.addProperty("count", r.count());
                arr.add(o);
            }
            data.add("missing", arr);
        }
        plugin.bridge().broadcastEvent("npc_job_blocked", data);
    }

    private void refreshOverride(NpcData data) {
        data.manualOverrideUntilMs = System.currentTimeMillis() + OVERRIDE_BUFFER_MS;
    }

    // ---- walking / arrival ----

    private void walkAndWait(Job job, Location target, String timeoutReason, Runnable onArrive) {
        NpcData data = npcs.get(job.npcId);
        if (data == null || data.dead) { blocked(job, "npc_dead", null); return; }
        refreshOverride(data);
        npcs.walkTo(data, target, 1.0);

        long[] waited = {0};
        boolean[] wasEngaged = {false};
        job.activeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (job.cancelled) { cancelTask(job); return; }
            if (engaged.test(job.npcId)) { wasEngaged[0] = true; return; } // defending itself: pause, don't time out
            NpcData d = npcs.get(job.npcId);
            if (d == null || d.dead) { cancelTask(job); blocked(job, "npc_dead", null); return; }
            if (wasEngaged[0]) { // fight over: the defense loop hijacked walking, re-aim at the target
                wasEngaged[0] = false;
                npcs.walkTo(d, target, 1.0);
            }
            refreshOverride(d);
            Entity entity = npcs.resolveEntity(d);
            waited[0] += ARRIVAL_POLL_TICKS;
            if (entity != null && entity.getWorld().equals(target.getWorld())
                    && entity.getLocation().distanceSquared(target) <= ARRIVAL_RADIUS_SQ) {
                cancelTask(job);
                onArrive.run();
                return;
            }
            if (waited[0] >= ARRIVAL_TIMEOUT_TICKS) {
                cancelTask(job);
                blocked(job, timeoutReason, null);
            }
        }, ARRIVAL_POLL_TICKS, ARRIVAL_POLL_TICKS);
    }

    // ---- inventory helpers ----

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

    private void withdraw(Inventory inv, String materialName, int count) {
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

    private void deposit(Inventory inv, List<JobInventoryMath.Requirement> outputs, Location dropAt) {
        for (JobInventoryMath.Requirement req : outputs) {
            Material mat = Material.matchMaterial(req.material());
            if (mat == null) continue;
            Map<Integer, ItemStack> leftover = inv.addItem(new ItemStack(mat, req.count()));
            for (ItemStack over : leftover.values()) {
                dropAt.getWorld().dropItemNaturally(dropAt, over);
            }
        }
    }

    private void dropOutputsAt(Location loc, List<JobInventoryMath.Requirement> outputs) {
        for (JobInventoryMath.Requirement req : outputs) {
            Material mat = Material.matchMaterial(req.material());
            if (mat == null) continue;
            loc.getWorld().dropItemNaturally(loc, new ItemStack(mat, req.count()));
        }
    }
}
