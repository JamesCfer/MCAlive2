package dev.celestia.mcalive2.actuators;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import dev.celestia.mcalive2.util.Json;
import dev.celestia.mcalive2.util.RegionMath;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Big, showy world effects: explosions, timed lightning sequences, and moving chunks of
 * terrain around. Separate from {@link WorldActuators} because these are "spectacle" tools
 * meant for dramatic beats rather than routine world editing.
 */
public class SpectacleActuators {

    private static final int MAX_CONCURRENT_LIGHTNING_SEQUENCES = 4;
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final MCAlive2Plugin plugin;

    /** Sequence id -> its repeating task, so we can bound concurrency and let tasks self-remove. */
    private final Map<String, BukkitTask> activeLightningSequences = new ConcurrentHashMap<>();

    public SpectacleActuators(MCAlive2Plugin plugin) {
        this.plugin = plugin;
    }

    public void register(CommandDispatcher d) {
        d.register("create_explosion", this::createExplosion);
        d.register("strike_lightning", this::strikeLightning);
        d.register("move_region", this::moveRegion);
    }

    private long fillCap() {
        return plugin.getConfig().getLong("max-fill-volume", 100000);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String randomId() {
        StringBuilder sb = new StringBuilder(8);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            sb.append(ID_CHARS.charAt(rnd.nextInt(ID_CHARS.length())));
        }
        return sb.toString();
    }

    // ---- create_explosion ----------------------------------------------------------------

    private JsonObject createExplosion(JsonObject args) {
        Location loc = Json.location(args);
        float power = (float) clamp(Json.optDouble(args, "power", 4), 0.5, 8);
        boolean fire = Json.optBool(args, "fire", false);
        boolean breakBlocks = Json.optBool(args, "breakBlocks", true);
        loc.getWorld().createExplosion(loc, power, fire, breakBlocks);
        return null;
    }

    // ---- strike_lightning ------------------------------------------------------------------

    private JsonObject strikeLightning(JsonObject args) {
        if (activeLightningSequences.size() >= MAX_CONCURRENT_LIGHTNING_SEQUENCES) {
            throw new IllegalArgumentException(
                    "too many concurrent lightning sequences (max " + MAX_CONCURRENT_LIGHTNING_SEQUENCES + ")");
        }

        World world = Json.world(args);
        double x = Json.reqDouble(args, "x");
        double z = Json.reqDouble(args, "z");
        int count = clamp(Json.optInt(args, "count", 1), 1, 200);
        double radiusBlocks = clamp(Json.optDouble(args, "radiusBlocks", 0), 0, 64);
        int intervalTicks = clamp(Json.optInt(args, "intervalTicks", 10), 1, 100);
        double explosionPower = clamp(Json.optDouble(args, "explosionPower", 0), 0, 8);

        String sequenceId = randomId();
        int[] struck = {0};
        BukkitTask[] taskHolder = new BukkitTask[1];

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            double px = x, pz = z;
            if (radiusBlocks > 0) {
                // uniform sampling within a disk of radius radiusBlocks centered on (x,z)
                double r = radiusBlocks * Math.sqrt(rnd.nextDouble());
                double theta = rnd.nextDouble() * 2 * Math.PI;
                px = x + r * Math.cos(theta);
                pz = z + r * Math.sin(theta);
            }
            int y = world.getHighestBlockYAt((int) Math.floor(px), (int) Math.floor(pz));
            Location strikeLoc = new Location(world, px, y, pz);
            world.strikeLightning(strikeLoc);
            if (explosionPower > 0) {
                world.createExplosion(strikeLoc, (float) explosionPower, false, true);
            }

            struck[0]++;
            if (struck[0] >= count) {
                BukkitTask self = taskHolder[0];
                if (self != null) self.cancel();
                activeLightningSequences.remove(sequenceId);

                JsonObject data = new JsonObject();
                data.addProperty("sequenceId", sequenceId);
                data.addProperty("kind", "lightning");
                JsonObject center = new JsonObject();
                center.addProperty("x", x);
                center.addProperty("z", z);
                data.add("center", center);
                data.addProperty("count", count);
                plugin.bridge().broadcastEvent("sequence_done", data);
            }
        }, 0L, intervalTicks);

        taskHolder[0] = task;
        activeLightningSequences.put(sequenceId, task);

        JsonObject out = new JsonObject();
        out.addProperty("sequenceId", sequenceId);
        return out;
    }

    // ---- move_region -----------------------------------------------------------------------

    /**
     * Copies a box of blocks to an offset destination, then (unless {@code clearSource} is
     * false) clears the entire source box to air. Iteration direction per axis is chosen via
     * {@link RegionMath#axisStep} so overlapping source/destination boxes are handled safely,
     * memmove-style. Only non-air source blocks are written to the destination - air is skipped
     * on the copy so the moved terrain doesn't punch air holes through whatever already
     * surrounds the destination - but {@code clearSource} still clears the whole source box
     * (air included) regardless of what was copied.
     */
    private JsonObject moveRegion(JsonObject args) {
        World world = Json.world(args);
        int x1 = (int) Json.reqDouble(args, "x1"), y1 = (int) Json.reqDouble(args, "y1"), z1 = (int) Json.reqDouble(args, "z1");
        int x2 = (int) Json.reqDouble(args, "x2"), y2 = (int) Json.reqDouble(args, "y2"), z2 = (int) Json.reqDouble(args, "z2");
        int dx = (int) Json.reqDouble(args, "dx"), dy = (int) Json.reqDouble(args, "dy"), dz = (int) Json.reqDouble(args, "dz");
        boolean clearSource = Json.optBool(args, "clearSource", true);

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        long cap = fillCap();
        if (volume > cap) {
            throw new IllegalArgumentException("region too large: " + volume + " blocks (cap " + cap + ")");
        }

        int stepX = RegionMath.axisStep(dx);
        int stepY = RegionMath.axisStep(dy);
        int stepZ = RegionMath.axisStep(dz);
        int sizeX = maxX - minX, sizeY = maxY - minY, sizeZ = maxZ - minZ;

        long moved = 0;
        for (int xi = 0; xi <= sizeX; xi++) {
            int x = stepX > 0 ? minX + xi : maxX - xi;
            for (int yi = 0; yi <= sizeY; yi++) {
                int y = stepY > 0 ? minY + yi : maxY - yi;
                for (int zi = 0; zi <= sizeZ; zi++) {
                    int z = stepZ > 0 ? minZ + zi : maxZ - zi;

                    Block src = world.getBlockAt(x, y, z);
                    if (src.getType().isAir()) continue;
                    BlockData data = src.getBlockData();
                    world.getBlockAt(x + dx, y + dy, z + dz).setBlockData(data, false);
                    moved++;
                }
            }
        }

        if (clearSource) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }

        JsonObject out = new JsonObject();
        out.addProperty("blocksMoved", moved);
        return out;
    }
}
