package dev.celestia.estari.ledger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@code npc_context}: an NPC's own sheet plus only the facts it is allowed
 * to know. Knowledge isolation is enforced HERE, plugin-side, so an actor process can
 * never see (and therefore never metagame) a fact it has no in-world way of knowing.
 *
 * A fact is visible to an NPC if its {@code knownBy} array contains any of:
 *  - the NPC's own id
 *  - {@code "faction:" + <the NPC's faction id>} (only if the NPC belongs to a faction)
 *  - the literal string {@code "all"}
 */
public final class NpcContext {

    private NpcContext() {}

    public static JsonObject resolve(Ledger ledger, String npcId) {
        JsonObject npc = ledger.get("npcs", npcId);
        if (npc == null) throw new IllegalArgumentException("no npc with id: " + npcId);

        String faction = npc.has("faction") && !npc.get("faction").isJsonNull()
                ? npc.get("faction").getAsString() : null;

        List<JsonObject> visible = new ArrayList<>();
        for (JsonObject fact : ledger.all("facts")) {
            if (isKnownBy(fact, npcId, faction)) visible.add(fact);
        }

        JsonObject result = new JsonObject();
        result.add("npc", npc);
        JsonArray facts = new JsonArray();
        for (JsonObject f : visible) facts.add(f);
        result.add("facts", facts);
        return result;
    }

    private static boolean isKnownBy(JsonObject fact, String npcId, String faction) {
        JsonElement knownByEl = fact.get("knownBy");
        if (knownByEl == null || !knownByEl.isJsonArray()) return false;
        for (JsonElement entry : knownByEl.getAsJsonArray()) {
            if (entry.isJsonNull()) continue;
            String who = entry.getAsString();
            if (who.equals("all")) return true;
            if (who.equals(npcId)) return true;
            if (faction != null && who.equals("faction:" + faction)) return true;
        }
        return false;
    }
}
