package dev.celestia.mcalive2.actuators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import dev.celestia.mcalive2.ledger.BlueprintParser;
import dev.celestia.mcalive2.senses.TerrainSampler;
import dev.celestia.mcalive2.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Block editing, world state, entity spawning, terrain sampling, and blueprint pasting. */
public class WorldActuators {

    /** Cap on |dx|,|dy|,|dz| for a single build_blueprint entry, independent of the block-count cap. */
    private static final int BLUEPRINT_MAX_OFFSET = 256;

    private final MCAlive2Plugin plugin;

    public WorldActuators(MCAlive2Plugin plugin) {
        this.plugin = plugin;
    }

    public void register(CommandDispatcher d) {
        d.register("get_server_info", this::serverInfo);
        d.register("set_block", this::setBlock);
        d.register("get_block", this::getBlock);
        d.register("fill_region", this::fillRegion);
        d.register("set_weather", this::setWeather);
        d.register("spawn_entity", this::spawnEntity);
        d.register("remove_entity", this::removeEntity);
        d.register("spawn_particles", this::spawnParticles);
        d.register("play_sound", this::playSound);
        d.register("sample_terrain", this::sampleTerrain);
        d.register("scan_area", this::scanArea);
        d.register("build_blueprint", this::buildBlueprint);
    }

