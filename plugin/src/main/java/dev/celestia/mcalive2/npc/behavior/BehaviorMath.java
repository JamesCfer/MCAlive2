package dev.celestia.mcalive2.npc.behavior;

import dev.celestia.mcalive2.ledger.BlueprintParser;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure decision logic for {@link BehaviorEngine}: nearest-candidate selection, blueprint
 * claim allocation, carrying-map arithmetic, cursor advancement, and crew-done
 * detection. No Bukkit dependency (positions are plain doubles/ints, material identity
 * is a String key), so every branchy decision here is unit-testable without a server -
 * in the style of {@link dev.celestia.mcalive2.npc.JobInventoryMath}.
 */
public final class BehaviorMath {

    private BehaviorMath() {}

    // ---- nearest-candidate selection ----

    /**
     * Index of the candidate {x,y,z} nearest to the point, skipping excluded indices.
     * Returns -1 when every candidate is excluded (or there are none).
     */
    public static int nearest(List<int[]> candidates, double px, double py, double pz, Set<Integer> excluded) {
        int best = -1;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            if (excluded != null && excluded.contains(i)) continue;
            int[] c = candidates.get(i);
            double dx = c[0] + 0.5 - px, dy = c[1] + 0.5 - py, dz = c[2] + 0.5 - pz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best;
    }

    // ---- carrying arithmetic ----

    public static void add(Map<String, Integer> carrying, String material, int amount) {
        carrying.merge(material, amount, Integer::sum);
    }

    public static boolean has(Map<String, Integer> carrying, String material, int amount) {
        return carrying.getOrDefault(material, 0) >= amount;
    }

    /** Take {@code amount} of a material out of the carrying map, all-or-nothing.
     *  @return false (leaving the map untouched) if there isn't enough */
    public static boolean consume(Map<String, Integer> carrying, String material, int amount) {
        int have = carrying.getOrDefault(material, 0);
        if (have < amount) return false;
        if (have == amount) carrying.remove(material);
        else carrying.put(material, have - amount);
        return true;
    }

    // ---- cursor advancement ----

    /** The next step index after finishing {@code step}: wraps to 0 when looping,
     *  otherwise lands on {@code stepCount} itself, the "done" sentinel. */
    public static int advance(int step, int stepCount, boolean loop) {
        int next = step + 1;
        if (next >= stepCount) return loop ? 0 : stepCount;
        return next;
    }

    /** True once a cursor's step index has advanced past the last step. */
    public static boolean isDone(int step, int stepCount) {
        return step >= stepCount;
    }

    /** True when EVERY cursor in the crew has finished (see {@link #isDone}). */
    public static boolean allDone(Collection<Integer> cursorSteps, int stepCount) {
        if (cursorSteps.isEmpty()) return false;
        for (int step : cursorSteps) {
            if (!isDone(step, stepCount)) return false;
        }
        return true;
    }

    // ---- blueprint claim allocation ----

    /**
     * The nearest blueprint block this NPC can go place right now: unplaced, unclaimed
     * by a crewmate, and (when {@code carrying} is non-null) of a material the NPC
     * actually carries. Positions are resolved against the blueprint origin
     * {@code ox,oy,oz}. Returns the block index, or -1 if nothing qualifies.
     */
    public static int nextBlockFor(List<BlueprintParser.BlockEntry> blocks, Set<Integer> placed,
                                   Set<Integer> claimed, Map<String, Integer> carrying,
                                   double px, double py, double pz, int ox, int oy, int oz) {
        int best = -1;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < blocks.size(); i++) {
            if (placed.contains(i) || (claimed != null && claimed.contains(i))) continue;
            BlueprintParser.BlockEntry e = blocks.get(i);
            if (carrying != null && !has(carrying, e.material().toUpperCase(java.util.Locale.ROOT), 1)) continue;
            double dx = ox + e.dx() + 0.5 - px, dy = oy + e.dy() + 0.5 - py, dz = oz + e.dz() + 0.5 - pz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best;
    }

    /** Material -> count still needed to finish the blueprint: every block neither
     *  placed nor claimed by a crewmate. */
    public static Map<String, Integer> neededMaterials(List<BlueprintParser.BlockEntry> blocks,
                                                       Set<Integer> placed, Set<Integer> claimed) {
        Map<String, Integer> needed = new LinkedHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (placed.contains(i) || (claimed != null && claimed.contains(i))) continue;
            add(needed, blocks.get(i).material().toUpperCase(java.util.Locale.ROOT), 1);
        }
        return needed;
    }

    /**
     * What one supply-chest trip should withdraw: up to {@code blocksPerTrip} items
     * total, drawn from {@code needed} materials, capped by what the chest actually
     * holds. An empty result means the chest has nothing useful.
     */
    public static Map<String, Integer> withdrawPlan(Map<String, Integer> needed,
                                                    Map<String, Integer> chest, int blocksPerTrip) {
        Map<String, Integer> plan = new LinkedHashMap<>();
        int budget = Math.max(0, blocksPerTrip);
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            if (budget <= 0) break;
            int available = chest.getOrDefault(e.getKey(), 0);
            int take = Math.min(budget, Math.min(available, e.getValue()));
            if (take > 0) {
                plan.put(e.getKey(), take);
                budget -= take;
            }
        }
        return plan;
    }
}
