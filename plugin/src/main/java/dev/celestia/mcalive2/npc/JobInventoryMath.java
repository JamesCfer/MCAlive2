package dev.celestia.mcalive2.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure withdraw/deposit matching logic for {@link JobManager}: given what's actually
 * available in a container and what a job step requires, decides whether the
 * all-or-nothing withdrawal can proceed and, if not, exactly what's missing. No Bukkit
 * dependency (material identity is just a String key), so this is unit-testable without
 * a real inventory.
 */
public final class JobInventoryMath {

    /** One material requirement: e.g. 5x IRON_INGOT. */
    public record Requirement(String material, int count) {}

    private JobInventoryMath() {}

    /**
     * @param available material name -> amount currently in the container
     * @param required  the requirements to check, all-or-nothing
     * @return the shortfall for each unmet requirement (empty list means everything is available)
     */
    public static List<Requirement> missing(Map<String, Integer> available, List<Requirement> required) {
        List<Requirement> missing = new ArrayList<>();
        for (Requirement r : required) {
            int have = available.getOrDefault(r.material(), 0);
            if (have < r.count()) {
                missing.add(new Requirement(r.material(), r.count() - have));
            }
        }
        return missing;
    }
}
