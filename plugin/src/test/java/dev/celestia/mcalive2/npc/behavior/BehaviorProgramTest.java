package dev.celestia.mcalive2.npc.behavior;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorProgramTest {

    private JsonObject pos(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    private JsonObject gatherStep() {
        JsonObject step = new JsonObject();
        step.addProperty("type", "gather");
        step.addProperty("material", "OAK_LOG");
        step.addProperty("count", 16);
        step.addProperty("radius", 12);
        step.add("anchor", pos(100, 64, -20));
        return step;
    }

    private JsonObject depositAllStep() {
        JsonObject step = new JsonObject();
        step.addProperty("type", "deposit");
        step.add("chest", pos(90, 64, -20));
        step.addProperty("items", "all");
        return step;
    }

    private JsonObject program(String id, JsonObject... steps) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", "Logging crew");
        JsonArray ids = new JsonArray();
        ids.add("woodcutter-1");
        ids.add("woodcutter-2");
        o.add("npcIds", ids);
        JsonArray arr = new JsonArray();
        for (JsonObject s : steps) arr.add(s);
        o.add("steps", arr);
        return o;
    }

    @Test
    void roundTripsThroughJson() {
        BehaviorProgram p = BehaviorProgram.fromJson(program("logging", gatherStep(), depositAllStep()));
        p.loop = true;
        p.createdAt = "2026-08-19T00:00:00Z";
        p.resetCursors();
        BehaviorProgram.Cursor c = p.cursors.get("woodcutter-1");
        c.step = 1;
        c.progress.addProperty("tx", 101);
        c.carrying.put("OAK_LOG", 7);
        p.placedFor(1).add(3);
        p.placedFor(1).add(5);

        BehaviorProgram back = BehaviorProgram.fromJson(p.toJson());
        assertEquals("logging", back.id);
        assertEquals("Logging crew", back.name);
        assertEquals(2, back.npcIds.size());
        assertTrue(back.loop);
        assertFalse(back.paused);
        assertEquals(2, back.steps.size());
        assertEquals("gather", back.stepType(0));
        assertEquals("deposit", back.stepType(1));
        assertEquals("2026-08-19T00:00:00Z", back.createdAt);

        BehaviorProgram.Cursor backCursor = back.cursors.get("woodcutter-1");
        assertEquals(1, backCursor.step);
        assertEquals(101, backCursor.progress.get("tx").getAsInt());
        assertEquals(7, backCursor.carrying.get("OAK_LOG"));
        assertEquals(0, back.cursors.get("woodcutter-2").step);
        assertTrue(back.cursors.get("woodcutter-2").carrying.isEmpty());
        assertEquals(java.util.Set.of(3, 5), back.placedFor(1));
    }

    @Test
    void rejectsUnknownStepType() {
        JsonObject step = new JsonObject();
        step.addProperty("type", "teleport");
        step.add("anchor", pos(0, 64, 0));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BehaviorProgram.fromJson(program("bad", step)));
        assertTrue(e.getMessage().contains("unknown type"));
    }

    @Test
    void rejectsMissingRequiredFields() {
        JsonObject noMaterial = gatherStep();
        noMaterial.remove("material");
        assertThrows(IllegalArgumentException.class, () -> BehaviorProgram.fromJson(program("bad", noMaterial)));

        JsonObject noAnchor = gatherStep();
        noAnchor.remove("anchor");
        assertThrows(IllegalArgumentException.class, () -> BehaviorProgram.fromJson(program("bad", noAnchor)));

        JsonObject badWait = new JsonObject();
        badWait.addProperty("type", "wait");
        assertThrows(IllegalArgumentException.class, () -> BehaviorProgram.fromJson(program("bad", badWait)));
    }

    @Test
    void rejectsEmptyCrewAndEmptySteps() {
        JsonObject noSteps = program("empty");
        assertThrows(IllegalArgumentException.class, () -> BehaviorProgram.fromJson(noSteps));

        JsonObject noCrew = program("no-crew", gatherStep());
        noCrew.add("npcIds", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> BehaviorProgram.fromJson(noCrew));
    }

    @Test
    void validatesEverySpecifiedStepType() {
        JsonObject build = new JsonObject();
        build.addProperty("type", "build");
        build.addProperty("blueprintId", "granary");
        build.add("supplyChest", pos(1, 64, 1));

        JsonObject withdraw = new JsonObject();
        withdraw.addProperty("type", "withdraw");
        withdraw.add("chest", pos(2, 64, 2));
        JsonArray items = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("material", "BREAD");
        item.addProperty("count", 3);
        items.add(item);
        withdraw.add("items", items);

        JsonObject work = new JsonObject();
        work.addProperty("type", "work_station");
        work.add("station", pos(3, 64, 3));
        work.addProperty("workTicks", 200);

        JsonObject go = new JsonObject();
        go.addProperty("type", "goto");
        go.addProperty("x", 4);
        go.addProperty("y", 64);
        go.addProperty("z", 4);

        JsonObject wait = new JsonObject();
        wait.addProperty("type", "wait");
        wait.addProperty("ticks", 100);

        JsonObject follow = new JsonObject();
        follow.addProperty("type", "follow");
        follow.addProperty("npcId", "woodcutter-1");
        follow.addProperty("ticks", 200);
        follow.addProperty("distance", 4);

        BehaviorProgram p = BehaviorProgram.fromJson(
                program("all-types", gatherStep(), build, depositAllStep(), withdraw, work, go, wait, follow));
        assertEquals(8, p.steps.size());
    }

    @Test
    void depositItemsMustBeAllOrAList() {
        JsonObject bad = depositAllStep();
        bad.addProperty("items", "everything");
        assertThrows(IllegalArgumentException.class, () -> BehaviorProgram.fromJson(program("bad", bad)));
    }

    @Test
    void storeRoundTripsThroughSaveAndLoad(@org.junit.jupiter.api.io.TempDir java.io.File dir) throws Exception {
        BehaviorStore store = new BehaviorStore();
        BehaviorProgram p = BehaviorProgram.fromJson(program("logging", gatherStep(), depositAllStep()));
        p.resetCursors();
        p.cursors.get("woodcutter-2").carrying.put("OAK_LOG", 4);
        store.put(p);
        java.io.File file = new java.io.File(dir, "behaviors.json");
        store.save(file);

        BehaviorStore loaded = new BehaviorStore();
        loaded.load(file);
        assertEquals(1, loaded.all().size());
        assertEquals(4, loaded.get("logging").cursors.get("woodcutter-2").carrying.get("OAK_LOG"));
    }

    @Test
    void resetCursorsGivesEveryCrewMemberAFreshCursor() {
        BehaviorProgram p = BehaviorProgram.fromJson(program("logging", gatherStep()));
        p.resetCursors();
        p.cursors.get("woodcutter-1").step = 1;
        p.cursors.get("woodcutter-1").carrying.put("OAK_LOG", 9);
        p.placedFor(0).add(1);
        p.resetCursors();
        assertEquals(2, p.cursors.size());
        assertEquals(0, p.cursors.get("woodcutter-1").step);
        assertTrue(p.cursors.get("woodcutter-1").carrying.isEmpty());
        assertTrue(p.placed.isEmpty());
    }
}
