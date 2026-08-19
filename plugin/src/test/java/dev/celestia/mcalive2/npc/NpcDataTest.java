package dev.celestia.mcalive2.npc;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON roundtrip for NpcData. Location fields are exercised only as "absent stays
 *  absent" - resolving a world name back to a Location needs a live server. */
class NpcDataTest {

    private static NpcData sample() {
        NpcData d = new NpcData();
        d.id = "mira";
        d.name = "Mira the Baker";
        d.entityType = "MANNEQUIN";
        d.profession = "farmer";
        d.skin = "Notch";
        d.defense = "flee";
        d.attackDamage = 4.5;
        d.dead = true;
        d.diedAt = "2026-08-19T12:00:00Z";
        NpcData.ScheduleEntry e = new NpcData.ScheduleEntry();
        e.start = 1000;
        e.action = "goto_work";
        e.radius = 12;
        d.schedule.add(e);
        return d;
    }

    @Test
    void roundTripPreservesEveryField() {
        NpcData d = NpcData.fromJson(sample().toJson());
        assertEquals("mira", d.id);
        assertEquals("Mira the Baker", d.name);
        assertEquals("MANNEQUIN", d.entityType);
        assertEquals("farmer", d.profession);
        assertEquals("Notch", d.skin);
        assertEquals("flee", d.defense);
        assertEquals(4.5, d.attackDamage);
        assertTrue(d.dead);
        assertEquals("2026-08-19T12:00:00Z", d.diedAt);
        assertEquals(1, d.schedule.size());
        assertEquals(1000, d.schedule.get(0).start);
        assertEquals("goto_work", d.schedule.get(0).action);
        assertEquals(12, d.schedule.get(0).radius);
        assertNull(d.home);
        assertNull(d.work);
        assertNull(d.lastLocation);
    }

    @Test
    void defenseDefaultsApplyToRecordsSavedBeforeTheFieldExisted() {
        // a pre-0.6 npcs.json entry has neither defense nor attackDamage
        JsonObject legacy = new JsonObject();
        legacy.addProperty("id", "old");
        legacy.addProperty("name", "Old Timer");
        NpcData d = NpcData.fromJson(legacy);
        assertEquals("fight", d.defense);
        assertEquals(0, d.attackDamage);
        assertFalse(d.dead);
    }

    @Test
    void zeroAttackDamageIsOmittedFromJson() {
        NpcData d = sample();
        d.attackDamage = 0; // 0 = "use the config default", not worth persisting
        JsonObject o = d.toJson();
        assertFalse(o.has("attackDamage"));
        assertEquals("flee", o.get("defense").getAsString());
    }
}
