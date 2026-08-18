package dev.celestia.estari.actuators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.estari.EstariPlugin;
import dev.celestia.estari.bridge.CommandDispatcher;
import dev.celestia.estari.ledger.BlueprintParser;
import dev.celestia.estari.senses.TerrainSampler;
import dev.celestia.estari.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Locale;

/** Block editing, world state, entity spawning, terrain sampling, and blueprint pasting. */
public class WorldActuators {

    /** Cap on |dx|,|dy|,|dz| for a single build_blueprint entry, independent of the block-count cap. */
    private static final int BLUEPRINT_MAX_OFFSET = 256;

    private final EstariPlugin plugin;

    public WorldActuators(EstariPlugin plugin) {
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

    /** Paste a blueprint: a JSON array of {dx,dy,dz,material,[data]} relative to an origin. */
    private JsonObject buildBlueprint(JsonObject args) {
        Location origin = Json.location(args);
        JsonArray blocks = args.has("blocks") && args.get("blocks").isJsonArray()
                ? args.getAsJsonArray("blocks") : null;
        List<BlueprintParser.BlockEntry> entries = BlueprintParser.parse(blocks, (int) fillCap(), BLUEPRINT_MAX_OFFSET);

        World world = origin.getWorld();
        int placed = 0;
        for (BlueprintParser.BlockEntry entry : entries) {
            Material mat = material(entry.material());
            Block block = world.getBlockAt(
                    origin.getBlockX() + entry.dx(),
                    origin.getBlockY() + entry.dy(),
                    origin.getBlockZ() + entry.dz());
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
}
