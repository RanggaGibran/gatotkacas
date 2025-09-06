package id.rnggagib.performance;

import id.rnggagib.nativebridge.NativeBridge;
import id.rnggagib.nativebridge.NativeCulling;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Toggleable entity culling module, safe on main thread.
 */
public final class CullingService {
    private final Plugin plugin;
    private final Logger logger;
    private final NativeBridge nativeBridge;
    private int taskId = -1;
    private ExecutorService worker; // single-threaded async precompute
    private Future<ComputationResult> inFlight;

    // Configurable params
    private boolean enabled;
    private double maxDistance;
    private double speedThreshold;
    private double cosAngleThreshold;
    private int intervalTicks;
    private int maxEntitiesPerTick;
    private boolean metrics;
    private boolean frustumApprox;
    private boolean ratioPercent;
    private boolean alarmEnabled;
    private double alarmThreshold;
    private int alarmCooldownSec;
    private int windowSeconds;
    private java.util.Set<String> worldsInclude = java.util.Set.of();
    private java.util.Set<String> worldsExclude = java.util.Set.of();
    private int chunkRadius;
    private java.util.Map<String, Integer> trackingRangePerWorld = new java.util.HashMap<>();
    private java.util.Map<String, TypeThreshold> typeThresholds = new java.util.HashMap<>();
    // Small pre-sized buffer pool to avoid allocations
    private double[] bufDistances;
    private double[] bufSpeeds;
    private double[] bufCos;
    private boolean[] bufOut;
    private int[] bufTypeCodes;

    // Metrics
    private int lastCulledCount = 0;
    private int lastProcessedCount = 0;
    private double lastCullRatio = 0.0; // 0..1
    private final java.util.ArrayDeque<int[]> window = new java.util.ArrayDeque<>(); // [culled, processed, timestampSec]
    private int windowCulled = 0;
    private int windowProcessed = 0;

    // Filters
    private java.util.Set<String> whitelist = java.util.Set.of();
    private java.util.Set<String> blacklist = java.util.Set.of();

    public CullingService(Plugin plugin, Logger logger, NativeBridge nativeBridge) {
        this.plugin = plugin;
        this.logger = logger;
        this.nativeBridge = nativeBridge;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.culling.enabled", false);
        maxDistance = cfg.getDouble("features.culling.max-distance", 48.0);
        speedThreshold = cfg.getDouble("features.culling.speed-threshold", 0.05);
        cosAngleThreshold = cfg.getDouble("features.culling.cos-angle-threshold", 0.25);
        intervalTicks = cfg.getInt("features.culling.interval-ticks", 20);
        maxEntitiesPerTick = cfg.getInt("features.culling.max-entities-per-tick", 512);
    metrics = cfg.getBoolean("features.culling.metrics", true);
    frustumApprox = cfg.getBoolean("features.culling.frustum-approx", false);
    ratioPercent = cfg.getBoolean("features.culling.ratio-percent", true);
    alarmEnabled = cfg.getBoolean("features.culling.alarm-enabled", false);
    alarmThreshold = cfg.getDouble("features.culling.alarm-threshold", 0.50);
    alarmCooldownSec = cfg.getInt("features.culling.alarm-cooldown-seconds", 30);
    windowSeconds = cfg.getInt("features.culling.window-seconds", 60);
    chunkRadius = cfg.getInt("features.culling.chunk-radius", 0);

    var wl = cfg.getStringList("features.culling.whitelist");
    var bl = cfg.getStringList("features.culling.blacklist");
    var winc = cfg.getStringList("features.culling.worlds-include");
    var wexc = cfg.getStringList("features.culling.worlds-exclude");
    whitelist = new java.util.HashSet<>();
    for (var s : wl) whitelist.add(s.toUpperCase());
    blacklist = new java.util.HashSet<>();
    for (var s : bl) blacklist.add(s.toUpperCase());
    worldsInclude = new java.util.HashSet<>(winc);
    worldsExclude = new java.util.HashSet<>(wexc);

    trackingRangePerWorld.clear();
    var tr = cfg.getConfigurationSection("tweaks.tracking-range-per-world");
    if (tr != null) {
        for (String w : tr.getKeys(false)) {
            trackingRangePerWorld.put(w, tr.getInt(w, 0));
        }
    }

    typeThresholds.clear();
    var tt = cfg.getConfigurationSection("features.culling.type-thresholds");
    if (tt != null) {
        for (String type : tt.getKeys(false)) {
            double md = tt.getDouble(type + ".max-distance", maxDistance);
            double st = tt.getDouble(type + ".speed-threshold", speedThreshold);
            double ct = tt.getDouble(type + ".cos-angle-threshold", cosAngleThreshold);
            typeThresholds.put(type.toUpperCase(), new TypeThreshold(md, st, ct));
        }
    }
    }

