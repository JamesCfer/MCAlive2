package dev.celestia.mcalive2.ledger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Knowledge isolation is the whole point of npc_context: an actor must only ever see
 * facts it could plausibly know, so it can never metagame. These tests exercise the
 * three knownBy forms (direct npc id, faction:<id>, and "all") plus the negative case.
 */
class NpcContextTest {

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();

        JsonObject alden = new JsonObject();
        alden.addProperty("id", "npc.alden");
        alden.addProperty("name", "Alden");
        alden.addProperty("faction", "millers");
        ledger.put("npcs", alden);

        JsonObject brin = new JsonObject();
        brin.addProperty("id", "npc.brin");
        brin.addProperty("name", "Brin");
        // no faction
        ledger.put("npcs", brin);
    }

    private JsonObject fact(String id, String text, String... knownBy) {
        JsonObject f = new JsonObject();
        f.addProperty("id", id);
        f.addProperty("text", text);
        JsonArray arr = new JsonArray();
        for (String k : knownBy) arr.add(k);
        f.add("knownBy", arr);
        return f;
    }

    @Test
    void directNpcIdFactIsVisibleOnlyToThatNpc() {
        ledger.put("facts", fact("f.secret", "Alden hid the ledger", "npc.alden"));

        JsonObject ctxAlden = NpcContext.resolve(ledger, "npc.alden");
        assertEquals(1, ctxAlden.getAsJsonArray("facts").size());

        JsonObject ctxBrin = NpcContext.resolve(ledger, "npc.brin");
        assertEquals(0, ctxBrin.getAsJsonArray("facts").size());
    }

    @Test
    void factionFactIsVisibleToFactionMembersOnly() {
        ledger.put("facts", fact("f.faction", "The mill guild meets at dusk", "faction:millers"));

        JsonObject ctxAlden = NpcContext.resolve(ledger, "npc.alden"); // in millers
        assertEquals(1, ctxAlden.getAsJsonArray("facts").size());

        JsonObject ctxBrin = NpcContext.resolve(ledger, "npc.brin"); // no faction
        assertEquals(0, ctxBrin.getAsJsonArray("facts").size());
    }

    @Test
    void allFactIsVisibleToEveryone() {
        ledger.put("facts", fact("f.public", "The bridge collapsed", "all"));

        assertEquals(1, NpcContext.resolve(ledger, "npc.alden").getAsJsonArray("facts").size());
        assertEquals(1, NpcContext.resolve(ledger, "npc.brin").getAsJsonArray("facts").size());
    }

    @Test
    void unknownFactIsExcludedFromEveryContext() {
        ledger.put("facts", fact("f.other", "Brin owes a debt", "npc.brin"));

        JsonObject ctxAlden = NpcContext.resolve(ledger, "npc.alden");
        assertEquals(0, ctxAlden.getAsJsonArray("facts").size());
    }

    @Test
    void mixedFactsResolveIndependentlyPerNpc() {
        ledger.put("facts", fact("f.a", "only alden", "npc.alden"));
        ledger.put("facts", fact("f.b", "only brin", "npc.brin"));
        ledger.put("facts", fact("f.c", "everyone", "all"));
        ledger.put("facts", fact("f.d", "millers only", "faction:millers"));

        JsonObject ctxAlden = NpcContext.resolve(ledger, "npc.alden");
        JsonArray aldenFacts = ctxAlden.getAsJsonArray("facts");
        assertEquals(3, aldenFacts.size()); // f.a, f.c, f.d

        JsonObject ctxBrin = NpcContext.resolve(ledger, "npc.brin");
        JsonArray brinFacts = ctxBrin.getAsJsonArray("facts");
        assertEquals(2, brinFacts.size()); // f.b, f.c
    }

    @Test
    void resolveThrowsForUnknownNpc() {
        assertThrows(IllegalArgumentException.class, () -> NpcContext.resolve(ledger, "npc.ghost"));
    }

    @Test
    void resolveIncludesTheNpcsOwnSheet() {
        JsonObject ctx = NpcContext.resolve(ledger, "npc.alden");
        assertEquals("Alden", ctx.getAsJsonObject("npc").get("name").getAsString());
    }
}
