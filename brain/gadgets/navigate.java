package celestia.gadgets;

import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Walking, the way a player walks.
 *
 * The plugin's own mannequin movement has no pathfinding: it heads in a straight line,
 * climbs one block, and gives up at the first wall - which is why work here used to be
 * done by teleporting bodies around. That is banned. This is the replacement: a real A*
 * search over standing positions, followed along the ground at a player's pace.
 *
 * The rules of the search are a player's rules. You may step up one block, drop three,
 * cross water by swimming, and never pass through anything solid; a diagonal step is
 * refused unless both blocks beside it are open, so nobody clips a corner. If there is
 * no way to somewhere, the answer is "no way" - not a shortcut.
 *
 * Movement is applied as small per-tick position updates, which is how the plugin moves
 * an entity that has no mob AI. That is walking; it is not relocation.
 */
public class Navigator implements GadgetContract {

    private static final Map<String, Walk> WALKS = new HashMap<String, Walk>();
    /** A player walks about 4.3 blocks a second: 0.215 of a block per tick. */
    private static final double WALK_SPEED = 0.215;

    private static class Walk {
        String npcId;
        int taskId;
        List<int[]> path = new ArrayList<int[]>();
        int leg;
        double speed;
        int stuckTicks;
        String stopReason;
        int startedAtLeg;
    }

    // ------------------------------------------------------------ terrain rules
    private static boolean passable(Block b) {
        Material m = b.getType();
        if (m == Material.LAVA) return false;
        return b.isPassable();
    }

    private static boolean solidFooting(Block b) {
        Material m = b.getType();
        if (m == Material.LAVA) return false;
        if (m == Material.WATER) return false;     // water holds nobody up
        return !b.isPassable();
    }

