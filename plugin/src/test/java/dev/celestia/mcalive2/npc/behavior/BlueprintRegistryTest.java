package dev.celestia.mcalive2.npc.behavior;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintRegistryTest {

    private JsonObject block(int dx, int dy, int dz, String material) {
        JsonObject o = new JsonObject();
        o.addProperty("dx", dx);
        o.addProperty("dy", dy);
        o.addProperty("dz", dz);
        o.addProperty("material", material);
        return o;
    }

    private JsonObject spec(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        JsonObject origin = new JsonObject();
        origin.addProperty("x", 100);
        origin.addProperty("y", 64);
        origin.addProperty("z", -40);
        o.add("origin", origin);
        JsonArray blocks = new JsonArray();
        blocks.add(block(0, 0, 0, "oak_log"));
        blocks.add(block(1, 0, 0, "oak_planks"));
        JsonObject withData = block(2, 0, 0, "oak_stairs");
        withData.addProperty("data", "minecraft:oak_stairs[facing=north]");
        blocks.add(withData);
        o.add("blocks", blocks);
        return o;
    }

    @Test
    void registersAndRetrievesABlueprint() {
        BlueprintRegistry registry = new BlueprintRegistry();
        BlueprintRegistry.Blueprint bp = registry.register(spec("granary"), 1000, 256);
        assertEquals("granary", bp.id());
        assertEquals(100, bp.ox());
        assertEquals(64, bp.oy());
        assertEquals(-40, bp.oz());
        assertEquals(3, bp.blocks().size());
        assertEquals("minecraft:oak_stairs[facing=north]", bp.blocks().get(2).data());
        assertSame(bp, registry.get("granary"));
    }

    @Test
    void rejectsMissingOriginAndEmptyBlocks() {
        BlueprintRegistry registry = new BlueprintRegistry();
        JsonObject noOrigin = spec("bad");
        noOrigin.remove("origin");
        assertThrows(IllegalArgumentException.class, () -> registry.register(noOrigin, 1000, 256));

        JsonObject noBlocks = spec("bad");
        noBlocks.add("blocks", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> registry.register(noBlocks, 1000, 256));
    }

    @Test
    void enforcesBlueprintParserCaps() {
        BlueprintRegistry registry = new BlueprintRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(spec("too-big"), 2, 256));
        JsonObject farOffset = spec("too-far");
        farOffset.getAsJsonArray("blocks").add(block(500, 0, 0, "oak_log"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(farOffset, 1000, 256));
    }

    @Test
    void roundTripsThroughSaveAndLoad(@TempDir File dir) throws Exception {
        BlueprintRegistry registry = new BlueprintRegistry();
        registry.register(spec("granary"), 1000, 256);
        registry.register(spec("mill"), 1000, 256);
        File file = new File(dir, "blueprints.json");
        registry.save(file);

        BlueprintRegistry loaded = new BlueprintRegistry();
        loaded.load(file, 1000, 256);
        assertEquals(2, loaded.all().size());
        BlueprintRegistry.Blueprint bp = loaded.get("granary");
        assertNotNull(bp);
        assertEquals(100, bp.ox());
        assertEquals(3, bp.blocks().size());
        assertEquals("oak_planks", bp.blocks().get(1).material());
        assertEquals("minecraft:oak_stairs[facing=north]", bp.blocks().get(2).data());
    }

    @Test
    void deleteRemovesAndLoadIgnoresMissingFile(@TempDir File dir) throws Exception {
        BlueprintRegistry registry = new BlueprintRegistry();
        registry.register(spec("granary"), 1000, 256);
        assertTrue(registry.delete("granary"));
        assertFalse(registry.delete("granary"));
        assertNull(registry.get("granary"));

        registry.load(new File(dir, "nope.json"), 1000, 256); // no throw
        assertTrue(registry.all().isEmpty());
    }
}
