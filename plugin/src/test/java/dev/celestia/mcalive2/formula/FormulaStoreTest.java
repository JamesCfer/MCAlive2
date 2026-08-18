package dev.celestia.mcalive2.formula;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class FormulaStoreTest {

    private static final Predicate<String> ALL_REGISTERED = cmd -> true;
    private static final Predicate<String> ONLY_NPC_SAY = cmd -> Set.of("npc_say", "npc_walk_to").contains(cmd);

    private JsonObject step(String cmd) {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", cmd);
        JsonObject args = new JsonObject();
        args.addProperty("text", "hi");
        o.add("args", args);
        return o;
    }

    private JsonObject formula(String id, String... cmds) {
        JsonObject f = new JsonObject();
        f.addProperty("id", id);
        f.addProperty("description", "a test formula");
        JsonArray steps = new JsonArray();
        for (String c : cmds) steps.add(step(c));
        f.add("steps", steps);
        return f;
    }

    @Test
    void definesAndRetrievesAFormula() {
        FormulaStore store = new FormulaStore();
        JsonObject f = formula("greet", "npc_say");
        store.define(f, ALL_REGISTERED);

        JsonObject got = store.get("greet");
        assertNotNull(got);
        assertEquals("greet", got.get("id").getAsString());
    }

    @Test
    void defineOverwritesExistingId() {
        FormulaStore store = new FormulaStore();
        store.define(formula("f1", "npc_say"), ALL_REGISTERED);
        JsonObject updated = formula("f1", "npc_say", "npc_walk_to");
        store.define(updated, ALL_REGISTERED);

        assertEquals(2, store.get("f1").getAsJsonArray("steps").size());
        assertEquals(1, store.all().size());
    }

    @Test
    void rejectsUnregisteredCommand() {
        FormulaStore store = new FormulaStore();
        JsonObject f = formula("bad", "not_a_real_command");
        assertThrows(IllegalArgumentException.class, () -> store.define(f, ALL_REGISTERED.negate()));
    }

    @Test
    void rejectsFormulaRecursion() {
        FormulaStore store = new FormulaStore();
        JsonObject f = formula("recursive", "formula_run");
        assertThrows(IllegalArgumentException.class, () -> store.define(f, ALL_REGISTERED));
    }

    @Test
    void rejectsNpcAssignJob() {
        FormulaStore store = new FormulaStore();
        JsonObject f = formula("cheaty", "npc_assign_job");
        assertThrows(IllegalArgumentException.class, () -> store.define(f, ALL_REGISTERED));
    }

    @Test
    void validatesAgainstSuppliedWhitelist() {
        FormulaStore store = new FormulaStore();
        store.define(formula("ok", "npc_say"), ONLY_NPC_SAY); // should not throw
        assertThrows(IllegalArgumentException.class,
                () -> store.define(formula("bad", "set_block"), ONLY_NPC_SAY));
    }

    @Test
    void rejectsMissingSteps() {
        FormulaStore store = new FormulaStore();
        JsonObject f = new JsonObject();
        f.addProperty("id", "empty");
        assertThrows(IllegalArgumentException.class, () -> store.define(f, ALL_REGISTERED));
    }

    @Test
    void rejectsMissingId() {
        FormulaStore store = new FormulaStore();
        JsonObject f = new JsonObject();
        JsonArray steps = new JsonArray();
        steps.add(step("npc_say"));
        f.add("steps", steps);
        assertThrows(IllegalArgumentException.class, () -> store.define(f, ALL_REGISTERED));
    }

    @Test
    void rejectsNonPrimitiveArgValue() {
        FormulaStore store = new FormulaStore();
        JsonObject f = new JsonObject();
        f.addProperty("id", "badarg");
        JsonArray steps = new JsonArray();
        JsonObject s = new JsonObject();
        s.addProperty("cmd", "npc_say");
        JsonObject args = new JsonObject();
        args.add("nested", new JsonObject());
        s.add("args", args);
        steps.add(s);
        f.add("steps", steps);
        assertThrows(IllegalArgumentException.class, () -> store.define(f, ALL_REGISTERED));
    }

    @Test
    void deleteAndList() {
        FormulaStore store = new FormulaStore();
        store.define(formula("a", "npc_say"), ALL_REGISTERED);
        store.define(formula("b", "npc_say"), ALL_REGISTERED);
        assertEquals(2, store.all().size());
        assertTrue(store.delete("a"));
        assertFalse(store.delete("a"));
        assertEquals(1, store.all().size());
        assertNull(store.get("a"));
    }

    @Test
    void saveAndLoadRoundTrips(@TempDir File tmp) throws Exception {
        FormulaStore store = new FormulaStore();
        store.define(formula("persisted", "npc_say"), ALL_REGISTERED);
        File file = new File(tmp, "formulas.json");
        store.save(file);

        FormulaStore reloaded = new FormulaStore();
        reloaded.load(file);
        assertNotNull(reloaded.get("persisted"));
        List<JsonObject> all = reloaded.all();
        assertEquals(1, all.size());
    }

    @Test
    void loadOfMissingFileIsANoop() throws Exception {
        FormulaStore store = new FormulaStore();
        store.load(new File("does-not-exist-formulas.json"));
        assertTrue(store.all().isEmpty());
    }
}
