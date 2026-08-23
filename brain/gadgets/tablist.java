package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import dev.celestia.mcalive2.npc.NpcData;
import dev.celestia.mcalive2.npc.NpcManager;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * People in the player list. Every living person is sent to every online player as a
 * tab-list entry, the same packet a joining player produces, so the list reads as the
 * population of the world rather than just who is logged in.
 *
 * Done through the server's own classes by reflection - the packet's record shape has
 * shifted between versions and a hard import would turn every update into a compile
 * error. Anything that fails is reported in status rather than thrown.
 */
public class Tablist implements GadgetContract {

    private static Integer TASK_ID = null;
    private static int beats = 0;
    private static String lastError = null;
    /** Entries each player currently has, so removals are exact. */
    private static final Map<UUID, Set<UUID>> SHOWN = new HashMap<UUID, Set<UUID>>();

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("tablist-generation");
        Integer cur = pdc.get(k, org.bukkit.persistence.PersistentDataType.INTEGER);
        int g = cur == null ? 0 : cur.intValue();
        if (bump) { g = g + 1; pdc.set(k, org.bukkit.persistence.PersistentDataType.INTEGER, Integer.valueOf(g)); }
        return g;
    }

    private static int reap(GadgetContext ctx) {
        int killed = 0;
        for (org.bukkit.scheduler.BukkitTask t : ctx.server().getScheduler().getPendingTasks()) {
            if (t.getOwner() != ctx.plugin()) continue;
            Object inner = runnableOf(t);
            if (inner != null && inner.getClass().getName().contains("Tablist")) { t.cancel(); killed++; }
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

    // ------------------------------------------------------------------ NMS by reflection

    private static Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    private static Object enumValue(Class<?> enumCls, String name) {
        Object[] vals = enumCls.getEnumConstants();
        for (Object v : vals) if (((Enum<?>) v).name().equals(name)) return v;
        return null;
    }

    private static Object gameProfile(UUID id, String name) throws Exception {
        Class<?> gp = cls("com.mojang.authlib.GameProfile");
        Constructor<?> c = gp.getConstructor(UUID.class, String.class);
        return c.newInstance(id, name);
    }

    /** Build a packet Entry by filling the record's canonical constructor by type. */
    private static Object entry(Class<?> entryCls, UUID id, Object profile, String shownName) throws Exception {
        Constructor<?>[] cs = entryCls.getConstructors();
        Constructor<?> best = null;
        for (Constructor<?> c : cs) if (best == null || c.getParameterCount() > best.getParameterCount()) best = c;
        if (best == null) throw new IllegalStateException("no Entry constructor");
        Class<?>[] ps = best.getParameterTypes();
        Object[] args = new Object[ps.length];
        int booleans = 0, ints = 0;
        for (int i = 0; i < ps.length; i++) {
            Class<?> p = ps[i];
            if (p == UUID.class) args[i] = id;
            else if (p.getName().endsWith("GameProfile")) args[i] = profile;
            else if (p == boolean.class) { args[i] = Boolean.valueOf(booleans == 0); booleans++; }   // listed=true, showHat=false
            else if (p == int.class) { args[i] = Integer.valueOf(0); ints++; }
            else if (p.getName().endsWith("GameType")) args[i] = enumValue(p, "SURVIVAL");
            else if (p.getName().endsWith("Component")) args[i] = literal(shownName);
            else args[i] = null;
        }
        return best.newInstance(args);
    }

    private static Object literal(String s) {
        try {
            Class<?> comp = cls("net.minecraft.network.chat.Component");
            Method m = comp.getMethod("literal", String.class);
            return m.invoke(null, s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void send(Player p, Object packet) throws Exception {
        Object handle = p.getClass().getMethod("getHandle").invoke(p);
        Object conn = handle.getClass().getField("connection").get(handle);
        Method send = null;
        for (Method m : conn.getClass().getMethods()) {
            if (m.getName().equals("send") && m.getParameterCount() == 1) { send = m; break; }
        }
        if (send == null) throw new IllegalStateException("no send(packet) on connection");
        send.invoke(conn, packet);
    }

    private static Object addPacket(List<Object> entries) throws Exception {
        Class<?> pk = cls("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
        Class<?> action = null;
        for (Class<?> inner : pk.getDeclaredClasses()) if (inner.getSimpleName().equals("Action")) action = inner;
        if (action == null) throw new IllegalStateException("no Action enum");
        @SuppressWarnings({"unchecked", "rawtypes"})
        EnumSet actions = EnumSet.noneOf((Class) action);
        for (String n : new String[]{ "ADD_PLAYER", "UPDATE_LISTED", "UPDATE_GAME_MODE", "UPDATE_DISPLAY_NAME" }) {
            Object v = enumValue(action, n);
            if (v != null) actions.add(v);
        }
        for (Constructor<?> c : pk.getConstructors()) {
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length == 2 && EnumSet.class.isAssignableFrom(ps[0]) && Collection.class.isAssignableFrom(ps[1])) {
                return c.newInstance(actions, entries);
            }
        }
        throw new IllegalStateException("no (EnumSet, Collection) constructor on info packet");
    }

    private static Object removePacket(List<UUID> ids) throws Exception {
        Class<?> pk = cls("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
        Constructor<?> c = pk.getConstructor(List.class);
        return c.newInstance(ids);
    }

    private static Class<?> entryClass() throws Exception {
        Class<?> pk = cls("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
        for (Class<?> inner : pk.getDeclaredClasses()) if (inner.getSimpleName().equals("Entry")) return inner;
        throw new IllegalStateException("no Entry record");
    }

    // ------------------------------------------------------------------ the beat

    private static String tabName(String name) {
        String n = name.replaceAll("[^A-Za-z0-9_]", "");
        return n.length() > 16 ? n.substring(0, 16) : (n.isEmpty() ? "npc" : n);
    }

    private void beat(GadgetContext ctx) {
        beats++;
        try {
            NpcManager npcs = ctx.plugin().npcManager();
            JsonObject q = new JsonObject();
            q.addProperty("collection", "npcs");
            JsonArray recs = ctx.invoke("ledger_query", q).getAsJsonArray("records");

            // the living, with a body
            Map<UUID, String> living = new HashMap<UUID, String>();
            for (JsonElement el : recs) {
                JsonObject r = el.getAsJsonObject();
                if (!r.has("id") || (r.has("alive") && !r.get("alive").getAsBoolean())) continue;
                NpcData d = npcs.get(r.get("id").getAsString());
                if (d == null || d.dead) continue;
                Entity e = npcs.resolveEntity(d);
                if (e == null) continue;
                living.put(e.getUniqueId(), r.has("name") ? r.get("name").getAsString() : d.name);
            }

            Class<?> entryCls = entryClass();
            for (Player p : ctx.server().getOnlinePlayers()) {
                Set<UUID> shown = SHOWN.get(p.getUniqueId());
                if (shown == null) { shown = new HashSet<UUID>(); SHOWN.put(p.getUniqueId(), shown); }

                List<Object> add = new ArrayList<Object>();
                for (Map.Entry<UUID, String> e : living.entrySet()) {
                    if (shown.contains(e.getKey())) continue;
                    String n = tabName(e.getValue());
                    add.add(entry(entryCls, e.getKey(), gameProfile(e.getKey(), n), e.getValue()));
                }
                if (!add.isEmpty()) {
                    send(p, addPacket(add));
                    for (Map.Entry<UUID, String> e : living.entrySet()) shown.add(e.getKey());
                }

                List<UUID> gone = new ArrayList<UUID>();
                for (UUID u : shown) if (!living.containsKey(u)) gone.add(u);
                if (!gone.isEmpty()) {
                    send(p, removePacket(gone));
                    shown.removeAll(gone);
                }
            }
            // forget players who left, so they get a fresh list on return
            List<UUID> offline = new ArrayList<UUID>();
            for (UUID u : SHOWN.keySet()) if (ctx.server().getPlayer(u) == null) offline.add(u);
            for (UUID u : offline) SHOWN.remove(u);
            lastError = null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        }
    }

    // ------------------------------------------------------------------ entry point

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("beats", beats);
            out.addProperty("lastError", lastError);
            JsonObject shown = new JsonObject();
            for (Map.Entry<UUID, Set<UUID>> e : SHOWN.entrySet()) {
                Player p = ctx.server().getPlayer(e.getKey());
                shown.addProperty(p == null ? e.getKey().toString() : p.getName(), e.getValue().size());
            }
            out.add("shownTo", shown);
            return out;
        }

        if (action.equals("debug")) {
            JsonObject out = new JsonObject();
            try {
                NpcManager npcs = ctx.plugin().npcManager();
                JsonObject q = new JsonObject();
                q.addProperty("collection", "npcs");
                JsonArray recs = ctx.invoke("ledger_query", q).getAsJsonArray("records");
                JsonArray seen = new JsonArray();
                for (JsonElement el : recs) {
                    JsonObject r = el.getAsJsonObject();
                    JsonObject o = new JsonObject();
                    String id = r.has("id") ? r.get("id").getAsString() : "?";
                    o.addProperty("id", id);
                    o.addProperty("alive", !(r.has("alive") && !r.get("alive").getAsBoolean()));
                    NpcData d = npcs.get(id);
                    o.addProperty("data", d != null);
                    o.addProperty("dead", d != null && d.dead);
                    Entity e = d == null ? null : npcs.resolveEntity(d);
                    o.addProperty("entity", e != null);
                    seen.add(o);
                }
                out.add("people", seen);
                Class<?> entryCls = entryClass();
                out.addProperty("entryClass", entryCls.getName());
                JsonArray params = new JsonArray();
                Constructor<?> best = null;
                for (Constructor<?> c : entryCls.getConstructors()) if (best == null || c.getParameterCount() > best.getParameterCount()) best = c;
                if (best != null) for (Class<?> p : best.getParameterTypes()) params.add(p.getName());
                out.add("entryParams", params);
                UUID u = UUID.randomUUID();
                Object en = entry(entryCls, u, gameProfile(u, "probe"), "probe");
                out.addProperty("entryBuilt", en != null);
                List<Object> l = new ArrayList<Object>();
                l.add(en);
                Object pk = addPacket(l);
                out.addProperty("packetBuilt", pk != null);
            } catch (Throwable t) {
                out.addProperty("error", t.toString());
                StackTraceElement[] st = t.getStackTrace();
                out.addProperty("at", st.length > 0 ? st[0].toString() : "-");
            }
            return out;
        }

        if (action.equals("stop")) {
            generation(ctx, true);
            int killed = reap(ctx);
            if (TASK_ID != null) { ctx.cancelTask(TASK_ID.intValue()); TASK_ID = null; }
            // take everyone back off the list
            try {
                for (Player p : ctx.server().getOnlinePlayers()) {
                    Set<UUID> shown = SHOWN.get(p.getUniqueId());
                    if (shown != null && !shown.isEmpty()) send(p, removePacket(new ArrayList<UUID>(shown)));
                }
            } catch (Throwable ignored) { }
            SHOWN.clear();
            JsonObject out = new JsonObject();
            out.addProperty("stopped", true);
            out.addProperty("staleTimersCancelled", killed);
            return out;
        }

        final int myGen = generation(ctx, true);
        int killed = reap(ctx);
        if (TASK_ID != null) { ctx.cancelTask(TASK_ID.intValue()); TASK_ID = null; }
        SHOWN.clear();
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 40;
        TASK_ID = Integer.valueOf(ctx.runTimer(period, new Runnable() {
            public void run() {
                try {
                    if (generation(ctx, false) != myGen) { reap(ctx); return; }
                    beat(ctx);
                } catch (Throwable ignored) { }
            }
        }));
        beat(ctx);
        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("generation", myGen);
        out.addProperty("staleTimersCancelled", killed);
        out.addProperty("periodTicks", period);
        out.addProperty("lastError", lastError);
        return out;
    }
}
