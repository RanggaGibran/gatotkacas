// NOTE: Requires ProtocolLib at compile time. Disabled by default.
package id.rnggagib.performance;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.Set;

public final class PacketCullingService {
    private final Plugin plugin;
    private final Logger logger;
    private final CullingService culling;
    private ProtocolManager pm;
    private boolean enabled;
    private Set<String> excludeTypes = java.util.Set.of("PLAYER", "ARMOR_STAND");

    public PacketCullingService(Plugin plugin, Logger logger, CullingService culling) {
        this.plugin = plugin;
        this.logger = logger;
        this.culling = culling;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.packet-culling.enabled", false);
        excludeTypes = new java.util.HashSet<>(cfg.getStringList("features.packet-culling.exclude-types"));
        if (((java.util.HashSet<String>) excludeTypes).isEmpty()) {
            excludeTypes = java.util.Set.of("PLAYER", "ARMOR_STAND");
        }
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Packet culling disabled"); return; }
        try {
            pm = ProtocolLibrary.getProtocolManager();
        } catch (Throwable t) {
            logger.info("ProtocolLib not found; packet culling skipped");
            return;
        }
        pm.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.SPAWN_ENTITY, PacketType.Play.Server.SPAWN_ENTITY_LIVING) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) return;
                Player viewer = event.getPlayer();
                var container = event.getPacket();
                // Get entity id & position
                Integer eid = null;
                double x = 0, y = 0, z = 0;
                try {
                    // Spawn entity packet positions
                    x = container.getDoubles().read(0);
                    y = container.getDoubles().read(1);
                    z = container.getDoubles().read(2);
                    eid = container.getIntegers().readSafely(0);
                } catch (Throwable ignore) { }
                if (eid == null) return;
                var world = viewer.getWorld();
                var ent = world.getEntity(java.util.UUID.nameUUIDFromBytes(("prot-"+eid).getBytes()));
                // We may not have the live entity yet; approximate using viewer context
                if (ent != null && excludeTypes.contains(ent.getType().name())) return;

                // Approx compute using viewer and packet pos
                var vloc = viewer.getLocation();
                double dx = x - vloc.getX();
                double dy = y - vloc.getY();
                double dz = z - vloc.getZ();
                double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
                var dir = new org.bukkit.util.Vector(dx, dy, dz).normalize();
                var view = vloc.getDirection().normalize();
                double cos = view.dot(dir);
                double speed = 0.0; // unknown at spawn, treat as stationary

                if (culling.quickShouldCull(distance, speed, cos)) {
                    event.setCancelled(true);
                }
            }
        });
        logger.info("Packet culling enabled");
    }

    public void stop() {
        if (pm != null) {
            pm.removePacketListeners(plugin);
            pm = null;
        }
    }
}
