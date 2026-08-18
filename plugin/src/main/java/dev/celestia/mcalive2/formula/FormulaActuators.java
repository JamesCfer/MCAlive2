package dev.celestia.mcalive2.formula;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.celestia.mcalive2.MCAlive2Plugin;
import dev.celestia.mcalive2.bridge.CommandDispatcher;
import dev.celestia.mcalive2.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bukkit-facing wiring for the formula engine: registers {@code formula_define/run/list/
 * get/delete}, persists {@link FormulaStore} to {@code formulas.json}, and schedules
 * formula runs as main-thread tasks that invoke steps through the same
 * {@link CommandDispatcher} the bridge itself uses.
 */
public class FormulaActuators {

    private static final int MAX_CONCURRENT_RUNS = 4;
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final MCAlive2Plugin plugin;
    private final CommandDispatcher dispatcher;
    private final FormulaStore store = new FormulaStore();
    private final Set<String> activeRuns = ConcurrentHashMap.newKeySet();

    public FormulaActuators(MCAlive2Plugin plugin, CommandDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
    }

    public void register(CommandDispatcher d) {
        d.register("formula_define", this::define);
        d.register("formula_run", this::run);
        d.register("formula_list", this::list);
        d.register("formula_get", this::get);
        d.register("formula_delete", this::delete);
    }

    // ---- commands ----

    private JsonObject define(JsonObject args) {
        store.define(args, dispatcher::isRegistered);
        save();
        return new JsonObject();
    }

    private JsonObject list(JsonObject args) {
        JsonArray arr = new JsonArray();
        for (JsonObject f : store.all()) {
            JsonObject summary = new JsonObject();
            summary.addProperty("id", f.get("id").getAsString());
            if (f.has("description")) summary.add("description", f.get("description"));
            summary.add("params", f.has("params") ? f.get("params") : new JsonArray());
            arr.add(summary);
        }
        JsonObject out = new JsonObject();
        out.add("formulas", arr);
        return out;
    }

    private JsonObject get(JsonObject args) {
        String id = Json.reqString(args, "id");
        JsonObject f = store.get(id);
        if (f == null) throw new IllegalArgumentException("no formula with id: " + id);
        return f.deepCopy();
    }

    private JsonObject delete(JsonObject args) {
        String id = Json.reqString(args, "id");
        store.delete(id);
        save();
        return new JsonObject();
    }

    private JsonObject run(JsonObject args) {
        String id = Json.reqString(args, "id");
        JsonObject formula = store.get(id);
        if (formula == null) throw new IllegalArgumentException("no formula with id: " + id);
        if (activeRuns.size() >= MAX_CONCURRENT_RUNS) {
            throw new IllegalArgumentException("too many concurrent formula runs (max " + MAX_CONCURRENT_RUNS + ")");
        }
        JsonObject providedArgs = args.has("args") && args.get("args").isJsonObject()
                ? args.getAsJsonObject("args") : new JsonObject();

        Map<String, Double> baseVars = buildVars(formula, providedArgs);

        JsonArray steps = formula.getAsJsonArray("steps");
        JsonObject loop = formula.has("loop") && formula.get("loop").isJsonObject()
                ? formula.getAsJsonObject("loop") : null;

        int iterations = 1;
        long intervalTicks = 0;
        if (loop != null) {
            iterations = Math.max(0, (int) Math.round(evalMaybeExpr(loop.get("count"), baseVars, 1)));
            intervalTicks = Math.max(0, Math.round(evalMaybeExpr(loop.get("intervalTicks"), baseVars, 0)));
        }

        String runId = randomId();
        activeRuns.add(runId);
        RunState state = new RunState(runId, id, iterations);

        long finalDelay = 0;
        for (int i = 0; i < iterations; i++) {
            long iterBase = (long) i * intervalTicks;
            long cumulative = 0;
            Map<String, Double> iterVars = new HashMap<>(baseVars);
            iterVars.put("i", (double) i);
            int stepIndex = 0;
            for (JsonElement stepEl : steps) {
                JsonObject step = stepEl.getAsJsonObject();
                long delayTicks = step.has("delayTicks") && !step.get("delayTicks").isJsonNull()
                        ? step.get("delayTicks").getAsLong() : 0;
                cumulative += delayTicks;
                long fireDelay = Math.max(0, iterBase + cumulative);
                finalDelay = Math.max(finalDelay, fireDelay);
                int si = stepIndex;
                BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin,
                        () -> executeStep(state, step, si, iterVars), fireDelay);
                state.tasks.add(task);
                stepIndex++;
            }
        }