    public void start() {
        stop();
        if (!enabled) {
            logger.info("Culling disabled");
            return;
        }
        if (!nativeBridge.isLoaded()) {
            logger.info("Culling enabled; native bridge not loaded — using Java fallback");
        }
        if (worker == null || worker.isShutdown()) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "gatotkacas-culling-worker");
                t.setDaemon(true);
                return t;
            });
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, intervalTicks, intervalTicks);
        logger.info("Culling scheduled every {} ticks", intervalTicks);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        inFlight = null;
    }

    private void tick() {
        // 1) Apply last completed computation, if any
        int culledThisTick = 0;
        int processedThisTick = 0;
        int nowSec = (int) (System.currentTimeMillis() / 1000L);
        if (inFlight != null && inFlight.isDone()) {
            try {
                var res = inFlight.get();
                // Apply results (main thread)
                for (var r : res.results) {
                    if (processedThisTick >= maxEntitiesPerTick) break; // safety cap on application as well
                    processedThisTick++;
                    var ent = Bukkit.getServer().getEntity(r.entityId);
                    if (ent == null) continue;
                    World w = ent.getWorld();
                    List<Player> players = w.getPlayers();
                    Player nearest = r.nearestPlayerId != null ? Bukkit.getPlayer(r.nearestPlayerId) : null;
                    if (r.cull) {
                        for (Player p : players) {
                            if (nearest != null && p.equals(nearest)) continue;
                            p.hideEntity(plugin, ent);
                        }
                        culledThisTick++;
                    } else {
                        for (Player p : players) {
                            p.showEntity(plugin, ent);
                        }
                    }
                }
            } catch (Exception ignored) {
                // On any error, drop this result
            } finally {
                inFlight = null; // clear slot
            }
        }

        // 2) If no computation running, build snapshot and dispatch one
        if (inFlight == null && worker != null) {
            var snapshot = buildSnapshot(maxEntitiesPerTick);
            if (snapshot != null && !snapshot.entities.isEmpty() && !snapshot.players.isEmpty()) {
                inFlight = worker.submit(() -> compute(snapshot));
            }
        }

        // 3) Metrics update and alarms
        if (metrics) {
            lastCulledCount = culledThisTick;
            lastProcessedCount = processedThisTick;
            lastCullRatio = processedThisTick > 0 ? (double) culledThisTick / (double) processedThisTick : 0.0;

            if (windowSeconds > 0) {
                window.addLast(new int[]{culledThisTick, processedThisTick, nowSec});
                windowCulled += culledThisTick;
                windowProcessed += processedThisTick;
                while (!window.isEmpty() && nowSec - window.peekFirst()[2] >= windowSeconds) {
                    int[] old = window.removeFirst();
                    windowCulled -= old[0];
                    windowProcessed -= old[1];
                }
            }

            if (alarmEnabled && lastCullRatio >= alarmThreshold) {
                int lastAlarmSec = -1;
                for (int[] item : window) {
                    if (item[1] == -1) lastAlarmSec = item[2];
                }
                if (lastAlarmSec == -1 || nowSec - lastAlarmSec >= alarmCooldownSec) {
                    logger.warn("Culling ratio high: {}/{} ({})", lastCulledCount, lastProcessedCount,
                        ratioPercent ? String.format("%.1f%%", lastCullRatio * 100.0) : String.format("%.3f", lastCullRatio));
                    window.addLast(new int[]{0, -1, nowSec});
                }
            }
        }
    }

    private Snapshot buildSnapshot(int cap) {
        // Build a lightweight snapshot on main thread using spatial queries around players
        var playersByWorld = new java.util.HashMap<String, java.util.List<PlayerSnap>>();
        var entities = new java.util.ArrayList<EntitySnap>(cap);
        var seen = new java.util.HashSet<UUID>(cap * 2);

        for (World world : Bukkit.getWorlds()) {
            String wname = world.getName();
            if (!worldsInclude.isEmpty() && !worldsInclude.contains(wname)) continue;
            if (worldsExclude.contains(wname)) continue;
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) continue;

            // Snapshot players (pos + view dir)
            var psnaps = new java.util.ArrayList<PlayerSnap>(players.size());
            for (Player p : players) {
                var loc = p.getLocation();
                var dir = loc.getDirection();
                psnaps.add(new PlayerSnap(p.getUniqueId(), wname, loc.getX(), loc.getY(), loc.getZ(), dir.getX(), dir.getY(), dir.getZ()));
            }
            playersByWorld.put(wname, psnaps);

            // Spatial gather: per-player nearby entities within maxDistance (expand a bit for safety)
            int trRange = trackingRangePerWorld.getOrDefault(wname, 0);
            double r = Math.max(8.0, Math.min(maxDistance + 4.0, trRange > 0 ? (trRange + 4.0) : Double.MAX_VALUE));
            for (Player p : players) {
                for (Entity e : p.getNearbyEntities(r, r, r)) {
                    if (entities.size() >= cap) break;
                    if (!e.isValid()) continue;
                    if (!e.getWorld().getName().equals(wname)) continue;
                    UUID id = e.getUniqueId();
                    if (seen.contains(id)) continue; // de-dup across players
                    var typeName = e.getType().name();
                    if (!whitelist.isEmpty() && !whitelist.contains(typeName)) continue;
                    if (blacklist.contains(typeName)) continue;
                    // Optional chunk radius filter relative to this player
                    if (chunkRadius > 0) {
                        var ch = e.getLocation().getChunk();
                        var pch = p.getLocation().getChunk();
                        int dx = Math.abs(pch.getX() - ch.getX());
                        int dz = Math.abs(pch.getZ() - ch.getZ());
                        if (dx > chunkRadius || dz > chunkRadius) continue;
                    }

                    var loc = e.getLocation();
                    double speed = e.getVelocity().length();
                    entities.add(new EntitySnap(id, wname, typeName, loc.getX(), loc.getY(), loc.getZ(), speed));
                    seen.add(id);
                }
                if (entities.size() >= cap) break;
            }
        }
        if (entities.isEmpty() || playersByWorld.isEmpty()) return new Snapshot(java.util.Map.of(), java.util.List.of());
        return new Snapshot(playersByWorld, entities);
    }

    private ComputationResult compute(Snapshot snap) {
        var results = new java.util.ArrayList<Result>(snap.entities.size());

        // Precompute nearest player and scalar features per entity
        int n = snap.entities.size();
        double[] distances = new double[n];
        double[] speeds = new double[n];
        double[] cosAngles = new double[n];
        java.util.UUID[] nearestIds = new java.util.UUID[n];
        boolean[] valid = new boolean[n];

        for (int i = 0; i < n; i++) {
            var e = snap.entities.get(i);
            var ps = snap.players.get(e.worldName);
            if (ps == null || ps.isEmpty()) { valid[i] = false; continue; }
            PlayerSnap nearest = null; double nearestSq = Double.MAX_VALUE;
            for (var p : ps) {
                double dx = e.x - p.x; double dy = e.y - p.y; double dz = e.z - p.z;
                double d2 = dx*dx + dy*dy + dz*dz;
                if (d2 < nearestSq) { nearestSq = d2; nearest = p; }
            }
            if (nearest == null) { valid[i] = false; continue; }
            double distance = Math.sqrt(nearestSq);
            double dirX = e.x - nearest.x; double dirY = e.y - nearest.y; double dirZ = e.z - nearest.z;
            double invLen = 1.0 / Math.max(1e-9, Math.sqrt(dirX*dirX + dirY*dirY + dirZ*dirZ));
            dirX *= invLen; dirY *= invLen; dirZ *= invLen;
            double viewLen = Math.max(1e-9, Math.sqrt(nearest.dirX*nearest.dirX + nearest.dirY*nearest.dirY + nearest.dirZ*nearest.dirZ));
            double vx = nearest.dirX / viewLen, vy = nearest.dirY / viewLen, vz = nearest.dirZ / viewLen;
            double cos = vx*dirX + vy*dirY + vz*dirZ;
            distances[i] = distance; speeds[i] = e.speed; cosAngles[i] = cos; nearestIds[i] = nearest.playerId; valid[i] = true;
        }

        boolean[] culls;
        if (nativeBridge.isLoaded()) {
            try {
                // Ensure buffers are large enough
                if (bufDistances == null || bufDistances.length < n) {
                    int cap = Math.max(1024, Integer.highestOneBit(n - 1) << 1);
                    bufDistances = new double[cap];
                    bufSpeeds = new double[cap];
                    bufCos = new double[cap];
                    bufOut = new boolean[cap];
                    bufTypeCodes = new int[cap];
                }
                System.arraycopy(distances, 0, bufDistances, 0, n);
                System.arraycopy(speeds, 0, bufSpeeds, 0, n);
                System.arraycopy(cosAngles, 0, bufCos, 0, n);

                if (!typeThresholds.isEmpty()) {
                    // Map type names to compact codes
                    var keys = new java.util.ArrayList<>(typeThresholds.keySet());
                    java.util.Collections.sort(keys);
                    var codeByType = new java.util.HashMap<String, Integer>(keys.size());
                    for (int i = 0; i < keys.size(); i++) codeByType.put(keys.get(i), i);
                    for (int i = 0; i < n; i++) {
                        if (!valid[i]) { bufTypeCodes[i] = 0; continue; }
                        String tname = snap.entities.get(i).typeName.toUpperCase();
                        Integer code = codeByType.get(tname);
                        bufTypeCodes[i] = code == null ? 0 : code;
                    }
                    double[] tMax = new double[keys.size() > 0 ? keys.size() : 1];
                    double[] tSpd = new double[tMax.length];
                    double[] tCos = new double[tMax.length];
                    for (int i = 0; i < keys.size(); i++) {
                        var th = typeThresholds.get(keys.get(i));
                        tMax[i] = th.maxDistance; tSpd[i] = th.speedThreshold; tCos[i] = th.cosAngleThreshold;
                    }
                    if (tMax.length == 1 && keys.isEmpty()) { // default bucket if none
                        tMax[0] = maxDistance; tSpd[0] = speedThreshold; tCos[0] = cosAngleThreshold;
                    }
                    NativeCulling.shouldCullBatchIntoByType(bufDistances, bufSpeeds, bufCos, bufTypeCodes, tMax, tSpd, tCos, bufOut);
                    culls = java.util.Arrays.copyOf(bufOut, n);
                } else {
                    NativeCulling.shouldCullBatchInto(bufDistances, bufSpeeds, bufCos, bufOut, maxDistance, speedThreshold, cosAngleThreshold);
                    culls = java.util.Arrays.copyOf(bufOut, n);
                }
            } catch (Throwable t) {
                culls = null;
            }
        } else {
            culls = new boolean[n];
            for (int i = 0; i < n; i++) {
                if (!valid[i]) { culls[i] = false; continue; }
                var th = typeThresholds.getOrDefault(snap.entities.get(i).typeName.toUpperCase(), new TypeThreshold(maxDistance, speedThreshold, cosAngleThreshold));
                boolean base = distances[i] > th.maxDistance && speeds[i] < th.speedThreshold && cosAngles[i] < th.cosAngleThreshold;
                culls[i] = frustumApprox ? (base && cosAngles[i] < (th.cosAngleThreshold - 0.15)) : base;
            }
        }

        if (culls != null) {
            for (int i = 0; i < n; i++) {
                if (!valid[i]) continue;
                results.add(new Result(snap.entities.get(i).id, culls[i], nearestIds[i]));
            }
        }
        return new ComputationResult(results);
    }

    private static final class TypeThreshold {
        final double maxDistance;
        final double speedThreshold;
        final double cosAngleThreshold;
        TypeThreshold(double maxDistance, double speedThreshold, double cosAngleThreshold) {
            this.maxDistance = maxDistance;
            this.speedThreshold = speedThreshold;
            this.cosAngleThreshold = cosAngleThreshold;
        }
    }

    // Snapshot + result types (no Bukkit refs off-thread)
    private record PlayerSnap(UUID playerId, String worldName, double x, double y, double z,
                              double dirX, double dirY, double dirZ) {}
    private record EntitySnap(UUID id, String worldName, String typeName, double x, double y, double z,
                              double speed) {}
    private record Snapshot(java.util.Map<String, java.util.List<PlayerSnap>> players,
                            java.util.List<EntitySnap> entities) {}
    private record Result(UUID entityId, boolean cull, UUID nearestPlayerId) {}
    private record ComputationResult(java.util.List<Result> results) {}

    // Quick scalar check used by packet culling
    public boolean quickShouldCull(double distance, double speed, double cos) {
        boolean base = distance > maxDistance && speed < speedThreshold && cos < cosAngleThreshold;
        if (frustumApprox) {
            return base && cos < (cosAngleThreshold - 0.15);
        }
        return base;
    }

    public int getLastCulledCount() {
        return lastCulledCount;
    }

    public int getLastProcessedCount() {
        return lastProcessedCount;
    }

    public double getLastCullRatio() {
        return lastCullRatio;
    }

    public int getWindowCulled() { return windowCulled; }
    public int getWindowProcessed() { return windowProcessed; }
    public double getWindowRatio() { return windowProcessed > 0 ? (double) windowCulled / (double) windowProcessed : 0.0; }
    public boolean isRatioPercent() { return ratioPercent; }
}
