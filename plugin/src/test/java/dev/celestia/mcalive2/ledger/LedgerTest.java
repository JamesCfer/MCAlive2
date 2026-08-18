package dev.celestia.mcalive2.ledger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LedgerTest {

    private JsonObject record(String id, String... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }

    @Test
    void putAndGetRoundTrips() {
        Ledger ledger = new Ledger();
        JsonObject npc = record("npc.alden", "name", "Alden");
        ledger.put("npcs", npc);

        JsonObject fetched = ledger.get("npcs", "npc.alden");
        assertNotNull(fetched);
        assertEquals("Alden", fetched.get("name").getAsString());
    }

    @Test
    void putRejectsMissingId() {
        Ledger ledger = new Ledger();
        JsonObject noId = new JsonObject();
        noId.addProperty("name", "Nameless");
        assertThrows(IllegalArgumentException.class, () -> ledger.put("npcs", noId));
    }

    @Test
    void putRejectsUnknownCollection() {
        Ledger ledger = new Ledger();
        assertThrows(IllegalArgumentException.class, () -> ledger.put("monsters", record("m1")));
    }

    @Test
    void putReplacesExistingRecordById() {
        Ledger ledger = new Ledger();
        ledger.put("npcs", record("npc.alden", "mood", "cheerful"));
        ledger.put("npcs", record("npc.alden", "mood", "grim"));
        assertEquals("grim", ledger.get("npcs", "npc.alden").get("mood").getAsString());
        assertEquals(1, ledger.all("npcs").size());
    }

    @Test
    void deleteRemovesRecord() {
        Ledger ledger = new Ledger();
        ledger.put("places", record("place.mill"));
        assertTrue(ledger.delete("places", "place.mill"));
        assertNull(ledger.get("places", "place.mill"));
        assertFalse(ledger.delete("places", "place.mill")); // already gone
    }

    @Test
    void queryFiltersByScalarSubsetMatch() {
        Ledger ledger = new Ledger();
        ledger.put("quests", record("q1", "giverNpc", "npc.alden", "state", "active"));
        ledger.put("quests", record("q2", "giverNpc", "npc.brin", "state", "active"));
        ledger.put("quests", record("q3", "giverNpc", "npc.alden", "state", "done"));

        JsonObject filter = new JsonObject();
        filter.addProperty("giverNpc", "npc.alden");
        filter.addProperty("state", "active");
        List<JsonObject> results = ledger.query("quests", filter);

        assertEquals(1, results.size());
        assertEquals("q1", results.get(0).get("id").getAsString());
    }

    @Test
    void queryWithNullFilterReturnsEverything() {
        Ledger ledger = new Ledger();
        ledger.put("places", record("p1"));
        ledger.put("places", record("p2"));
        assertEquals(2, ledger.query("places", null).size());
    }

    @Test
    void queryMatchesArrayFieldsByContainment() {
        Ledger ledger = new Ledger();
        JsonObject fact = record("f1");
        JsonArray knownBy = new JsonArray();
        knownBy.add("npc.alden");
        knownBy.add("faction:millers");
        fact.add("knownBy", knownBy);
        ledger.put("facts", fact);

        JsonObject filter = new JsonObject();
        filter.addProperty("knownBy", "npc.alden");
        assertEquals(1, ledger.query("facts", filter).size());

        JsonObject miss = new JsonObject();
        miss.addProperty("knownBy", "npc.brin");
        assertEquals(0, ledger.query("facts", miss).size());
    }

    @Test
    void saveAndLoadRoundTripsAllCollections(@TempDir File tempDir) throws Exception {
        Ledger ledger = new Ledger();
        ledger.put("npcs", record("npc.alden", "name", "Alden"));
        ledger.put("facts", record("fact.1", "text", "The well ran dry"));

        File dir = new File(tempDir, "ledger");
        ledger.save(dir);

        Ledger reloaded = new Ledger();
        reloaded.load(dir);

        assertEquals("Alden", reloaded.get("npcs", "npc.alden").get("name").getAsString());
        assertEquals("The well ran dry", reloaded.get("facts", "fact.1").get("text").getAsString());
        assertTrue(reloaded.all("places").isEmpty());
    }
}
