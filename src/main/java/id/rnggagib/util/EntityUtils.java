package id.rnggagib.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.TextDisplay;

import java.util.UUID;

public final class EntityUtils {
    private EntityUtils() {}

    public static TextDisplay findTextDisplay(UUID id) {
        if (id == null) return null;
        for (World w : Bukkit.getWorlds()) {
            Entity e = w.getEntity(id);
            if (e instanceof TextDisplay td) return td;
        }
        return null;
    }

    public static Item findItem(UUID id) {
        if (id == null) return null;
        for (World w : Bukkit.getWorlds()) {
            Entity e = w.getEntity(id);
            if (e instanceof Item it) return it;
        }
        return null;
    }
}
