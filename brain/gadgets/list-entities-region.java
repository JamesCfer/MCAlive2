package dev.celestia.mcalive2.gadget;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

public class ListEntitiesRegion implements GadgetContract {
    @Override
    public JsonObject run(JsonObject args, GadgetContext ctx) {
        String worldName = args.has("world") ? args.get("world").getAsString() : "world";
        double x1 = args.get("x1").getAsDouble();
        double x2 = args.get("x2").getAsDouble();
        double y1 = args.get("y1").getAsDouble();
        double y2 = args.get("y2").getAsDouble();
        double z1 = args.get("z1").getAsDouble();
        double z2 = args.get("z2").getAsDouble();

        World w = ctx.world(worldName);
        JsonObject out = new JsonObject();
        JsonArray arr = new JsonArray();
        if (w == null) {
            out.addProperty("ok", false);
            out.addProperty("error", "no such world: " + worldName);
            return out;
        }
        double minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        double minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        for (Entity e : w.getEntities()) {
            if (!e.isValid()) continue;
            double ex = e.getLocation().getX();
            double ey = e.getLocation().getY();
            double ez = e.getLocation().getZ();
            if (ex < minX || ex > maxX || ey < minY || ey > maxY || ez < minZ || ez > maxZ) continue;
            if (!(e instanceof LivingEntity) && !e.getType().name().contains("MANNEQUIN") ) {
                // still include non-living notable entities, but skip pure projectiles etc.
                if (e.getType().name().contains("ARROW") || e.getType().name().contains("ITEM")) continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.getUniqueId().toString());
            o.addProperty("type", e.getType().name());
            o.addProperty("name", e.getCustomName() != null ? e.getCustomName() : e.getName());
            o.addProperty("x", Math.round(ex * 100.0) / 100.0);
            o.addProperty("y", Math.round(ey * 100.0) / 100.0);
            o.addProperty("z", Math.round(ez * 100.0) / 100.0);
            arr.add(o);
        }
        out.addProperty("ok", true);
        out.add("entities", arr);
        return out;
    }
}
