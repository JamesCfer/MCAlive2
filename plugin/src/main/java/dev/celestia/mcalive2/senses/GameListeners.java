package dev.celestia.mcalive2.senses;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.actuators.LedgerActuators;
import dev.celestia.mcalive2.bridge.BridgeServer;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import dev.celestia.mcalive2.util.Json;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Forwards the interesting things happening in the world to the brain as pushed events. */
public class GameListeners implements Listener {

    private static final long HEAD_TAKEN_COOLDOWN_MS = 60_000;

    private final MCAlive2Plugin plugin;
    private final NpcManager npcs;
    private final BridgeServer bridge;
    private final LedgerActuators ledger;
    private final ExploredTracker explored;
    /** "<playerUuid>:<npcId>" -> last fire time, so a player re-dropping/re-picking a
     *  head doesn't spam the director with the same npc_head_taken event. */
    private final Map<String, Long> headTakenCooldown = new ConcurrentHashMap<>();

    public GameListeners(MCAlive2Plugin plugin, NpcManager npcs, BridgeServer bridge,
                          LedgerActuators ledger, ExploredTracker explored) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.bridge = bridge;
        this.ledger = ledger;
        this.explored = explored;
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        // adopt or delete NPC-tagged entities as their chunks stream in, so
        // respawn logic can never leave duplicates behind
        npcs.sweepEntities(event.getEntities());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        // AsyncChatEvent fires off the main thread; text serialization is safe here, but
        // location/entity lookups (the nearest-NPC search) are not, so hop to a sync task
        // before touching Bukkit world/entity state and build+broadcast the payload there.
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            JsonObject data = new JsonObject();
            data.addProperty("player", player.getName());
            data.addProperty("message", message);
            Location loc = player.getLocation();
            data.add("location", Json.locationJson(loc));
            NpcData nearest = nearestNpc(loc);
            if (nearest != null) {
                data.addProperty("nearNpcId", nearest.id);
                data.addProperty("nearNpcName", nearest.name);
            }
            bridge.broadcastEvent("player_chat", data);
        });
    }

    /** The nearest living NPC to a location within npc-chat-range blocks, or null if none. */
    private NpcData nearestNpc(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        double range = plugin.getConfig().getDouble("npc-chat-range", 8);
        double bestDistSq = range * range;
        NpcData nearest = null;
        for (NpcData npc : npcs.all()) {
            if (npc.dead) continue;
            Entity entity = npcs.resolveEntity(npc);
            if (entity == null || !entity.isValid()) continue;
            Location npcLoc = entity.getLocation();
            if (npcLoc.getWorld() != loc.getWorld()) continue;
            double distSq = npcLoc.distanceSquared(loc);
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                nearest = npc;
            }
        }
        return nearest;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getPlayer().getName());
        data.add("location", Json.locationJson(event.getPlayer().getLocation()));
        bridge.broadcastEvent("player_join", data);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getPlayer().getName());
        bridge.broadcastEvent("player_quit", data);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return; // only bother checking cells when the player actually crosses a block
        }
        JsonObject data = explored.checkExplored(event.getPlayer());
        if (data != null) {
            bridge.broadcastEvent("player_explored", data);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        NpcData npc = npcs.byEntity(event.getRightClicked());
        if (npc == null) return;
        event.setCancelled(true); // don't open villager trades etc.
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getPlayer().getName());
        data.addProperty("npcId", npc.id);
        data.addProperty("npcName", npc.name);
        bridge.broadcastEvent("npc_interact", data);
    }

    /** A player picking up a dead NPC's memorial head - the revival token. */
    @EventHandler(ignoreCancelled = true)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack stack = event.getItem().getItemStack();
        if (!stack.hasItemMeta()) return;
        String npcId = stack.getItemMeta().getPersistentDataContainer().get(npcs.key(), PersistentDataType.STRING);
        if (npcId == null) return;

        String cooldownKey = player.getUniqueId() + ":" + npcId;
        long now = System.currentTimeMillis();
        Long last = headTakenCooldown.get(cooldownKey);
        if (last != null && now - last < HEAD_TAKEN_COOLDOWN_MS) return;
        headTakenCooldown.put(cooldownKey, now);

        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("npcId", npcId);
        NpcData npc = npcs.get(npcId);
        data.addProperty("npcName", npc != null ? npc.name : null);
        data.add("location", Json.locationJson(player.getLocation()));
        bridge.broadcastEvent("npc_head_taken", data);
    }

    /**
     * NPCs walk their routines all day unattended; terrain mishaps should not quietly
     * kill the cast. Deliberate harm from players and mobs still lands.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNpcEnvironmentalDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) return;
        if (npcs.byEntity(event.getEntity()) == null) return;
        switch (event.getCause()) {
            case FALL, SUFFOCATION, DROWNING, CRAMMING, FLY_INTO_WALL, CONTACT, HOT_FLOOR, VOID ->
                    event.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler
    public void onNpcDamaged(EntityDamageByEntityEvent event) {
        NpcData npc = npcs.byEntity(event.getEntity());
        if (npc == null) return;
        if (!(event.getDamager() instanceof Player player)) return;
        JsonObject data = new JsonObject();
        data.addProperty("player", player.getName());
        data.addProperty("npcId", npc.id);
        data.addProperty("npcName", npc.name);
        data.addProperty("damage", event.getFinalDamage());
        bridge.broadcastEvent("npc_attacked", data);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        NpcData npc = npcs.byEntity(event.getEntity());
        if (npc == null) return;
        // permadeath: never auto-respawn, drop a memorial head, mark dead everywhere.
        npcs.markDead(npc);
        event.getDrops().clear();
        event.getDrops().add(npcs.headOf(npc));
        ledger.markNpcDeadIfPresent(npc.id, npc.diedAt);

        JsonObject data = new JsonObject();
        data.addProperty("npcId", npc.id);
        data.addProperty("npcName", npc.name);
        data.add("location", Json.locationJson(event.getEntity().getLocation()));
        Player killer = event.getEntity().getKiller();
        if (killer != null) data.addProperty("killer", killer.getName());
        bridge.broadcastEvent("npc_death", data);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("player", event.getPlayer().getName());
        data.addProperty("cause", event.getEntity().getLastDamageCause() == null ? "unknown"
                : event.getEntity().getLastDamageCause().getCause().name());
        data.addProperty("deathMessage", event.deathMessage() == null ? ""
                : PlainTextComponentSerializer.plainText().serialize(event.deathMessage()));
        data.add("location", Json.locationJson(event.getPlayer().getLocation()));
        bridge.broadcastEvent("player_death", data);
    }
}