    private JsonObject serverInfo(JsonObject args) {
        JsonObject data = new JsonObject();
        data.addProperty("version", Bukkit.getVersion());
        JsonArray worlds = new JsonArray();
        for (World w : Bukkit.getWorlds()) {
            JsonObject wo = new JsonObject();
            wo.addProperty("name", w.getName());
            wo.addProperty("time", w.getTime());
            wo.addProperty("storm", w.hasStorm());
            wo.addProperty("thundering", w.isThundering());
            wo.addProperty("players", w.getPlayers().size());
            worlds.add(wo);
        }
        data.add("worlds", worlds);
        data.addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size());
        return data;
    }

    private Material material(String name) {
        Material m = Material.matchMaterial(name);
        if (m == null) throw new IllegalArgumentException("unknown material: " + name);
        return m;
    }

    private JsonObject setBlock(JsonObject args) {
        Location loc = Json.location(args);
        Material mat = material(Json.reqString(args, "material"));
        loc.getBlock().setType(mat);
        return null;
    }

    private JsonObject getBlock(JsonObject args) {
        Location loc = Json.location(args);
        Block b = loc.getBlock();
        JsonObject data = new JsonObject();
        data.addProperty("material", b.getType().getKey().getKey());
        data.addProperty("blockData", b.getBlockData().getAsString());
        return data;
    }

    private long fillCap() {
        return plugin.getConfig().getLong("max-fill-volume", 100000);
    }

    private JsonObject fillRegion(JsonObject args) {
        World world = Json.world(args);
        int x1 = (int) Json.reqDouble(args, "x1"), y1 = (int) Json.reqDouble(args, "y1"), z1 = (int) Json.reqDouble(args, "z1");
        int x2 = (int) Json.reqDouble(args, "x2"), y2 = (int) Json.reqDouble(args, "y2"), z2 = (int) Json.reqDouble(args, "z2");
        Material mat = material(Json.reqString(args, "material"));
        boolean hollow = Json.optBool(args, "hollow", false);

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        long cap = fillCap();
        if (volume > cap) {
            throw new IllegalArgumentException("region too large: " + volume + " blocks (cap " + cap + ")");
        }
        long changed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (hollow && x != minX && x != maxX && y != minY && y != maxY && z != minZ && z != maxZ) {
                        continue;
                    }
                    world.getBlockAt(x, y, z).setType(mat, false);
                    changed++;
                }
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("blocksChanged", changed);
        return data;
    }

    private JsonObject setWeather(JsonObject args) {
        World world = Json.world(args);
        String weather = Json.reqString(args, "weather").toLowerCase(Locale.ROOT);
        switch (weather) {
            case "clear" -> { world.setStorm(false); world.setThundering(false); }
            case "rain" -> { world.setStorm(true); world.setThundering(false); }
            case "thunder" -> { world.setStorm(true); world.setThundering(true); }
            default -> throw new IllegalArgumentException("weather must be clear|rain|thunder");
        }
        return null;
    }

    private JsonObject spawnEntity(JsonObject args) {
        Location loc = Json.location(args);
        EntityType type;
        try {
            type = EntityType.valueOf(Json.reqString(args, "type").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown entity type");
        }
        loc.getChunk().load();
        Entity entity = loc.getWorld().spawnEntity(loc, type);
        return Json.entityJson(entity);
    }

    private JsonObject removeEntity(JsonObject args) {
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(Json.reqString(args, "uuid"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid uuid");
        }
        Entity entity = Bukkit.getEntity(uuid);
        if (entity == null) throw new IllegalArgumentException("no loaded entity with uuid " + uuid);
        if (entity instanceof org.bukkit.entity.Player) throw new IllegalArgumentException("cannot remove players");
        entity.remove();
        return null;
    }

    private JsonObject spawnParticles(JsonObject args) {
        Location loc = Json.location(args);
        org.bukkit.Particle particle;
        try {
            particle = org.bukkit.Particle.valueOf(Json.reqString(args, "particle").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown particle");
        }
        int count = Json.optInt(args, "count", 20);
        double spread = Json.optDouble(args, "spread", 0.5);
        loc.getWorld().spawnParticle(particle, loc, count, spread, spread, spread, 0.02);
        return null;
    }

    private JsonObject playSound(JsonObject args) {
        Location loc = Json.location(args);
        String sound = Json.reqString(args, "sound");
        float volume = (float) Json.optDouble(args, "volume", 1.0);
        float pitch = (float) Json.optDouble(args, "pitch", 1.0);
        loc.getWorld().playSound(loc, sound, volume, pitch);
        return null;
    }

    private JsonObject sampleTerrain(JsonObject args) {
        World world = Json.world(args);
        int x1 = (int) Json.reqDouble(args, "x1"), z1 = (int) Json.reqDouble(args, "z1");
        int x2 = (int) Json.reqDouble(args, "x2"), z2 = (int) Json.reqDouble(args, "z2");
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        return TerrainSampler.sampleRect(world, minX, minZ, maxX, maxZ);
    }

    private JsonObject scanArea(JsonObject args) {
        World world = Json.world(args);
        int x1 = (int) Json.reqDouble(args, "x1"), z1 = (int) Json.reqDouble(args, "z1");
        int x2 = (int) Json.reqDouble(args, "x2"), z2 = (int) Json.reqDouble(args, "z2");
        Integer yHint = args.has("yHint") && !args.get("yHint").isJsonNull()
                ? (int) args.get("yHint").getAsDouble() : null;
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        return TerrainSampler.scanArea(world, minX, minZ, maxX, maxZ, yHint);
    }

    /** Paste a blueprint: a JSON array of {dx,dy,dz,material,[data]} relative to an origin.
     *  Optional {@code settle: "surface"} shifts the paste so the blueprint's lowest layer
     *  sits on the actual terrain instead of hovering/sinking; optional {@code clearAbove:
     *  true} clears the blueprint's bounding box to air first so trees/hillside can't
     *  interpenetrate the build. */
    private JsonObject buildBlueprint(JsonObject args) {
        Location origin = Json.location(args);
        JsonArray blocks = args.has("blocks") && args.get("blocks").isJsonArray()
                ? args.getAsJsonArray("blocks") : null;
        List<BlueprintParser.BlockEntry> entries = BlueprintParser.parse(blocks, (int) fillCap(), BLUEPRINT_MAX_OFFSET);

        World world = origin.getWorld();
        int originX = origin.getBlockX();
        int originZ = origin.getBlockZ();
        int originY = origin.getBlockY();

        if ("surface".equalsIgnoreCase(Json.optString(args, "settle", null))) {
            originY += settleShift(world, originX, originY, originZ, entries);
        }

        int minDx = Integer.MAX_VALUE, maxDx = Integer.MIN_VALUE;
        int minDy = Integer.MAX_VALUE, maxDy = Integer.MIN_VALUE;
        int minDz = Integer.MAX_VALUE, maxDz = Integer.MIN_VALUE;
        for (BlueprintParser.BlockEntry e : entries) {
            minDx = Math.min(minDx, e.dx()); maxDx = Math.max(maxDx, e.dx());
            minDy = Math.min(minDy, e.dy()); maxDy = Math.max(maxDy, e.dy());
            minDz = Math.min(minDz, e.dz()); maxDz = Math.max(maxDz, e.dz());
        }

        if (Json.optBool(args, "clearAbove", false)) {
            clearBoundingBox(world,
                    originX + minDx, originY + minDy, originZ + minDz,
                    originX + maxDx, originY + maxDy, originZ + maxDz);
        }

        int placed = 0;
        for (BlueprintParser.BlockEntry entry : entries) {
            Material mat = material(entry.material());
            Block block = world.getBlockAt(originX + entry.dx(), originY + entry.dy(), originZ + entry.dz());
            if (entry.data() != null && !entry.data().isBlank()) {
                BlockData bd = Bukkit.createBlockData(mat, entry.data());
                block.setBlockData(bd, false);
            } else {
                block.setType(mat, false);
            }
            placed++;
        }
        JsonObject out = new JsonObject();
        out.addProperty("blocksPlaced", placed);
        return out;
    }

    /** How far to shift originY so the blueprint's lowest dy layer sits on the median
     *  terrain surface under its footprint, rather than hovering above or sinking into it. */
    private int settleShift(World world, int originX, int originY, int originZ, List<BlueprintParser.BlockEntry> entries) {
        int minDy = entries.stream().mapToInt(BlueprintParser.BlockEntry::dy).min().orElse(0);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<int[]> footprint = new ArrayList<>();
        for (BlueprintParser.BlockEntry e : entries) {
            if (e.dy() != minDy) continue;
            long key = (((long) e.dx()) << 32) ^ (e.dz() & 0xffffffffL);
            if (seen.add(key)) footprint.add(new int[]{e.dx(), e.dz()});
        }
        int[] surfaces = new int[footprint.size()];
        for (int i = 0; i < footprint.size(); i++) {
            int x = originX + footprint.get(i)[0];
            int z = originZ + footprint.get(i)[1];
            surfaces[i] = TerrainSampler.surfaceY(world, x, z, originY);
        }
        int[] sorted = surfaces.clone();
        Arrays.sort(sorted);
        int medianSurfaceY = sorted.length % 2 == 0
                ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2
                : sorted[sorted.length / 2];
        int currentLowestWorldY = originY + minDy;
        return medianSurfaceY - currentLowestWorldY;
    }

    private void clearBoundingBox(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        long cap = fillCap();
        if (volume > cap) {
            throw new IllegalArgumentException("clearAbove bounding box too large: " + volume + " blocks (cap " + cap + ")");
        }
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }
}
