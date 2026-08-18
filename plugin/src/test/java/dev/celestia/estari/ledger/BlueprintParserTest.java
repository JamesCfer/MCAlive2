package dev.celestia.estari.ledger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintParserTest {

    private JsonObject entry(int dx, int dy, int dz, String material) {
        JsonObject o = new JsonObject();
        o.addProperty("dx", dx);
        o.addProperty("dy", dy);
        o.addProperty("dz", dz);
        o.addProperty("material", material);
        return o;
    }

    @Test
    void parsesValidBlueprint() {
        JsonArray bp = new JsonArray();
        bp.add(entry(0, 0, 0, "stone"));
        bp.add(entry(1, 0, 0, "oak_planks"));

        List<BlueprintParser.BlockEntry> entries = BlueprintParser.parse(bp, 1000, 256);
        assertEquals(2, entries.size());
        assertEquals("stone", entries.get(0).material());
        assertEquals(1, entries.get(1).dx());
    }

    @Test
    void parsesOptionalBlockData() {
        JsonArray bp = new JsonArray();
        JsonObject e = entry(0, 0, 0, "oak_stairs");
        e.addProperty("data", "facing=north");
        bp.add(e);

        List<BlueprintParser.BlockEntry> entries = BlueprintParser.parse(bp, 1000, 256);
        assertEquals("facing=north", entries.get(0).data());
    }

    @Test
    void rejectsNullBlueprint() {
        assertThrows(IllegalArgumentException.class, () -> BlueprintParser.parse(null, 1000, 256));
    }

    @Test
    void rejectsEmptyBlueprint() {
        assertThrows(IllegalArgumentException.class, () -> BlueprintParser.parse(new JsonArray(), 1000, 256));
    }

    @Test
    void rejectsBlockCountOverCap() {
        JsonArray bp = new JsonArray();
        for (int i = 0; i < 5; i++) bp.add(entry(i, 0, 0, "stone"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintParser.parse(bp, 4, 256));
    }

    @Test
    void rejectsOffsetOverCap() {
        JsonArray bp = new JsonArray();
        bp.add(entry(500, 0, 0, "stone")); // exceeds maxOffset of 256
        assertThrows(IllegalArgumentException.class, () -> BlueprintParser.parse(bp, 1000, 256));
    }

    @Test
    void rejectsNegativeOffsetOverCap() {
        JsonArray bp = new JsonArray();
        bp.add(entry(0, -500, 0, "stone"));
        assertThrows(IllegalArgumentException.class, () -> BlueprintParser.parse(bp, 1000, 256));
    }

    @Test
    void rejectsMissingMaterial() {
        JsonArray bp = new JsonArray();
        JsonObject e = new JsonObject();
        e.addProperty("dx", 0);
        e.addProperty("dy", 0);
        e.addProperty("dz", 0);
        bp.add(e);
        assertThrows(IllegalArgumentException.class, () -> BlueprintParser.parse(bp, 1000, 256));
    }

    @Test
    void rejectsBlankMaterial() {
        JsonArray bp = new JsonArray();
        bp.add(entry(0, 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> BlueprintParser.parse(bp, 1000, 256));
    }

    @Test
    void rejectsNonIntegerOffsets() {
        JsonArray bp = new JsonArray();
        JsonObject e = new JsonObject();
        e.addProperty("dx", 1.5);
        e.addProperty("dy", 0);
        e.addProperty("dz", 0);
        e.addProperty("material", "stone");
        bp.add(e);
        assertThrows(IllegalArgumentException.class, () -> BlueprintParser.parse(bp, 1000, 256));
    }

    @Test
    void rejectsNonObjectEntries() {
        JsonArray bp = new JsonArray();
        bp.add("not-an-object");
        assertThrows(IllegalArgumentException.class, () -> BlueprintParser.parse(bp, 1000, 256));
    }
}
