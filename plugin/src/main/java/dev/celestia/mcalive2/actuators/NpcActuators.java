package dev.celestia.mcalive2.actuators;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import dev.celestia.mcalive2.util.Json;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/** Spawning, directing, and voicing NPCs. */
public class NpcActuators {

    private final MCAlive2Plugin plugin;
    private final NpcManager npcs;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public NpcActuators(MCAlive2Plugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    public void register(CommandDispatcher d) {
        d.register("npc_spawn", this::spawn);
        d.register("npc_update", this::update);
        d.register("npc_remove", this::remove);
        d.register("npc_say", this::say);
        d.register("npc_walk_to", this::walkTo);
        d.register("npc_look_at", this::lookAt);
        d.register("npc_equip", this::equip);
        d.register("npc_pose", this::pose);
        d.register("npc_revive", this::revive);
        d.register("npc_head_check", this::headCheck);
    }

    private NpcData require(JsonObject args) {
        String id = Json.reqString(args, "id");
        NpcData data = npcs.get(id);
        if (data == null) throw new IllegalArgumentException("no NPC with id: " + id);
        return data;
    }

    private Entity requireEntity(NpcData data) {
        Entity e = npcs.resolveEntity(data);
        if (e == null) throw new IllegalStateException("NPC entity could not be resolved (dead or unloadable)");
        return e;
    }

    private JsonObject spawn(JsonObject args) {
        String id = Json.reqString(args, "id");
        if (npcs.get(id) != null) throw new IllegalArgumentException("NPC id already exists: " + id);
        NpcData data = new NpcData();
        data.id = id;
        data.name = Json.reqString(args, "name");
        data.entityType = Json.optString(args, "entityType", "MANNEQUIN");
        data.profession = Json.optString(args, "profession", null);
        data.skin = Json.optString(args, "skin", null);
        applyPlaces(data, args);
        Location loc = Json.location(args);
        npcs.spawn(data, loc);
        applySchedule(data, args);
        npcs.save();
        return npcs.toJson(data);
    }

    private void applyPlaces(NpcData data, JsonObject args) {
        if (args.has("home") && args.get("home").isJsonObject()) {
            data.home = Json.location(args.getAsJsonObject("home"));
        }
        if (args.has("work") && args.get("work").isJsonObject()) {
            data.work = Json.location(args.getAsJsonObject("work"));
        }
    }

    private void applySchedule(NpcData data, JsonObject args) {
        if (!args.has("schedule") || !args.get("schedule").isJsonArray()) return;
        data.schedule.clear();
        for (JsonElement e : args.getAsJsonArray("schedule")) {
            data.schedule.add(NpcData.ScheduleEntry.fromJson(e.getAsJsonObject()));
        }
    }

    private JsonObject update(JsonObject args) {
        NpcData data = require(args);
        if (args.has("name")) {
            data.name = Json.reqString(args, "name");
            Entity e = npcs.resolveEntity(data);
            if (e != null) e.customName(Component.text(data.name, NamedTextColor.GOLD));
        }
        boolean respawn = false;
        if (args.has("entityType")) {
            String type = Json.reqString(args, "entityType");
            respawn = !type.equalsIgnoreCase(data.entityType);
            data.entityType = type;
        }
        if (args.has("profession")) {
            data.profession = Json.optString(args, "profession", null);
            respawn = true;
        }
        if (args.has("skin")) {
            data.skin = Json.optString(args, "skin", null);
            respawn = true;
        }
        if (respawn) npcs.respawn(data); // swap the backing entity to match the new look
        applyPlaces(data, args);
        applySchedule(data, args);
        npcs.save();
        return npcs.toJson(data);
    }

    private JsonObject remove(JsonObject args) {
        String id = Json.reqString(args, "id");
        boolean removed = npcs.remove(id);
        if (!removed) throw new IllegalArgumentException("no NPC with id: " + id);
        npcs.save();
        return null;
    }

    /** npc_say is the ONLY dialogue channel: no narration tools exist alongside it. */
    private JsonObject say(JsonObject args) {
        NpcData data = require(args);
        String text = Json.reqString(args, "text");
        Entity entity = requireEntity(data);
        double radius = plugin.getConfig().getDouble("npc-chat-radius", 20.0);

        Component msg = Component.text(data.name, NamedTextColor.GOLD)
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(text, NamedTextColor.WHITE));
        int heard = 0;
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : entity.getWorld().getPlayers()) {
            double d2 = p.getLocation().distanceSquared(entity.getLocation());
            if (d2 <= radius * radius) {
                p.sendMessage(msg);
                heard++;
            }
            if (d2 < best) { best = d2; nearest = p; }
        }
        if (nearest != null && best <= radius * radius) {
            npcs.face(entity, nearest.getLocation());
        }
        JsonObject out = new JsonObject();
        out.addProperty("playersHeard", heard);
        return out;
    }

    private JsonObject walkTo(JsonObject args) {
        NpcData data = require(args);
        Location target = Json.location(args);
        double speed = Json.optDouble(args, "speed", 1.0);
        // pause the daily routine so the walk isn't overridden
        data.manualOverrideUntilMs = System.currentTimeMillis() + (long) (Json.optDouble(args, "holdSeconds", 60) * 1000);
        boolean started = npcs.walkTo(data, target, speed);
        JsonObject out = new JsonObject();
        out.addProperty("pathStarted", started);
        return out;
    }

    private JsonObject lookAt(JsonObject args) {
        NpcData data = require(args);
        Entity entity = requireEntity(data);
        Location target = Json.location(args);
        npcs.face(entity, target);
        return null;
    }

    private JsonObject equip(JsonObject args) {
        NpcData data = require(args);
        Entity entity = requireEntity(data);
        if (!(entity instanceof LivingEntity living)) {
            throw new IllegalStateException("NPC entity type cannot hold equipment");
        }
        EntityEquipment eq = living.getEquipment();
        if (eq == null) throw new IllegalStateException("NPC entity has no equipment slots");
        EquipmentSlot slot;
        try {
            slot = EquipmentSlot.valueOf(Json.reqString(args, "slot").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("slot must be one of " + List.of(EquipmentSlot.values()));
        }
        String materialName = Json.optString(args, "material", "AIR");
        Material mat = Material.matchMaterial(materialName);
        if (mat == null) throw new IllegalArgumentException("unknown material: " + materialName);
        ItemStack item = new ItemStack(mat, Json.optInt(args, "amount", 1));
        String displayName = Json.optString(args, "displayName", null);
        if (displayName != null) {
            item.editMeta(meta -> meta.displayName(mm.deserialize("<!italic>" + displayName)));
        }
        eq.setItem(slot, item);
        return null;
    }

    private JsonObject pose(JsonObject args) {
        NpcData data = require(args);
        Entity entity = requireEntity(data);
        String poseName = Json.reqString(args, "pose").toUpperCase(Locale.ROOT);
        Pose target;
        try {
            target = Pose.valueOf(poseName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown pose: " + poseName);
        }
        if (entity instanceof Mannequin && !Mannequin.validPoses().contains(target)) {
            throw new IllegalArgumentException("pose " + target + " is not valid for a mannequin; valid: " + Mannequin.validPoses());
        }
        entity.setPose(target, false);
        return null;
    }

    private JsonObject revive(JsonObject args) {
        NpcData data = require(args);
        Location at;
        if (args.has("x") && args.has("y") && args.has("z")) {
            at = Json.location(args);
        } else {
            at = data.lastLocation != null ? data.lastLocation
                    : data.home != null ? data.home : data.work;
            if (at == null) throw new IllegalStateException("NPC has no known location to revive at");
            at = at.clone();
        }
        npcs.revive(data, at);
        npcs.save();
        return npcs.toJson(data);
    }

    /** Inspect a player's held item (or a given hotbar slot) for the NPC-head PDC tag,
     *  so the director can verify a revival offering without guesswork. */
    private JsonObject headCheck(JsonObject args) {
        String playerName = Json.reqString(args, "player");
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) throw new IllegalArgumentException("player not online: " + playerName);

        ItemStack item;
        if (args.has("slot")) {
            int slot = Json.optInt(args, "slot", 0);
            if (slot < 0 || slot > 8) throw new IllegalArgumentException("slot must be a hotbar slot 0-8");
            item = player.getInventory().getItem(slot);
        } else {
            item = player.getInventory().getItemInMainHand();
        }

        String npcId = (item != null && item.hasItemMeta())
                ? item.getItemMeta().getPersistentDataContainer().get(npcs.key(), PersistentDataType.STRING)
                : null;

        JsonObject out = new JsonObject();
        out.addProperty("isNpcHead", npcId != null);
        if (npcId != null) {
            out.addProperty("npcId", npcId);
            NpcData data = npcs.get(npcId);
            out.addProperty("npcName", data != null ? data.name : null);
            out.addProperty("dead", data != null && data.dead);
        }
        return out;
    }
}