        BukkitTask finisher = Bukkit.getScheduler().runTaskLater(plugin, () -> finish(state), finalDelay + 1);
        state.tasks.add(finisher);

        JsonObject out = new JsonObject();
        out.addProperty("runId", runId);
        return out;
    }

    // ---- execution ----

    private void executeStep(RunState state, JsonObject step, int stepIndex, Map<String, Double> vars) {
        if (state.aborted) return;
        String cmd = step.get("cmd").getAsString();
        try {
            JsonObject resolvedArgs = resolveArgs(step, vars);
            dispatcher.invoke(cmd, resolvedArgs);
        } catch (Exception e) {
            state.aborted = true;
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            plugin.getLogger().warning("Formula run " + state.runId + " (" + state.formulaId
                    + ") failed at step " + stepIndex + " (" + cmd + "): " + message);
            for (BukkitTask t : state.tasks) t.cancel();
            activeRuns.remove(state.runId);

            JsonObject data = new JsonObject();
            data.addProperty("runId", state.runId);
            data.addProperty("formulaId", state.formulaId);
            data.addProperty("step", stepIndex);
            data.addProperty("error", message);
            plugin.bridge().broadcastEvent("formula_error", data);
        }
    }

    private void finish(RunState state) {
        if (state.aborted) return;
        activeRuns.remove(state.runId);
        JsonObject data = new JsonObject();
        data.addProperty("sequenceId", state.runId);
        data.addProperty("kind", "formula:" + state.formulaId);
        data.addProperty("count", state.iterations);
        plugin.bridge().broadcastEvent("sequence_done", data);
    }

    private JsonObject resolveArgs(JsonObject step, Map<String, Double> vars) {
        JsonObject out = new JsonObject();
        if (step.has("args") && step.get("args").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : step.getAsJsonObject("args").entrySet()) {
                out.add(e.getKey(), Expr.resolveValue(e.getValue(), vars));
            }
        }
        return out;
    }

    private Map<String, Double> buildVars(JsonObject formula, JsonObject providedArgs) {
        Map<String, Double> vars = new HashMap<>();
        if (formula.has("params") && formula.get("params").isJsonArray()) {
            for (JsonElement pEl : formula.getAsJsonArray("params")) {
                JsonObject p = pEl.getAsJsonObject();
                String name = p.get("name").getAsString();
                JsonElement value = providedArgs.has(name) ? providedArgs.get(name)
                        : p.has("default") ? p.get("default") : null;
                Double d = toDouble(value);
                if (d != null) vars.put(name, d);
            }
        }
        return vars;
    }

    private Double toDouble(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isNumber()) return p.getAsDouble();
        if (p.isString()) {
            try { return Double.parseDouble(p.getAsString()); } catch (NumberFormatException ex) { return null; }
        }
        return null;
    }

    private double evalMaybeExpr(JsonElement el, Map<String, Double> vars, double def) {
        if (el == null || el.isJsonNull()) return def;
        JsonElement resolved = Expr.resolveValue(el, vars);
        if (resolved.isJsonPrimitive() && resolved.getAsJsonPrimitive().isNumber()) {
            return resolved.getAsDouble();
        }
        throw new IllegalArgumentException("loop count/intervalTicks must be numeric");
    }

    private static String randomId() {
        StringBuilder sb = new StringBuilder(8);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) sb.append(ID_CHARS.charAt(rnd.nextInt(ID_CHARS.length())));
        return sb.toString();
    }

    private static final class RunState {
        final String runId;
        final String formulaId;
        final int iterations;
        final List<BukkitTask> tasks = new ArrayList<>();
        volatile boolean aborted = false;

        RunState(String runId, String formulaId, int iterations) {
            this.runId = runId;
            this.formulaId = formulaId;
            this.iterations = iterations;
        }
    }

    // ---- lifecycle ----

    private File file() {
        return new File(plugin.getDataFolder(), "formulas.json");
    }

    public void save() {
        try {
            plugin.getDataFolder().mkdirs();
            store.save(file());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save formulas.json: " + e.getMessage());
        }
    }

    public void load() {
        try {
            store.load(file());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load formulas.json: " + e.getMessage());
        }
    }
}
