package id.rnggagib.performance;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Packet culling via ProtocolLib using reflection only (no compile-time dependency).
 * If ProtocolLib is present, we register a listener for SPAWN_ENTITY(_LIVING) and
 * cancel packets that are very likely culled for the receiver using the quick heuristic.
 */
public final class PacketCullingReflectService {
    private final Plugin plugin;
    private final Logger logger;
    private final CullingService culling;
    private boolean enabled;
    // kept for future use if we decode entity type from packet
    // private Set<String> excludeTypes = Set.of("PLAYER", "ARMOR_STAND");
    private Object protocolManager; // com.comphenix.protocol.ProtocolManager
    private Object packetListener;  // com.comphenix.protocol.events.PacketListener (dynamic proxy)

    // Small LRU cache for recent (viewerId, x, y, z) decisions to avoid duplicate math
    private final java.util.Map<Long, Boolean> decisionCache = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
            return size() > 512; // cap
        }
    };

    public PacketCullingReflectService(Plugin plugin, Logger logger, CullingService culling) {
        this.plugin = plugin;
        this.logger = logger;
        this.culling = culling;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.packet-culling.enabled", false);
    // Exclude types configurable for future packet type decoding; currently unused in reflection mode
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Packet culling disabled"); return; }

        try {
            Class<?> protocolLibraryCls = Class.forName("com.comphenix.protocol.ProtocolLibrary", false, Bukkit.getServer().getClass().getClassLoader());
            Method getPM = protocolLibraryCls.getMethod("getProtocolManager");
            protocolManager = getPM.invoke(null);
        } catch (Throwable t) {
            logger.info("ProtocolLib not found; packet culling skipped");
            return;
        }

        try {
            // Resolve required ProtocolLib classes via reflection
            ClassLoader cl = protocolManager.getClass().getClassLoader();
            Class<?> packetTypeCls = Class.forName("com.comphenix.protocol.PacketType", false, cl);
            Class<?> packetTypePlayServerCls = Class.forName("com.comphenix.protocol.PacketType$Play$Server", false, cl);
            Class<?> listenerPriorityCls = Class.forName("com.comphenix.protocol.events.ListenerPriority", false, cl);
            // optional classes may not exist across versions
            Class<?> listeningWhitelistCls = Class.forName("com.comphenix.protocol.events.ListeningWhitelist", false, cl);
            Class<?> packetListenerItf = Class.forName("com.comphenix.protocol.events.PacketListener", false, cl);
            Class<?> packetEventCls = Class.forName("com.comphenix.protocol.events.PacketEvent", false, cl);
            Class<?> packetContainerCls = Class.forName("com.comphenix.protocol.events.PacketContainer", false, cl);

            // Packet types: SPAWN_ENTITY and SPAWN_ENTITY_LIVING (if present)
            Object spawnEntity = packetTypePlayServerCls.getField("SPAWN_ENTITY").get(null);
            Object spawnLiving;
            try {
                spawnLiving = packetTypePlayServerCls.getField("SPAWN_ENTITY_LIVING").get(null);
            } catch (NoSuchFieldException nsf) {
                spawnLiving = null; // Some versions may differ
            }

            // Build ListeningWhitelist for sending packets using builder pattern
            // ListeningWhitelist.newBuilder().priority(ListenerPriority.NORMAL).types(PacketType...)
            Class<?> builderCls = Class.forName("com.comphenix.protocol.events.ListeningWhitelist$Builder", false, cl);
            Method newBuilder = listeningWhitelistCls.getMethod("newBuilder");
            Object builder = newBuilder.invoke(null);
            Method bPriority = builderCls.getMethod("priority", listenerPriorityCls);
            Method bTypes = builderCls.getMethod("types", Class.forName("[Lcom.comphenix.protocol.PacketType;", false, cl));
            try { builderCls.getMethod("gamePhase", Class.forName("com.comphenix.protocol.events.GamePhase", false, cl)); } catch (Throwable ignored) {}
            Method bBuild = builderCls.getMethod("build");

            Object priorityNormal = listenerPriorityCls.getMethod("valueOf", String.class).invoke(null, "NORMAL");
            bPriority.invoke(builder, priorityNormal);

            Object typesArray;
            if (spawnLiving != null) {
                typesArray = Array.newInstance(packetTypeCls, 2);
                Array.set(typesArray, 0, spawnEntity);
                Array.set(typesArray, 1, spawnLiving);
            } else {
                typesArray = Array.newInstance(packetTypeCls, 1);
                Array.set(typesArray, 0, spawnEntity);
            }
            bTypes.invoke(builder, typesArray);
            Object sendingWhitelist = bBuild.invoke(builder);

            // Empty receiving whitelist
            Object recvBuilder = newBuilder.invoke(null);
            bPriority.invoke(recvBuilder, priorityNormal);
            Object receivingWhitelist = bBuild.invoke(recvBuilder);

            // Create dynamic proxy for PacketListener
            packetListener = Proxy.newProxyInstance(cl, new Class[]{packetListenerItf}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if (name.equals("getPlugin")) return plugin;
                    if (name.equals("getListeningWhitelist") || name.equals("getSendingWhitelist")) return sendingWhitelist;
                    if (name.equals("getReceivingWhitelist")) return receivingWhitelist;
                    if (name.equals("onPacketSending")) {
                        Object packetEvent = args[0];
                        // boolean isCancelled()
                        boolean cancelled = (boolean) packetEventCls.getMethod("isCancelled").invoke(packetEvent);
                        if (cancelled) return null;
                        Player viewer = (Player) packetEventCls.getMethod("getPlayer").invoke(packetEvent);
                        Object container = packetEventCls.getMethod("getPacket").invoke(packetEvent);

                        // Extract position doubles if present (indices differ by packet type, fallback safe reads)
                        double x = 0, y = 0, z = 0;
                        try {
                            Object doubles = packetContainerCls.getMethod("getDoubles").invoke(container);
                            // StructureModifier<Double> with method read(int)
                            Class<?> smCls = doubles.getClass();
                            x = ((Number) smCls.getMethod("read", int.class).invoke(doubles, 0)).doubleValue();
                            y = ((Number) smCls.getMethod("read", int.class).invoke(doubles, 1)).doubleValue();
                            z = ((Number) smCls.getMethod("read", int.class).invoke(doubles, 2)).doubleValue();
                        } catch (Throwable ignore) { /* Some versions store locations differently; skip if not available */ }

                        var vloc = viewer.getLocation();
                        double dx = x - vloc.getX();
                        double dy = y - vloc.getY();
                        double dz = z - vloc.getZ();
                        double distance = Math.sqrt(dx*dx + dy*dy + dz*dz);
                        var dir = new org.bukkit.util.Vector(dx, dy, dz);
                        if (dir.lengthSquared() > 1e-9) dir.normalize();
                        var view = vloc.getDirection().normalize();
                        double cos = view.dot(dir);
                        double speed = 0.0;

                        // Build a compact key: 24 bits per coord after quantization + 16 bits of viewer hash
                        long key = 0L;
                        try {
                            int qx = (int) Math.round(x * 4.0); // quarter-block precision
                            int qy = (int) Math.round(y * 4.0);
                            int qz = (int) Math.round(z * 4.0);
                            int vh = viewer.getUniqueId().hashCode() & 0xFFFF;
                            key = (((long) (qx & 0xFFFFFF)) << 40) | (((long) (qy & 0xFFFFFF)) << 16) | ((long) (qz & 0xFFFF)) | (((long) vh) << 56);
                        } catch (Throwable ignore) {}

                        Boolean cached;
                        synchronized (decisionCache) { cached = decisionCache.get(key); }
                        boolean shouldCull = cached != null ? cached : culling.quickShouldCull(distance, speed, cos);
                        if (cached == null) { synchronized (decisionCache) { decisionCache.put(key, shouldCull); } }

                        if (shouldCull) {
                            // event.setCancelled(true)
                            packetEventCls.getMethod("setCancelled", boolean.class).invoke(packetEvent, true);
                        }
                        return null;
                    }
                    // Ignore other methods: onPacketReceiving, onTick, onAdd, onRemove
                    return null;
                }
            });

            // protocolManager.addPacketListener(PacketListener)
            Method add = protocolManager.getClass().getMethod("addPacketListener", packetListener.getClass().getInterfaces()[0]);
            add.invoke(protocolManager, packetListener);
            logger.info("Packet culling enabled (ProtocolLib via reflection)");
        } catch (Throwable t) {
            logger.warn("Failed to enable packet culling via reflection: {}", t.toString());
        }
    }

    public void stop() {
        if (protocolManager != null && packetListener != null) {
            try {
                Method remove = protocolManager.getClass().getMethod("removePacketListener", Class.forName("com.comphenix.protocol.events.PacketListener", false, protocolManager.getClass().getClassLoader()));
                remove.invoke(protocolManager, packetListener);
            } catch (Throwable ignored) { }
            packetListener = null;
        }
    }
}
