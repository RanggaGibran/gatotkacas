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
    // Budget config
    private boolean budgetEnabled;
    private int budgetMaxPerTick;
    private double budgetAlwaysSendWithin;
    private int budgetQueueCap;
    private int budgetQueueTtlTicks;
    // kept for future use if we decode entity type from packet
    // private Set<String> excludeTypes = Set.of("PLAYER", "ARMOR_STAND");
    private Object protocolManager; // com.comphenix.protocol.ProtocolManager
    private Object packetListener;  // com.comphenix.protocol.events.PacketListener (dynamic proxy)
    private Method sendServerPacketMethod; // ProtocolManager#sendServerPacket(Player, PacketContainer)
    private Class<?> packetContainerCls;
    private int drainTask = -1;

    // Small LRU cache for recent (viewerId, x, y, z) decisions to avoid duplicate math
    private final java.util.Map<Long, Boolean> decisionCache = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
            return size() > 512; // cap
        }
    };

    // Budget state
    private long tickNow = 0L;
    private final java.util.Map<java.util.UUID, Integer> sentThisTick = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, java.util.ArrayDeque<Queued>> queuedByPlayer = new java.util.HashMap<>();
    private static final class Queued {
        final Object container; final long tick; final double dist;
        Queued(Object c, long t, double d) { this.container = c; this.tick = t; this.dist = d; }
    }

    public PacketCullingReflectService(Plugin plugin, Logger logger, CullingService culling) {
        this.plugin = plugin;
        this.logger = logger;
        this.culling = culling;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.packet-culling.enabled", false);
    // Budget
    budgetEnabled = cfg.getBoolean("features.packet-culling.budget.enabled", false);
    budgetMaxPerTick = Math.max(1, cfg.getInt("features.packet-culling.budget.max-spawns-per-tick", 20));
    budgetAlwaysSendWithin = Math.max(0.0, cfg.getDouble("features.packet-culling.budget.always-send-within", 12.0));
    budgetQueueCap = Math.max(8, cfg.getInt("features.packet-culling.budget.queue-cap", 256));
    budgetQueueTtlTicks = Math.max(20, cfg.getInt("features.packet-culling.budget.queue-ttl-ticks", 100));
    // Exclude types configurable for future packet type decoding; currently unused in reflection mode
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Packet culling disabled"); return; }

        // Resolve ProtocolLib via PluginManager and use its classloader (plugin classloaders are isolated)
        ClassLoader plCl;
        try {
            var pl = Bukkit.getPluginManager().getPlugin("ProtocolLib");
            if (pl == null || !pl.isEnabled()) {
                logger.info("ProtocolLib not found or not enabled; packet culling skipped");
                return;
            }
            plCl = pl.getClass().getClassLoader();
            Class<?> protocolLibraryCls = Class.forName("com.comphenix.protocol.ProtocolLibrary", false, plCl);
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
            packetContainerCls = Class.forName("com.comphenix.protocol.events.PacketContainer", false, cl);
            try { sendServerPacketMethod = protocolManager.getClass().getMethod("sendServerPacket", Player.class, packetContainerCls); } catch (Throwable ignored) {}

            // Packet types: SPAWN_ENTITY and SPAWN_ENTITY_LIVING (if present)
            Object spawnEntity = packetTypePlayServerCls.getField("SPAWN_ENTITY").get(null);
            Object spawnLiving;
            Object worldParticles = null;
            try { worldParticles = packetTypePlayServerCls.getField("WORLD_PARTICLES").get(null); } catch (Throwable ignored) {}
            try {
                spawnLiving = packetTypePlayServerCls.getField("SPAWN_ENTITY_LIVING").get(null);
            } catch (NoSuchFieldException nsf) {
                try {
                    // Fallback name used in some ProtocolLib versions
                    spawnLiving = packetTypePlayServerCls.getField("SPAWN_LIVING").get(null);
                } catch (NoSuchFieldException nsf2) {
                    spawnLiving = null; // Not available
                }
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

            // Filter only supported packet types to avoid "unknown packet" warnings on newer MC where living packet was merged
            java.util.List<Object> supported = new java.util.ArrayList<>();
            try {
                Method isSupported = packetTypeCls.getMethod("isSupported");
                if ((boolean) isSupported.invoke(spawnEntity)) supported.add(spawnEntity);
                if (spawnLiving != null && (boolean) isSupported.invoke(spawnLiving)) supported.add(spawnLiving);
                if (worldParticles != null && (boolean) isSupported.invoke(worldParticles)) supported.add(worldParticles);
            } catch (Throwable ignore) {
                // If API lacks isSupported, fall back to SPAWN_ENTITY only
                supported.clear();
                supported.add(spawnEntity);
            }
            if (supported.isEmpty()) {
                logger.info("Packet culling: no supported spawn packet types on this server; skipping registration");
                return;
            }
            Object typesArray = Array.newInstance(packetTypeCls, supported.size());
            for (int i = 0; i < supported.size(); i++) Array.set(typesArray, i, supported.get(i));
            bTypes.invoke(builder, typesArray);
            Object sendingWhitelist = bBuild.invoke(builder);

            // Empty receiving whitelist
            Object recvBuilder = newBuilder.invoke(null);
            bPriority.invoke(recvBuilder, priorityNormal);
            Object receivingWhitelist = bBuild.invoke(recvBuilder);

            // Create dynamic proxy for PacketListener
            final Object worldParticlesFinal = worldParticles; // capture for inner
            packetListener = Proxy.newProxyInstance(cl, new Class[]{packetListenerItf}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    // Handle Object methods to keep Proxy stable in collections
                    if (method.getDeclaringClass() == Object.class) {
                        switch (name) {
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == (args != null && args.length > 0 ? args[0] : null);
                            case "toString": return "GatotkacasPacketListenerProxy";
                        }
                    }
                    if (name.equals("getPlugin")) return plugin;
            if (name.equals("getPriority")) return priorityNormal;
                    if (name.equals("getListeningWhitelist") || name.equals("getSendingWhitelist")) return sendingWhitelist;
                    if (name.equals("getReceivingWhitelist")) return receivingWhitelist;
                    if (name.equals("onPacketSending")) {
                        Object packetEvent = args[0];
                        // boolean isCancelled()
                        boolean cancelled = (boolean) packetEventCls.getMethod("isCancelled").invoke(packetEvent);
                        if (cancelled) return null;
                        Player viewer = (Player) packetEventCls.getMethod("getPlayer").invoke(packetEvent);
                        Object container = packetEventCls.getMethod("getPacket").invoke(packetEvent);
                        Object ptype = packetEventCls.getMethod("getPacketType").invoke(packetEvent);
                        // Particle downsampling first
                        if (worldParticlesFinal != null && ptype.equals(worldParticlesFinal)) {
                            int pct = 100;
                            try { pct = ((id.rnggagib.Plugin) plugin).particlePercent(viewer); } catch (Throwable ignored) {}
                            if (pct <= 0) { packetEventCls.getMethod("setCancelled", boolean.class).invoke(packetEvent, true); return null; }
                            if (pct >= 100) return null;
                            // sample by hash for stability: player + current tick
                            int h = viewer.getUniqueId().hashCode() ^ (int) tickNow;
                            h = (h ^ (h >>> 16)) & 0x7fffffff;
                            int r = h % 100;
                            if (r >= pct) { packetEventCls.getMethod("setCancelled", boolean.class).invoke(packetEvent, true); }
                            return null;
                        }

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

                        // Citizens NPC exemption: if the target entity at this location has metadata "NPC", skip culling.
                        try {
                            org.bukkit.World w = viewer.getWorld();
                            // Search small radius for an entity with metadata NPC near the spawn position
                            for (org.bukkit.entity.Entity nearby : w.getNearbyEntities(new org.bukkit.Location(w, x, y, z), 0.75, 0.75, 0.75)) {
                                if (nearby.hasMetadata("NPC")) {
                                    java.util.List<org.bukkit.metadata.MetadataValue> mv = nearby.getMetadata("NPC");
                                    boolean npc = false; for (var m : mv) { if (m != null && m.asBoolean()) { npc = true; break; } }
                                    if (npc) { return null; }
                                }
                            }
                        } catch (Throwable ignored) {}
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

                        // Budget check (applies only when we can re-send later)
                        if (budgetEnabled && sendServerPacketMethod != null && !shouldCull) {
                            if (distance > budgetAlwaysSendWithin) {
                                java.util.UUID pid = viewer.getUniqueId();
                                int used; synchronized (sentThisTick) { used = sentThisTick.getOrDefault(pid, 0); }
                                if (used >= budgetMaxPerTick) {
                                    // enqueue and cancel
                                    Object copy = container;
                                    try {
                                        Method deepClone = packetContainerCls.getMethod("deepClone");
                                        copy = deepClone.invoke(container);
                                    } catch (Throwable __) {
                                        try { Method shallow = packetContainerCls.getMethod("shallowClone"); copy = shallow.invoke(container); } catch (Throwable ___) { /* fallback to same ref */ }
                                    }
                                    synchronized (queuedByPlayer) {
                                        var dq = queuedByPlayer.computeIfAbsent(pid, k -> new java.util.ArrayDeque<Queued>());
                                        dq.addLast(new Queued(copy, tickNow, distance));
                                        // enforce cap: drop farthest when full
                                        if (dq.size() > budgetQueueCap) {
                                            // find farthest and remove it
                                            Queued far = null; for (Queued q : dq) if (far == null || q.dist > far.dist) far = q;
                                            if (far != null) dq.remove(far);
                                        }
                                    }
                                    packetEventCls.getMethod("setCancelled", boolean.class).invoke(packetEvent, true);
                                    return null;
                                } else {
                                    synchronized (sentThisTick) { sentThisTick.put(pid, used + 1); }
                                }
                            } else {
                                // within always-send range: bypass budget but still count a bit to avoid abuse
                                java.util.UUID pid = viewer.getUniqueId();
                                int used; synchronized (sentThisTick) { used = sentThisTick.getOrDefault(pid, 0); sentThisTick.put(pid, used + 1); }
                            }
                        }

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
            logger.info("Packet culling enabled ({} packet type(s)){}", java.lang.Integer.valueOf(supported.size()), budgetEnabled ? " with per-player budget" : "");

            // Tick task to advance time and drain queues
            drainTask = org.bukkit.Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
                tickNow++;
                // Reset counters each tick
                synchronized (sentThisTick) { sentThisTick.clear(); }
                if (!budgetEnabled || sendServerPacketMethod == null) return;
                synchronized (queuedByPlayer) {
                    for (var entry : new java.util.ArrayList<>(queuedByPlayer.entrySet())) {
                        java.util.UUID pid = entry.getKey();
                        var dq = entry.getValue();
                        Player p = org.bukkit.Bukkit.getPlayer(pid);
                        if (p == null || !p.isOnline()) { dq.clear(); continue; }
                        int sent = 0;
                        // Drop expired
                        dq.removeIf(q -> (tickNow - q.tick) > budgetQueueTtlTicks);
                        while (!dq.isEmpty() && sent < budgetMaxPerTick) {
                            // pick nearest first to feel responsive
                            Queued best = null;
                            for (Queued q : dq) { if (best == null || q.dist < best.dist) { best = q; } }
                            if (best == null) break;
                            // remove by index from deque: fallback to remove(best)
                            boolean removed = dq.remove(best);
                            if (!removed) {
                                // should not happen, but guard
                                best = dq.pollFirst();
                                if (best == null) break;
                            }
                            try { sendServerPacketMethod.invoke(protocolManager, p, best.container); } catch (Throwable ignored) {}
                            sent++;
                        }
                        if (dq.isEmpty()) queuedByPlayer.remove(pid);
                    }
                }
            }, 1L, 1L);
        } catch (Throwable t) {
            logger.warn("Failed to enable packet culling via reflection", t);
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
        if (drainTask != -1) { org.bukkit.Bukkit.getScheduler().cancelTask(drainTask); drainTask = -1; }
        queuedByPlayer.clear(); sentThisTick.clear(); decisionCache.clear();
    }
}