    /**
     * Can an NPC be here - feet and head clear, and either something solid underfoot or
     * water at the feet? A body in water is IN the water, treading at the surface, not
     * walking across the top of it. That is the difference between a swimmer and a saint.
     */
    private static boolean standable(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() + 1 || y >= w.getMaxHeight() - 1) return false;
        Block feet = w.getBlockAt(x, y, z);
        if (!passable(feet)) return false;
        if (!passable(w.getBlockAt(x, y + 1, z))) return false;
        if (feet.getType() == Material.WATER) return true;
        return solidFooting(w.getBlockAt(x, y - 1, z));
    }

    private static boolean swimming(World w, int x, int y, int z) {
        return w.getBlockAt(x, y, z).getType() == Material.WATER;
    }

    /** How far a path may sink below the daylit surface before it is refused. */
    private static final int MAX_DEPTH = 6;
    /** Cost added per block below the surface, so a cave is a detour and not a shortcut. */
    private static final double DEPTH_COST = 2.2;

    /**
     * Ground height per column, cached for the life of one search. Without the cache this
     * is the most expensive thing in A*; with it, staying above ground is nearly free.
     */
    private static int surfaceAt(World w, java.util.HashMap<Long, Integer> cache, int x, int z) {
        long k = (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        Integer v = cache.get(k);
        if (v != null) return v.intValue();
        int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        cache.put(k, Integer.valueOf(y));
        return y;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static class Node {
        int x, y, z;
        double g, f;
        Node parent;
        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    /**
     * A* from one standing position to another. Returns the path, or the best partial
     * path towards the goal when the goal itself cannot be reached - an NPC that can
     * only get halfway should still set off.
     */
    private static List<int[]> findPath(World w, int sx, int sy, int sz, int tx, int ty, int tz,
                                        int maxNodes, boolean allowUnderground) {
        java.util.HashMap<Long, Integer> surf = new java.util.HashMap<Long, Integer>();
        // A route that ends underground is allowed to travel underground - a miner walking
        // down its own shaft - but a walk between two places on the surface must stay on it.
        int targetDepth = surfaceAt(w, surf, tx, tz) - ty;
        boolean underground = allowUnderground || targetDepth > MAX_DEPTH;
        final Node start = new Node(sx, sy, sz);
        start.g = 0;
        start.f = dist(sx, sy, sz, tx, ty, tz);
        PriorityQueue<Node> open = new PriorityQueue<Node>(64, new Comparator<Node>() {
            public int compare(Node a, Node b) { return Double.compare(a.f, b.f); }
        });
        Map<Long, Node> best = new HashMap<Long, Node>();
        Set<Long> closed = new HashSet<Long>();
        open.add(start);
        best.put(key(sx, sy, sz), start);

        Node nearest = start;
        double nearestD = start.f;
        int examined = 0;
        int[][] dirs = new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

        while (!open.isEmpty() && examined < maxNodes) {
            Node cur = open.poll();
            long ck = key(cur.x, cur.y, cur.z);
            if (closed.contains(ck)) continue;
            closed.add(ck);
            examined++;

            double d = dist(cur.x, cur.y, cur.z, tx, ty, tz);
            if (d < nearestD) { nearestD = d; nearest = cur; }
            if (cur.x == tx && cur.z == tz && Math.abs(cur.y - ty) <= 1) return rebuild(cur);
            if (d <= 1.5) return rebuild(cur);

            for (int[] dir : dirs) {
                int nx = cur.x + dir[0], nz = cur.z + dir[1];
                boolean diagonal = dir[0] != 0 && dir[1] != 0;
                if (diagonal) {
                    // no cutting corners: both sides of the turn must be open
                    if (!standableColumnClear(w, cur.x + dir[0], cur.y, cur.z)
                            || !standableColumnClear(w, cur.x, cur.y, cur.z + dir[1])) continue;
                }
                for (int dy = 1; dy >= -3; dy--) {          // step up one, drop three
                    int ny = cur.y + dy;
                    if (!standable(w, nx, ny, nz)) continue;
                    if (dy == 1 && !passable(w.getBlockAt(cur.x, cur.y + 2, cur.z))) continue; // headroom to climb
                    int depth = surfaceAt(w, surf, nx, nz) - ny;
                    if (!underground && depth > MAX_DEPTH) continue;   // refuse to sink
                    long nk = key(nx, ny, nz);
                    if (closed.contains(nk)) break;
                    double step = (diagonal ? 1.414 : 1.0) + (dy < 0 ? 0.4 * -dy : 0) + (dy > 0 ? 0.5 : 0);
                    // Going under the sky costs. This is what stops a cave mouth scoring as
                    // a shortcut and walking somebody down to bedrock one legal step at a time.
                    if (!underground && depth > 0) step += depth * DEPTH_COST;
                    // swimming is slow, and a swimmer goes round a lake when there is a round
                    if (swimming(w, nx, ny, nz)) step *= 2.2;
                    double ng = cur.g + step;
                    Node prev = best.get(nk);
                    if (prev != null && prev.g <= ng) break;
                    Node next = new Node(nx, ny, nz);
                    next.g = ng;
                    next.f = ng + dist(nx, ny, nz, tx, ty, tz);
                    next.parent = cur;
                    best.put(nk, next);
                    open.add(next);
                    break;                                   // take the first landing found
                }
            }
        }
        return nearest == start ? new ArrayList<int[]>() : rebuild(nearest);
    }

    /** Feet and head clear at this column - used for the corner test on diagonals. */
    private static boolean standableColumnClear(World w, int x, int y, int z) {
        return passable(w.getBlockAt(x, y, z)) && passable(w.getBlockAt(x, y + 1, z));
    }

    private static double dist(int x, int y, int z, int tx, int ty, int tz) {
        double dx = x - tx, dy = y - ty, dz = z - tz;
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.5;
    }

    private static List<int[]> rebuild(Node n) {
        List<int[]> out = new ArrayList<int[]>();
        while (n != null) { out.add(0, new int[]{n.x, n.y, n.z}); n = n.parent; }
        if (!out.isEmpty()) out.remove(0);   // the first node is where we already stand
        return out;
    }

    // ------------------------------------------------------------ the walk itself
    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "walk";
        String npcId = args.has("npcId") ? args.get("npcId").getAsString() : null;

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            Walk wk = WALKS.get(npcId);
            if (wk == null) { out.addProperty("walking", false); return out; }
            out.addProperty("walking", true);
            out.addProperty("legsLeft", wk.path.size() - wk.leg);
            out.addProperty("legsWalked", wk.leg);
            return out;
        }
        if (action.equals("stop")) {
            Walk wk = WALKS.get(npcId);
            if (wk != null) wk.stopReason = "stopped";
            JsonObject out = new JsonObject();
            out.addProperty("stopping", wk != null);
            return out;
        }
        if (action.equals("busy")) {
            JsonObject out = new JsonObject();
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (String id : WALKS.keySet()) a.add(id);
            out.add("walking", a);
            return out;
        }

        NpcManager npcs = ctx.plugin().npcManager();
        NpcData data = npcs.get(npcId);
        if (data == null) throw new IllegalArgumentException("no NPC with id: " + npcId);
        Entity entity = npcs.resolveEntity(data);
        if (entity == null) throw new IllegalStateException("NPC entity could not be resolved: " + npcId);
        World world = entity.getWorld();

        JsonObject to = args.getAsJsonObject("to");
        int tx = to.get("x").getAsInt(), ty = to.get("y").getAsInt(), tz = to.get("z").getAsInt();
        Location at = entity.getLocation();
        int sx = at.getBlockX(), sy = at.getBlockY(), sz = at.getBlockZ();
        // if the NPC is standing somewhere it should not be, start from the ground under it
        if (!standable(world, sx, sy, sz)) {
            // Look up as well as down. Somebody embedded in a block has the only place it
            // can stand ABOVE it, and searching downward alone found nothing - which left
            // every walk-based rescue unable to start.
            int fixed = Integer.MIN_VALUE;
            for (int r = 0; r <= 4 && fixed == Integer.MIN_VALUE; r++) {
                if (standable(world, sx, sy + r, sz)) fixed = sy + r;
                else if (standable(world, sx, sy - r, sz)) fixed = sy - r;
            }
            if (fixed != Integer.MIN_VALUE) sy = fixed;
        }
        int maxNodes = args.has("maxNodes") ? args.get("maxNodes").getAsInt() : 12000;

        Walk wk = WALKS.get(npcId);
        if (wk != null) wk.stopReason = "replaced";
        Walk fresh = new Walk();
        fresh.npcId = npcId;
        fresh.speed = args.has("speed") ? args.get("speed").getAsDouble() : WALK_SPEED;
        boolean allowUnderground = args.has("underground") && args.get("underground").getAsBoolean();
        fresh.path = findPath(world, sx, sy, sz, tx, ty, tz, maxNodes, allowUnderground);

        JsonObject out = new JsonObject();
        if (fresh.path.isEmpty()) {
            out.addProperty("started", false);
            out.addProperty("reason", "no way there on foot");
            return out;
        }
        int[] end = fresh.path.get(fresh.path.size() - 1);
        final Walk walk = fresh;
        walk.taskId = ctx.runTimer(1, new Runnable() {
            public void run() { step(ctx, walk, world); }
        });
        WALKS.put(npcId, walk);

        out.addProperty("started", true);
        out.addProperty("legs", walk.path.size());
        out.addProperty("endsAt", end[0] + "," + end[1] + "," + end[2]);
        out.addProperty("reachesGoal", end[0] == tx && end[2] == tz);
        out.addProperty("etaSeconds", Math.round(walk.path.size() / (walk.speed * 20.0)));
        return out;
    }

    private void step(GadgetContext ctx, Walk wk, World world) {
        try {
            NpcManager npcs = ctx.plugin().npcManager();
            NpcData d = npcs.get(wk.npcId);
            Entity e = d == null ? null : npcs.resolveEntity(d);
            if (e == null) { finish(ctx, wk, "npc_gone"); return; }
            if (wk.stopReason != null) { finish(ctx, wk, wk.stopReason); return; }
            if (wk.leg >= wk.path.size()) { finish(ctx, wk, "arrived"); return; }

            int[] target = wk.path.get(wk.leg);
            Location cur = e.getLocation();
            double tx = target[0] + 0.5, ty = target[1], tz = target[2] + 0.5;
            double dx = tx - cur.getX(), dz = tz - cur.getZ();
            double flat = Math.sqrt(dx * dx + dz * dz);

            if (flat < 0.25 && Math.abs(ty - cur.getY()) < 0.6) {
                wk.leg++;
                wk.stuckTicks = 0;
                d.lastLocation = cur.clone();
                return;
            }
            if (++wk.stuckTicks > 100) { finish(ctx, wk, "stuck"); return; }

            double sp = swimming(world, cur.getBlockX(), cur.getBlockY(), cur.getBlockZ()) ? wk.speed * 0.45 : wk.speed;
            double moveX = flat > 0 ? dx / flat * sp : 0;
            double moveZ = flat > 0 ? dz / flat * sp : 0;
            double nx = cur.getX() + moveX, nz = cur.getZ() + moveZ;
            // rise or fall towards the next foothold rather than snapping to it
            double dy = ty - cur.getY();
            double ny = cur.getY() + Math.max(-0.5, Math.min(0.35, dy));

            Location next = new Location(world, nx, ny, nz);
            // Settle: never end a tick inside a block. Interpolating towards the next
            // foothold can clip a rising slope, and an NPC left embedded is exactly the
            // fault the groundskeeper has been bailing out thousands of times.
            for (int lift = 0; lift < 4; lift++) {
                if (passable(world.getBlockAt(next.getBlockX(), next.getBlockY(), next.getBlockZ()))
                        && passable(world.getBlockAt(next.getBlockX(), next.getBlockY() + 1, next.getBlockZ()))) break;
                next.setY(next.getY() + 1);
            }
            // Gravity. Interpolating towards the next foothold descends slower than the
            // ground falls away, so an NPC crossing a drop ends the tick in mid-air and
            // hangs there. Fall to the first footing under it, as a body would.
            int fell = 0;
            while (fell < 4
                    && !swimming(world, next.getBlockX(), next.getBlockY(), next.getBlockZ())
                    && !solidFooting(world.getBlockAt(next.getBlockX(), next.getBlockY() - 1, next.getBlockZ()))
                    && passable(world.getBlockAt(next.getBlockX(), next.getBlockY() - 1, next.getBlockZ()))) {
                next.setY(next.getY() - 1);
                fell++;
            }
            next.setDirection(new Vector(dx, 0, dz));
            e.teleport(next);      // per-tick step: how the plugin moves an AI-less body
        } catch (Throwable t) {
            finish(ctx, wk, "error: " + t.getClass().getSimpleName());
        }
    }

    private void finish(GadgetContext ctx, Walk wk, String reason) {
        ctx.cancelTask(wk.taskId);
        Walk cur = WALKS.get(wk.npcId);
        if (cur == wk) WALKS.remove(wk.npcId);
        JsonObject ev = new JsonObject();
        ev.addProperty("npcId", wk.npcId);
        ev.addProperty("reason", reason);
        ev.addProperty("legsWalked", wk.leg);
        ctx.plugin().bridge().broadcastEvent("npc_walk_done", ev);
    }
}
