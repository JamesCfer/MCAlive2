package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Land. Every block somebody places stakes the columns around it (Chebyshev radius 3)
 * to its owner - an NPC ("npc:ada"), a player ("player:James") or a village
 * ("village:v1"). First claim wins; nobody can claim over somebody else. Village land is
 * common ground (inn, market - strangers are welcome), so it blocks BUILDING by others
 * but never counts walking as trespass. Personal land does both:
 *
 *  - a stranger who steps onto it is warned by the owner (npc_say, if the owner is an
 *    NPC nearby-enough to matter), and if they linger a claim_trespass event goes to
 *    the brain and the owner remembers it (hostility count in the ledger, an alert on
 *    the owner's sheet that gadget:people turns into a confrontation);
 *  - placing or breaking a block on it is an immediate claim_violation event, a bigger
 *    hostility bump, and the same alert.
 *
 * Player placements are heard through a real BlockPlaceEvent listener; NPC placements
 * are staked by gadget:people when it builds. Everything persists in the "claims"
 * ledger collection (one record per owner, cols as a flat [x,z,x,z...] array, plus a
 * "hostility" record of pair counts).
 *
 * Actions: start (default), stop, status, stake {owner,x,z[,r]} or
 * {owner,x1,z1,x2,z2[,r]}, owner_of {x,z}, check_rect {x1,z1,x2,z2[,owner]},
 * release {owner}, hostility.
 */
public class Claims implements GadgetContract {

    private static final int RADIUS = 3;              // the barrier around every placed block
    private static final int MAX_COLS_PER_OWNER = 8000;
    private static final long WARN_COOLDOWN_MS = 120_000;
    private static final long EVENT_COOLDOWN_MS = 300_000;
    private static final long VIOLATION_COOLDOWN_MS = 60_000;
    private static final int TRESPASS_AFTER_SEC = 10; // linger this long -> event
    private static final int PERIOD_TICKS = 40;       // trespass sweep every 2 s
    private static final int SAVE_EVERY_BEATS = 15;   // flush dirty owners every ~30 s

    private static Integer TASK_ID = null;
    private static boolean LOADED = false;
    private static int beats = 0;

    private static final Map<Long, String> COLS = new HashMap<Long, String>();
    private static final Map<String, Set<Long>> BY_OWNER = new HashMap<String, Set<Long>>();
    private static final Set<String> DIRTY = new HashSet<String>();
    private static JsonObject HOSTILITY = new JsonObject();   // pairKey -> {count,last}
    private static boolean HOSTILITY_DIRTY = false;

    private static final Map<String, Integer> DWELL = new HashMap<String, Integer>();   // pairKey -> seconds on the land
    private static final Map<String, Long> WARNED = new HashMap<String, Long>();
    private static final Map<String, Long> EVENTED = new HashMap<String, Long>();
    private static Map<String, String> NPC_VILLAGE = new HashMap<String, String>();     // npcId -> villageId
    private static long villagesFreshAt = 0;
    private static String lastError = null;

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    // ------------------------------------------------------------------ timer plumbing

    private static int generation(GadgetContext ctx, boolean bump) {
        org.bukkit.World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("claims-generation");
        Integer cur = pdc.get(k, org.bukkit.persistence.PersistentDataType.INTEGER);
        int g = cur == null ? 0 : cur.intValue();
        if (bump) { g = g + 1; pdc.set(k, org.bukkit.persistence.PersistentDataType.INTEGER, Integer.valueOf(g)); }
        return g;
    }

    private static int reapTimers(GadgetContext ctx) {
        int killed = 0;
        for (org.bukkit.scheduler.BukkitTask t : ctx.server().getScheduler().getPendingTasks()) {
            if (t.getOwner() != ctx.plugin()) continue;
            Object inner = runnableOf(t);
            if (inner != null && inner.getClass().getName().contains("Claims")) { t.cancel(); killed++; }
        }
        return killed;
    }

    private static Object runnableOf(Object task) {
        Class<?> c = task.getClass();
        while (c != null) {
            java.lang.reflect.Field[] fs = c.getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                if (!Runnable.class.isAssignableFrom(fs[i].getType())) continue;
                try {
                    fs[i].setAccessible(true);
                    Object v = fs[i].get(task);
                    if (v != null && v != task) return v;
                } catch (Throwable ignored) { }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** A redefine loads a new class but the old listener stays registered - find every
     *  ClaimsEars from any generation by class name and unregister it. */
    private static int reapListeners() {
        int killed = 0;
        HandlerList[] lists = { BlockPlaceEvent.getHandlerList(), BlockBreakEvent.getHandlerList() };
        for (HandlerList hl : lists) {
            for (org.bukkit.plugin.RegisteredListener rl : hl.getRegisteredListeners()) {
                if (rl.getListener().getClass().getName().contains("ClaimsEars")) {
                    hl.unregister(rl.getListener());
                    killed++;
                }
            }
        }
        return killed;
    }

    // ------------------------------------------------------------------ json helpers

    private static int geti(JsonObject o, String k, int dflt) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : dflt;
    }

    private static String gets(JsonObject o, String k, String dflt) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : dflt;
    }

    // ------------------------------------------------------------------ persistence
    //
    // The ledger's collections are fixed (and facts feed actor prompts), so the map
    // lives as one JSON string in the world's persistent data:
    //   { "owners": { owner: [x,z,x,z...] }, "hostility": { "who|owner": {count,last} } }

    private static org.bukkit.persistence.PersistentDataContainer pdc(GadgetContext ctx) {
        return ctx.server().getWorlds().get(0).getPersistentDataContainer();
    }

    private static void load(GadgetContext ctx) {
        if (LOADED) return;
        COLS.clear();
        BY_OWNER.clear();
        HOSTILITY = new JsonObject();
        try {
            String s = pdc(ctx).get(ctx.key("claims-data"), org.bukkit.persistence.PersistentDataType.STRING);
            if (s != null && !s.isEmpty()) {
                JsonObject root = com.google.gson.JsonParser.parseString(s).getAsJsonObject();
                if (root.has("hostility") && root.get("hostility").isJsonObject()) HOSTILITY = root.getAsJsonObject("hostility");
                JsonObject owners = root.has("owners") && root.get("owners").isJsonObject() ? root.getAsJsonObject("owners") : new JsonObject();
                for (Map.Entry<String, JsonElement> e : owners.entrySet()) {
                    if (!e.getValue().isJsonArray()) continue;
                    JsonArray cols = e.getValue().getAsJsonArray();
                    Set<Long> mine = new HashSet<Long>();
                    for (int i = 0; i + 1 < cols.size(); i += 2) {
                        long key = pack(cols.get(i).getAsInt(), cols.get(i + 1).getAsInt());
                        COLS.put(Long.valueOf(key), e.getKey());
                        mine.add(Long.valueOf(key));
                    }
                    BY_OWNER.put(e.getKey(), mine);
                }
            }
        } catch (Throwable t) {
            lastError = "load: " + t;
        }
        LOADED = true;
    }

    private static void flush(GadgetContext ctx) {
        if (DIRTY.isEmpty() && !HOSTILITY_DIRTY) return;
        try {
            JsonObject owners = new JsonObject();
            for (Map.Entry<String, Set<Long>> e : BY_OWNER.entrySet()) {
                JsonArray cols = new JsonArray();
                for (Long key : e.getValue()) {
                    cols.add((int) (key.longValue() >> 32));
                    cols.add((int) key.longValue());
                }
                owners.add(e.getKey(), cols);
            }
            JsonObject root = new JsonObject();
            root.add("owners", owners);
            root.add("hostility", HOSTILITY);
            pdc(ctx).set(ctx.key("claims-data"), org.bukkit.persistence.PersistentDataType.STRING, root.toString());
            DIRTY.clear();
            HOSTILITY_DIRTY = false;
        } catch (Throwable t) {
            lastError = "flush: " + t;
        }
    }

    // ------------------------------------------------------------------ the map itself

    /** Claim every free column in the rect grown by r. Never steals: a column already
     *  owned by somebody else is left alone and reported. */
    private static JsonObject stake(String owner, int x1, int z1, int x2, int z2, int r) {
        int added = 0, blocked = 0;
        Set<String> blockedBy = new HashSet<String>();
        Set<Long> mine = BY_OWNER.get(owner);
        if (mine == null) { mine = new HashSet<Long>(); BY_OWNER.put(owner, mine); }
        int lox = Math.min(x1, x2) - r, hix = Math.max(x1, x2) + r;
        int loz = Math.min(z1, z2) - r, hiz = Math.max(z1, z2) + r;
        for (int x = lox; x <= hix; x++) {
            for (int z = loz; z <= hiz; z++) {
                Long key = Long.valueOf(pack(x, z));
                String cur = COLS.get(key);
                if (cur == null) {
                    if (mine.size() >= MAX_COLS_PER_OWNER) continue;
                    COLS.put(key, owner);
                    mine.add(key);
                    added++;
                } else if (!cur.equals(owner)) {
                    blocked++;
                    blockedBy.add(cur);
                }
            }
        }
        if (added > 0) DIRTY.add(owner);
        JsonObject out = new JsonObject();
        out.addProperty("added", added);
        out.addProperty("blocked", blocked);
        JsonArray by = new JsonArray();
        for (String s : blockedBy) by.add(s);
        out.add("blockedBy", by);
        out.addProperty("total", mine.size());
        return out;
    }

    /** Whose land is this column? null string if nobody's. */
    private static String ownerOf(int x, int z) {
        return COLS.get(Long.valueOf(pack(x, z)));
    }

    // ------------------------------------------------------------------ who is who

    private static void refreshVillages(GadgetContext ctx) {
        if (System.currentTimeMillis() < villagesFreshAt) return;
        try {
            JsonObject q = new JsonObject();
            q.addProperty("collection", "npcs");
            JsonArray recs = ctx.invoke("ledger_query", q).getAsJsonArray("records");
            Map<String, String> fresh = new HashMap<String, String>();
            for (JsonElement el : recs) {
                JsonObject rec = el.getAsJsonObject();
                String v = gets(rec, "village", null);
                if (v != null) fresh.put(gets(rec, "id", ""), v);
            }
            NPC_VILLAGE = fresh;
            villagesFreshAt = System.currentTimeMillis() + 30_000;
        } catch (Throwable t) {
            lastError = "villages: " + t;
            villagesFreshAt = System.currentTimeMillis() + 30_000;
        }
    }

    /** May `who` treat `owner`'s land as home ground? */
    private static boolean allied(String who, String owner) {
        if (who.equals(owner)) return true;
        if (owner.startsWith("village:")) {
            // village common ground: members are home, and for WALKING everyone is
            // welcome anyway (handled by the caller); this answers the building question
            String vid = owner.substring("village:".length());
            return who.startsWith("npc:") && vid.equals(NPC_VILLAGE.get(who.substring(4)));
        }
        if (owner.startsWith("npc:") && who.startsWith("npc:")) {
            // fellow villagers walk each other's yards without it being war
            String a = NPC_VILLAGE.get(owner.substring(4));
            String b = NPC_VILLAGE.get(who.substring(4));
            return a != null && a.equals(b);
        }
        return false;
    }

    private static String displayName(String key) {
        int i = key.indexOf(':');
        return i < 0 ? key : key.substring(i + 1);
    }

    // ------------------------------------------------------------------ hostility

    private static void rememberHostility(String intruder, String owner, int amount) {
        String pk = intruder + "|" + owner;
        JsonObject h = HOSTILITY.has(pk) && HOSTILITY.get(pk).isJsonObject()
                ? HOSTILITY.getAsJsonObject(pk) : new JsonObject();
        h.addProperty("count", geti(h, "count", 0) + amount);
        h.addProperty("last", System.currentTimeMillis());
        HOSTILITY.add(pk, h);
        HOSTILITY_DIRTY = true;
    }

    /** Tell the owner's sheet, so gadget:people sends them over to confront. */
    private static void alertOwner(GadgetContext ctx, String owner, String intruderName, Location at) {
        if (!owner.startsWith("npc:")) return;
        try {
            JsonObject q = new JsonObject();
            q.addProperty("collection", "npcs");
            q.addProperty("id", owner.substring(4));
            JsonObject rec = ctx.invoke("ledger_get", q);
            JsonObject alert = new JsonObject();
            alert.addProperty("kind", "trespass");
            alert.addProperty("who", intruderName);
            alert.addProperty("x", at.getBlockX());
            alert.addProperty("y", at.getBlockY());
            alert.addProperty("z", at.getBlockZ());
            alert.addProperty("until", System.currentTimeMillis() + 180_000);
            rec.add("alert", alert);
            JsonObject p = new JsonObject();
            p.addProperty("collection", "npcs");
            p.add("record", rec);
            ctx.invoke("ledger_put", p);
        } catch (Throwable ignored) { }
    }

    private static final String[] WARNINGS = {
        "This is my land. Off it.",
        "You're on my ground.",
        "I built here. Move along.",
        "That line's mine. Step back.",
    };

    private static void warn(GadgetContext ctx, String owner, String intruderName, Location at) {
        if (!owner.startsWith("npc:")) return;
        try {
            NpcManager npcs = ctx.plugin().npcManager();
            NpcData d = npcs.get(owner.substring(4));
            if (d == null || d.dead) return;
            Entity body = npcs.resolveEntity(d);
            if (body == null || body.getLocation().distance(at) > 48) return;   // too far to have seen it
            JsonObject a = new JsonObject();
            a.addProperty("id", d.id);
            a.addProperty("text", WARNINGS[Math.abs((owner + intruderName).hashCode()) % WARNINGS.length]);
            ctx.invoke("npc_say", a);
        } catch (Throwable ignored) { }
    }

    private static void fire(GadgetContext ctx, String event, String intruder, String owner, Location at, int dwellSec) {
        JsonObject ev = new JsonObject();
        ev.addProperty("intruder", intruder);
        ev.addProperty("owner", owner);
        ev.addProperty("x", at.getBlockX());
        ev.addProperty("y", at.getBlockY());
        ev.addProperty("z", at.getBlockZ());
        if (dwellSec > 0) ev.addProperty("dwellSec", dwellSec);
        String pk = intruder + "|" + owner;
        JsonObject h = HOSTILITY.has(pk) && HOSTILITY.get(pk).isJsonObject() ? HOSTILITY.getAsJsonObject(pk) : null;
        ev.addProperty("hostility", h == null ? 0 : geti(h, "count", 0));
        try { ctx.plugin().bridge().broadcastEvent(event, ev); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ the ears: player placements

    public static class ClaimsEars implements Listener {
        private final GadgetContext ctx;
        private final int gen;

        public ClaimsEars(GadgetContext ctx, int gen) {
            this.ctx = ctx;
            this.gen = gen;
        }

        @EventHandler(ignoreCancelled = true)
        public void onPlace(BlockPlaceEvent event) {
            if (gen != generation(ctx, false)) return;   // stale generation: a newer Claims runs now
            handle(event.getPlayer(), event.getBlock().getX(), event.getBlock().getZ(), true);
        }

        @EventHandler(ignoreCancelled = true)
        public void onBreak(BlockBreakEvent event) {
            if (gen != generation(ctx, false)) return;
            handle(event.getPlayer(), event.getBlock().getX(), event.getBlock().getZ(), false);
        }

        private void handle(Player p, int x, int z, boolean place) {
            try {
                String who = "player:" + p.getName();
                String cur = ownerOf(x, z);
                if (cur != null && !cur.equals(who) && !allied(who, cur)) {
                    // building on somebody's land is the act of hostility itself
                    String pk = who + "|" + cur;
                    long last = EVENTED.containsKey(pk + "#v") ? EVENTED.get(pk + "#v").longValue() : 0;
                    rememberHostility(who, cur, 3);
                    if (System.currentTimeMillis() - last > VIOLATION_COOLDOWN_MS) {
                        EVENTED.put(pk + "#v", Long.valueOf(System.currentTimeMillis()));
                        Location at = new Location(p.getWorld(), x, p.getLocation().getY(), z);
                        warn(ctx, cur, p.getName(), at);
                        alertOwner(ctx, cur, p.getName(), at);
                        fire(ctx, "claim_violation", who, cur, at, 0);
                    }
                    return;   // their placement claims nothing on foreign ground
                }
                if (place) stake(who, x, z, x, z, RADIUS);
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ the sweep: trespass

    private static void sweep(GadgetContext ctx) {
        refreshVillages(ctx);
        Set<String> present = new HashSet<String>();
        List<Object[]> movers = new ArrayList<Object[]>();   // {key, displayName, Location}
        for (Player p : ctx.server().getOnlinePlayers()) {
            movers.add(new Object[]{ "player:" + p.getName(), p.getName(), p.getLocation() });
        }
        NpcManager npcs = ctx.plugin().npcManager();
        for (NpcData d : npcs.all()) {
            if (d.dead) continue;
            Entity e = npcs.resolveEntity(d);
            if (e == null) continue;
            movers.add(new Object[]{ "npc:" + d.id, d.name, e.getLocation() });
        }
        for (Object[] m : movers) {
            String who = (String) m[0];
            String name = (String) m[1];
            Location at = (Location) m[2];
            String owner = ownerOf(at.getBlockX(), at.getBlockZ());
            if (owner == null || owner.startsWith("village:") || owner.equals(who) || allied(who, owner)) continue;
            String pk = who + "|" + owner;
            present.add(pk);
            int dwell = (DWELL.containsKey(pk) ? DWELL.get(pk).intValue() : 0) + PERIOD_TICKS / 20;
            DWELL.put(pk, Integer.valueOf(dwell));
            long now = System.currentTimeMillis();
            Long warned = WARNED.get(pk);
            if (warned == null || now - warned.longValue() > WARN_COOLDOWN_MS) {
                WARNED.put(pk, Long.valueOf(now));
                warn(ctx, owner, name, at);
            }
            if (dwell >= TRESPASS_AFTER_SEC) {
                Long ev = EVENTED.get(pk);
                if (ev == null || now - ev.longValue() > EVENT_COOLDOWN_MS) {
                    EVENTED.put(pk, Long.valueOf(now));
                    rememberHostility(who, owner, 1);
                    alertOwner(ctx, owner, name, at);
                    fire(ctx, "claim_trespass", who, owner, at, dwell);
                }
            }
        }
        // stepping off the land resets the clock
        for (String pk : new ArrayList<String>(DWELL.keySet())) {
            if (!present.contains(pk)) DWELL.remove(pk);
        }
        beats++;
        if (beats % SAVE_EVERY_BEATS == 0) flush(ctx);
    }

    // ------------------------------------------------------------------ actions

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        load(ctx);
        String action = gets(args, "action", "start");
        JsonObject out = new JsonObject();

        if (action.equals("stake")) {
            String owner = gets(args, "owner", null);
            if (owner == null) throw new IllegalArgumentException("stake needs an owner");
            int x1 = geti(args, "x1", geti(args, "x", 0));
            int z1 = geti(args, "z1", geti(args, "z", 0));
            int x2 = geti(args, "x2", x1);
            int z2 = geti(args, "z2", z1);
            return stake(owner, x1, z1, x2, z2, geti(args, "r", RADIUS));
        }

        if (action.equals("owner_of")) {
            String o = ownerOf(geti(args, "x", 0), geti(args, "z", 0));
            out.addProperty("owner", o == null ? "" : o);
            return out;
        }

        if (action.equals("check_rect")) {
            String owner = gets(args, "owner", "");
            int x1 = geti(args, "x1", 0), z1 = geti(args, "z1", 0);
            int x2 = geti(args, "x2", x1), z2 = geti(args, "z2", z1);
            Set<String> conflicts = new HashSet<String>();
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    String cur = ownerOf(x, z);
                    if (cur != null && !cur.equals(owner) && !allied(owner, cur)) conflicts.add(cur);
                }
            }
            out.addProperty("ok", conflicts.isEmpty());
            JsonArray c = new JsonArray();
            for (String s : conflicts) c.add(s);
            out.add("conflicts", c);
            return out;
        }

        if (action.equals("unstake")) {
            // give back one plot (plus its barrier) without touching the owner's other land -
            // what a builder does when they abandon a site
            String owner = gets(args, "owner", null);
            if (owner == null) throw new IllegalArgumentException("unstake needs an owner");
            int x1 = geti(args, "x1", 0), z1 = geti(args, "z1", 0);
            int x2 = geti(args, "x2", x1), z2 = geti(args, "z2", z1);
            int r = geti(args, "r", RADIUS);
            Set<Long> mine = BY_OWNER.get(owner);
            int n = 0;
            if (mine != null) {
                for (int x = Math.min(x1, x2) - r; x <= Math.max(x1, x2) + r; x++) {
                    for (int z = Math.min(z1, z2) - r; z <= Math.max(z1, z2) + r; z++) {
                        Long key = Long.valueOf(pack(x, z));
                        if (owner.equals(COLS.get(key))) { COLS.remove(key); mine.remove(key); n++; }
                    }
                }
            }
            if (n > 0) DIRTY.add(owner);
            out.addProperty("released", n);
            return out;
        }

        if (action.equals("release")) {
            String owner = gets(args, "owner", null);
            if (owner == null) throw new IllegalArgumentException("release needs an owner");
            Set<Long> mine = BY_OWNER.remove(owner);
            int n = 0;
            if (mine != null) {
                for (Long key : mine) { COLS.remove(key); n++; }
            }
            DIRTY.add(owner);
            flush(ctx);
            out.addProperty("released", n);
            return out;
        }

        if (action.equals("hostility")) {
            out.add("pairs", HOSTILITY);
            return out;
        }

        if (action.equals("status")) {
            out.addProperty("running", TASK_ID != null);
            out.addProperty("columns", COLS.size());
            JsonObject owners = new JsonObject();
            for (Map.Entry<String, Set<Long>> e : BY_OWNER.entrySet()) owners.addProperty(e.getKey(), e.getValue().size());
            out.add("owners", owners);
            out.addProperty("hostilePairs", HOSTILITY.entrySet().size());
            out.addProperty("lastError", lastError == null ? "" : lastError);
            return out;
        }

        if (action.equals("stop")) {
            generation(ctx, true);
            int t = reapTimers(ctx);
            int l = reapListeners();
            flush(ctx);
            TASK_ID = null;
            out.addProperty("stoppedTimers", t);
            out.addProperty("stoppedListeners", l);
            return out;
        }

        // start (the default): bump the generation, reap anything older, listen and sweep
        final int gen = generation(ctx, true);
        int reapedT = reapTimers(ctx);
        int reapedL = reapListeners();
        ctx.server().getPluginManager().registerEvents(new ClaimsEars(ctx, gen), ctx.plugin());
        final GadgetContext c = ctx;
        TASK_ID = Integer.valueOf(ctx.runTimer(PERIOD_TICKS, new Runnable() {
            public void run() {
                if (gen != generation(c, false)) return;
                try { sweep(c); } catch (Throwable t) { lastError = "sweep: " + t; }
            }
        }));
        out.addProperty("running", true);
        out.addProperty("generation", gen);
        out.addProperty("reapedTimers", reapedT);
        out.addProperty("reapedListeners", reapedL);
        out.addProperty("columns", COLS.size());
        return out;
    }
}
