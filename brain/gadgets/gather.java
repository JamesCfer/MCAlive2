package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Call everybody to spawn at once.
 *
 * (2026-08-25, James: "i want all npc's to be teleported to spawn, they can then go back
 * to wherever. I want players to be teleported there too at the same time as they are all
 * moved there.")
 *
 * This is the ONE place anything in MCAlive2 moves a body without walking it, and it is
 * deliberately not a capability anyone has during ordinary life. Hard rule 1 stands:
 * NPCs never teleport, they pathfind and walk at a player's pace. What this is instead is
 * an operator calling a gathering - a thing that happens TO the world, from outside it,
 * the way weather or an update restart does. Nothing in the simulation can invoke it: it
 * is not on the director's tool list and not on the actor's, and there is no timer. It
 * happens when somebody at a keyboard asks for it.
 *
 * Afterwards nobody is told to stay. Jobs are left exactly as they were, so the walk back
 * to whatever they were doing starts on the next beat and takes as long as walking takes.
 *
 * The one thing that must happen before the move is cancelling any walk in flight.
 * gadget:navigate steps a body along a path one tick at a time by teleporting it to the
 * next foothold; a walk whose path was computed four hundred blocks away would spend the
 * next second dragging its owner straight back out of the square.
 *
 *   gadget:gather {action:"now"}                 everybody, living NPCs and online players
 *   gadget:gather {action:"now", npcs:false}     players only
 *   gadget:gather {action:"now", players:false}  NPCs only
 *   gadget:gather {action:"where"}               just report the muster point
 */
public class Gather implements GadgetContract {

    /** Bodies stand a block apart, spiralling out from the muster point. */
    private static final int STEP = 1;

    private static boolean standable(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() + 1 || y >= w.getMaxHeight() - 1) return false;
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        Block under = w.getBlockAt(x, y - 1, z);
        if (!feet.isPassable() || !head.isPassable()) return false;
        if (feet.getType() == Material.LAVA || under.getType() == Material.LAVA) return false;
        return !under.isPassable() && under.getType() != Material.WATER;
    }

    /** Ground level at a column, or the muster level if there is nothing to stand on. */
    private static int groundAt(World w, int x, int z, int fallback) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR) + 1;
        for (int t = 0; t <= 6; t++) {
            if (standable(w, x, y + t, z)) return y + t;
            if (standable(w, x, y - t, z)) return y - t;
        }
        return fallback;
    }

    /**
     * n places to stand, spiralling out from the middle. Everybody arriving in one block
     * would suffocate each other and hand the groundskeeper forty faults at once.
     */
    private static List<Location> muster(World w, Location centre, int n) {
        List<Location> out = new ArrayList<Location>();
        int cx = centre.getBlockX(), cz = centre.getBlockZ(), cy = centre.getBlockY();
        int x = 0, z = 0, dx = 0, dz = -1;
        int guard = 0;
        while (out.size() < n && guard++ < 40000) {
            // square spiral: the classic turn-at-the-corners walk
            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int t = dx; dx = -dz; dz = t;
            }
            x += dx; z += dz;
            int px = cx + x * STEP, pz = cz + z * STEP;
            int py = groundAt(w, px, pz, cy);
            if (standable(w, px, py, pz)) {
                out.add(new Location(w, px + 0.5, py, pz + 0.5));
            }
        }
        return out;
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "now";
        World w = ctx.world(null);
        Location spawn = w.getSpawnLocation();
        int sy = groundAt(w, spawn.getBlockX(), spawn.getBlockZ(), spawn.getBlockY());
        Location centre = new Location(w, spawn.getBlockX(), sy, spawn.getBlockZ());

        JsonObject out = new JsonObject();
        JsonObject at = new JsonObject();
        at.addProperty("x", centre.getBlockX());
        at.addProperty("y", centre.getBlockY());
        at.addProperty("z", centre.getBlockZ());
        at.addProperty("world", w.getName());
        out.add("at", at);
        if (action.equals("where")) return out;
        if (!action.equals("now")) {
            throw new IllegalArgumentException("gadget:gather has no action \"" + action + "\" (now, where)");
        }

        boolean doNpcs = !args.has("npcs") || args.get("npcs").getAsBoolean();
        boolean doPlayers = !args.has("players") || args.get("players").getAsBoolean();

        NpcManager npcs = ctx.plugin().npcManager();
        List<Entity> bodies = new ArrayList<Entity>();
        List<String> names = new ArrayList<String>();
        if (doNpcs) {
            for (NpcData d : npcs.all()) {
                if (d == null || d.dead) continue;
                Entity e;
                try { e = npcs.resolveEntity(d); } catch (Throwable t) { continue; }
                if (e == null || !e.isValid()) continue;
                // Stop the walk BEFORE the move, or its next step drags them back out.
                try {
                    JsonObject stop = new JsonObject();
                    stop.addProperty("action", "stop");
                    stop.addProperty("npcId", d.id);
                    ctx.invoke("gadget:navigate", stop);
                } catch (Throwable ignored) { }
                bodies.add(e);
                names.add(d.name == null ? d.id : d.name);
            }
        }
        List<Player> people = new ArrayList<Player>();
        if (doPlayers) {
            for (Player p : ctx.server().getOnlinePlayers()) {
                if (p != null && p.isOnline()) people.add(p);
            }
        }

        // One list of spots for everybody, so nobody lands on anybody, and every teleport
        // happens inside this one call - which is one server tick, which is what "at the
        // same time" means here.
        List<Location> spots = muster(w, centre, bodies.size() + people.size() + 4);
        int n = 0;
        JsonArray movedNpcs = new JsonArray();
        for (int i = 0; i < bodies.size(); i++) {
            if (n >= spots.size()) break;
            Location to = spots.get(n++);
            Entity e = bodies.get(i);
            try {
                Location look = to.clone();
                look.setDirection(centre.toVector().subtract(to.toVector()));
                e.teleport(look);
                movedNpcs.add(names.get(i));
            } catch (Throwable t) {
                n--;   // that spot is still free
            }
        }
        JsonArray movedPlayers = new JsonArray();
        for (Player p : people) {
            if (n >= spots.size()) break;
            Location to = spots.get(n++);
            try {
                p.teleport(to);
                p.playSound(to, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                movedPlayers.add(p.getName());
            } catch (Throwable t) {
                n--;
            }
        }
        try {
            w.spawnParticle(org.bukkit.Particle.END_ROD, centre.clone().add(0.5, 1, 0.5), 120, 3, 1, 3, 0.02);
            w.playSound(centre, org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
        } catch (Throwable ignored) { }

        JsonObject ev = new JsonObject();
        ev.addProperty("npcs", movedNpcs.size());
        ev.addProperty("players", movedPlayers.size());
        ev.add("at", at);
        ctx.plugin().bridge().broadcastEvent("gathered", ev);

        out.add("npcs", movedNpcs);
        out.add("players", movedPlayers);
        out.addProperty("moved", movedNpcs.size() + movedPlayers.size());
        return out;
    }
}
