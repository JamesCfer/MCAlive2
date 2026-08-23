package celestia.gadgets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.celestia.mcalive2.gadget.GadgetContract;
import dev.celestia.mcalive2.gadget.GadgetContext;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes a plain-English "what is this one doing right now" onto every NPC's ledger
 * record, so clicking a marker on the map answers the question instead of just naming
 * the NPC.
 *
 * The truth is spread across two places - the behavior engine knows who is walking a
 * circuit or hauling to a chest, and the work gadgets know who is down a shaft or over
 * the horizon - so this reconciles both into one sentence per NPC each sweep.
 */
public class Roster implements GadgetContract {

    private static Integer TASK_ID = null;
    private static int sweeps = 0;

    private static int generation(GadgetContext ctx, boolean bump) {
        World w = ctx.server().getWorlds().get(0);
        org.bukkit.persistence.PersistentDataContainer pdc = w.getPersistentDataContainer();
        org.bukkit.NamespacedKey k = ctx.key("roster-generation");
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
            if (inner != null && inner.getClass().getName().contains("Roster")) { t.cancel(); killed++; }
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

    private static String describeStep(String type, boolean paused) {
        if (paused) return "stopped, cannot reach its next task";
        if (type == null) return "working";
        if (type.equals("goto")) return "walking its rounds";
        if (type.equals("wait")) return "resting a moment";
        if (type.equals("gather")) return "gathering materials";
        if (type.equals("build")) return "building";
        if (type.equals("deposit")) return "hauling goods to the store";
        if (type.equals("withdraw")) return "drawing supplies from the store";
        if (type.equals("follow")) return "following";
        if (type.equals("work_station")) return "working at a station";
        if (type.equals("done")) return "finished its task, awaiting orders";
        return type;
    }

    private JsonObject gadgetStatus(GadgetContext ctx, String gadget, String npcId) {
        try {
            JsonObject a = new JsonObject();
            a.addProperty("npcId", npcId);
            a.addProperty("action", "status");
            JsonObject call = new JsonObject();
            call.addProperty("id", gadget);
            call.add("args", a);
            return ctx.invoke("gadget_run", call);
        } catch (Throwable t) {
            return null;
        }
    }

    private int sweep(GadgetContext ctx) throws Exception {
        // who the behavior engine is driving, and what step they are on
        Map<String, String> byProgram = new HashMap<String, String>();
        Map<String, Boolean> pausedProgram = new HashMap<String, Boolean>();
        Map<String, String> programOf = new HashMap<String, String>();
        JsonObject st = ctx.invoke("behavior_status", new JsonObject());
        for (JsonElement el : st.getAsJsonArray("programs")) {
            JsonObject p = el.getAsJsonObject();
            boolean paused = p.has("paused") && p.get("paused").getAsBoolean();
            if (!p.has("cursors") || !p.get("cursors").isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("cursors").entrySet()) {
                JsonObject cur = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
                String type = cur != null && cur.has("type") ? cur.get("type").getAsString() : null;
                byProgram.put(e.getKey(), describeStep(type, paused));
                pausedProgram.put(e.getKey(), Boolean.valueOf(paused));
                programOf.put(e.getKey(), p.get("id").getAsString());
            }
        }

        // whatever the pursuits chooser has someone doing is the most specific answer
        Map<String, String> pursuit = new HashMap<String, String>();
        // circuits pursuits stopped on purpose, which must not be reported as a fault
        java.util.Set<String> deliberate = new java.util.HashSet<String>();
        try {
            JsonObject pa = new JsonObject();
            pa.addProperty("action", "jobs");
            JsonObject pc = new JsonObject();
            pc.addProperty("id", "pursuits");
            pc.add("args", pa);
            JsonObject pj = ctx.invoke("gadget_run", pc);
            if (pj.has("pausedByUs") && pj.get("pausedByUs").isJsonArray()) {
                for (JsonElement e : pj.getAsJsonArray("pausedByUs")) deliberate.add(e.getAsString());
            }
            if (pj.has("doing") && pj.get("doing").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : pj.getAsJsonObject("doing").entrySet()) {
                    pursuit.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (Throwable ignored) { }

        JsonObject q = new JsonObject();
        q.addProperty("collection", "npcs");
        JsonArray records = ctx.invoke("ledger_query", q).getAsJsonArray("records");
        int written = 0;
        for (JsonElement el : records) {
            JsonObject rec = el.getAsJsonObject();
            if (!rec.has("id")) continue;
            String id = rec.get("id").getAsString();
            if (rec.has("alive") && !rec.get("alive").getAsBoolean()) {
                rec.addProperty("activity", "dead");
                putRecord(ctx, rec);
                written++;
                continue;
            }

            String doing = pursuit.get(id);
            // an errand gadget outranks a behavior program - it is what actually
            // has hold of the NPC right now
            JsonObject m = doing != null ? null : gadgetStatus(ctx, "mine", id);
            if (m != null && m.has("running") && m.get("running").getAsBoolean()) {
                doing = "down the mine shaft (" + (m.has("broken") ? m.get("broken").getAsInt() : 0) + " blocks cut)";
            }
            if (doing == null) {
                JsonObject f = gadgetStatus(ctx, "forage", id);
                if (f != null && f.has("running") && f.get("running").getAsBoolean()) {
                    doing = "foraging " + (f.has("blocksOut") ? f.get("blocksOut").getAsInt() : 0)
                            + " blocks from home (" + (f.has("found") ? f.get("found").getAsInt() : 0) + " food found)";
                }
            }
            if (doing == null) {
                JsonObject t = gadgetStatus(ctx, "forester", id);
                if (t != null && t.has("running") && t.get("running").getAsBoolean()) {
                    doing = "felling timber (" + (t.has("felled") ? t.get("felled").getAsInt() : 0) + " logs)";
                }
            }
            if (doing == null) {
                Boolean wasPaused = pausedProgram.get(id);
                String pid = programOf.get(id);
                if (wasPaused != null && wasPaused.booleanValue() && pid != null && deliberate.contains(pid)) {
                    doing = "between tasks";
                } else {
                    doing = byProgram.get(id);
                }
            }
            if (doing == null) doing = "idle - nothing assigned";

            // hunger colours what they are doing
            if (rec.has("hunger") && !rec.get("hunger").isJsonNull()) {
                int fed = rec.get("hunger").getAsInt();
                if (fed <= 0) doing = "STARVING - " + doing;
                else if (fed <= 6) doing = doing + ", and hungry";
            }

            rec.addProperty("activity", doing);
            putRecord(ctx, rec);
            written++;
        }
        return written;
    }

    private void putRecord(GadgetContext ctx, JsonObject rec) throws Exception {
        JsonObject put = new JsonObject();
        put.addProperty("collection", "npcs");
        put.add("record", rec);
        ctx.invoke("ledger_put", put);
    }

    public JsonObject run(JsonObject args, GadgetContext ctx) throws Exception {
        String action = args.has("action") ? args.get("action").getAsString() : "start";

        if (action.equals("status")) {
            JsonObject out = new JsonObject();
            out.addProperty("running", TASK_ID != null);
            out.addProperty("sweeps", sweeps);
            return out;
        }
        if (action.equals("sweep")) {
            JsonObject out = new JsonObject();
            out.addProperty("written", sweep(ctx));
            return out;
        }
        if (action.equals("stop")) {
            generation(ctx, true);
            int killed = reap(ctx);
            if (TASK_ID != null) { ctx.cancelTask(TASK_ID.intValue()); TASK_ID = null; }
            JsonObject out = new JsonObject();
            out.addProperty("stopped", true);
            out.addProperty("staleTimersCancelled", killed);
            return out;
        }

        final int myGen = generation(ctx, true);
        int killed = reap(ctx);
        if (TASK_ID != null) { ctx.cancelTask(TASK_ID.intValue()); TASK_ID = null; }
        int period = args.has("periodTicks") ? args.get("periodTicks").getAsInt() : 200;
        int first = sweep(ctx);
        TASK_ID = Integer.valueOf(ctx.runTimer(period, new Runnable() {
            public void run() {
                try {
                    if (generation(ctx, false) != myGen) { reap(ctx); return; }
                    sweeps++;
                    sweep(ctx);
                } catch (Throwable ignored) { }
            }
        }));
        JsonObject out = new JsonObject();
        out.addProperty("started", true);
        out.addProperty("generation", myGen);
        out.addProperty("staleTimersCancelled", killed);
        out.addProperty("describedNow", first);
        return out;
    }
}
