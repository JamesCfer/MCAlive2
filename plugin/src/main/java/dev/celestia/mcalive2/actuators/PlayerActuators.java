package dev.celestia.mcalive2.actuators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import dev.celestia.mcalive2.util.Json;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Locale;

/**
 * Player-facing actuators. Deliberately narrow: no broadcast/title/actionbar narration
 * tools and no set_time (see DESIGN.md — chat is the only dialogue channel and the
 * director never touches the clock).
 */
public class PlayerActuators {

    private final MCAlive2Plugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public PlayerActuators(MCAlive2Plugin plugin) {
        this.plugin = plugin;
    }

    public void register(CommandDispatcher d) {
        d.register("list_players", this::listPlayers);
        d.register("give_item", this::giveItem);
        d.register("apply_effect", this::applyEffect);
    }

    private Player requirePlayer(JsonObject args) {
        String name = Json.reqString(args, "player");
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) throw new IllegalArgumentException("player not online: " + name);
        return p;
    }

    private JsonObject listPlayers(JsonObject args) {
        JsonArray arr = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName());
            o.add("location", Json.locationJson(p.getLocation()));
            o.addProperty("health", p.getHealth());
            o.addProperty("foodLevel", p.getFoodLevel());
            o.addProperty("level", p.getLevel());
            o.addProperty("gameMode", p.getGameMode().name());
            ItemStack hand = p.getInventory().getItemInMainHand();
            o.addProperty("heldItem", hand.getType().getKey().getKey());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("players", arr);
        return data;
    }

    /** displayName/lore support MiniMessage formatting (e.g. <gold>, <bold>). */
    private JsonObject giveItem(JsonObject args) {
        Player p = requirePlayer(args);
        Material mat = Material.matchMaterial(Json.reqString(args, "material"));
        if (mat == null) throw new IllegalArgumentException("unknown material");
        int amount = Json.optInt(args, "amount", 1);
        ItemStack item = new ItemStack(mat, amount);
        String displayName = Json.optString(args, "displayName", null);
        String lore = Json.optString(args, "lore", null);
        if (displayName != null || lore != null) {
            item.editMeta(meta -> {
                if (displayName != null) meta.displayName(mm.deserialize("<!italic>" + displayName));
                if (lore != null) meta.lore(List.of(mm.deserialize("<!italic><gray>" + lore)));
            });
        }
        p.getInventory().addItem(item);
        return null;
    }

    private JsonObject applyEffect(JsonObject args) {
        Player p = requirePlayer(args);
        String name = Json.reqString(args, "effect").toLowerCase(Locale.ROOT);
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(name));
        if (type == null) throw new IllegalArgumentException("unknown effect: " + name);
        int seconds = Json.optInt(args, "seconds", 30);
        int amplifier = Json.optInt(args, "amplifier", 0);
        p.addPotionEffect(new PotionEffect(type, seconds * 20, amplifier));
        return null;
    }
}
