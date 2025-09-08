package id.rnggagib.tweaks;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Periodic sweeper to merge/cap XP orbs and projectiles per-chunk,
 * and despawn orphans far from any player.
 */
public final class SweeperService implements org.bukkit.event.Listener {
    private final Plugin plugin;
    private final Logger logger;
    private boolean enabled;
    private int periodTicks;
    private double playerRadius;
    private int minAgeTicks;

    private double xpMergeRadius;
    private int xpMaxPerChunk;
    private int xpMaxMergePerTick;

    private Set<String> projectileTypes;
    private double projMergeRadius;
    private int projMaxPerChunk;
    private int projMaxRemovePerTick;

    private int taskId = -1;

    public SweeperService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.sweeper.enabled", false);
        periodTicks = Math.max(20, cfg.getInt("features.sweeper.period-ticks", 40));
        playerRadius = Math.max(16.0, cfg.getDouble("features.sweeper.player-radius", 64.0));
        minAgeTicks = Math.max(20, cfg.getInt("features.sweeper.min-age-ticks", 60));

        xpMergeRadius = Math.max(0.5, cfg.getDouble("features.sweeper.xp.merge-radius", 2.5));
        xpMaxPerChunk = Math.max(8, cfg.getInt("features.sweeper.xp.max-per-chunk", 64));
        xpMaxMergePerTick = Math.max(8, cfg.getInt("features.sweeper.xp.max-merge-per-tick", 128));

        projMergeRadius = Math.max(0.5, cfg.getDouble("features.sweeper.projectile.merge-radius", 2.0));
        projMaxPerChunk = Math.max(16, cfg.getInt("features.sweeper.projectile.max-per-chunk", 96));
        projMaxRemovePerTick = Math.max(8, cfg.getInt("features.sweeper.projectile.max-remove-per-tick", 128));
        var types = cfg.getStringList("features.sweeper.projectile.types");
        projectileTypes = new HashSet<>();
        for (String s : types) projectileTypes.add(s.toUpperCase(Locale.ENGLISH));
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Sweeper disabled"); return; }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, periodTicks, periodTicks);
        logger.info("Sweeper enabled (xp r={} cap/chunk={}, proj r={} cap/chunk={})", xpMergeRadius, xpMaxPerChunk, projMergeRadius, projMaxPerChunk);
    }

    public void stop() {
        if (taskId != -1) { Bukkit.getScheduler().cancelTask(taskId); taskId = -1; }
    }

    private void tick() {
        // Precompute player positions per world for orphan check
        Map<World, List<Vector>> players = new HashMap<>();
        for (World w : Bukkit.getWorlds()) {
            List<Vector> list = new ArrayList<>();
            for (Player p : w.getPlayers()) list.add(p.getLocation().toVector());
            players.put(w, list);
        }

        int xpProcessed = 0, projProcessed = 0;
        for (World w : Bukkit.getWorlds()) {
            // XP: gather per-chunk
            Map<Long, List<ExperienceOrb>> xpByChunk = new HashMap<>();
            for (ExperienceOrb orb : w.getEntitiesByClass(ExperienceOrb.class)) {
                if (orb.getTicksLived() < minAgeTicks) continue;
                long key = chunkKey(orb.getChunk());
                xpByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(orb);
            }
            for (var e : xpByChunk.entrySet()) {
                var list = e.getValue();
                // Merge within radius using simple clustering: keep the largest value orb as leader
                list.sort(Comparator.comparingInt(ExperienceOrb::getExperience).reversed());
                boolean[] used = new boolean[list.size()];
                for (int i = 0; i < list.size() && xpProcessed < xpMaxMergePerTick; i++) {
                    if (used[i]) continue;
                    ExperienceOrb leader = list.get(i);
                    int total = leader.getExperience();
                    for (int j = i + 1; j < list.size(); j++) {
                        if (used[j]) continue;
                        ExperienceOrb o = list.get(j);
                        if (leader.getLocation().distanceSquared(o.getLocation()) <= xpMergeRadius * xpMergeRadius) {
                            total += o.getExperience();
                            used[j] = true; o.remove(); xpProcessed++;
                        }
                    }
                    leader.setExperience(total);
                }
                // Cap per-chunk: remove oldest small ones beyond cap
                int alive = 0;
                for (int i = 0; i < list.size(); i++) if (!used[i] && !list.get(i).isDead()) alive++;
                if (alive > xpMaxPerChunk) {
                    // remove extras by ascending experience
                    List<ExperienceOrb> keep = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) if (!used[i] && !list.get(i).isDead()) keep.add(list.get(i));
                    keep.sort(Comparator.comparingInt(ExperienceOrb::getExperience).reversed());
                    for (int i = xpMaxPerChunk; i < keep.size() && xpProcessed < xpMaxMergePerTick; i++) {
                        keep.get(i).remove(); xpProcessed++;
                    }
                }
            }

            // Projectiles: cluster and cap; also orphan-despawn
            Map<Long, List<Projectile>> projByChunk = new HashMap<>();
            for (Entity ent : w.getEntities()) {
                if (!(ent instanceof Projectile p)) continue;
                String type = ent.getType().name();
                if (!projectileTypes.contains(type)) continue;
                if (p.getTicksLived() < minAgeTicks) continue;
                long key = chunkKey(ent.getChunk());
                projByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            }
            for (var e2 : projByChunk.entrySet()) {
                var list = e2.getValue();
                // Orphan-despawn: if far from all players
                var plist = players.getOrDefault(w, List.of());
                for (Iterator<Projectile> it = list.iterator(); it.hasNext();) {
                    Projectile p = it.next();
                    boolean near = false;
                    for (Vector pv : plist) {
                        if (pv.distanceSquared(p.getLocation().toVector()) <= playerRadius * playerRadius) { near = true; break; }
                    }
                    if (!near) { p.remove(); it.remove(); projProcessed++; }
                }
                // Merge/collapse clusters: keep one leader in small radius, remove others
                list.sort(Comparator.comparingInt(Entity::getTicksLived)); // keep older one
                boolean[] used = new boolean[list.size()];
                for (int i = 0; i < list.size() && projProcessed < projMaxRemovePerTick; i++) {
                    if (used[i]) continue;
                    Projectile leader = list.get(i);
                    for (int j = i + 1; j < list.size() && projProcessed < projMaxRemovePerTick; j++) {
                        if (used[j]) continue;
                        Projectile o = list.get(j);
                        if (leader.getLocation().distanceSquared(o.getLocation()) <= projMergeRadius * projMergeRadius) {
                            used[j] = true; o.remove(); projProcessed++;
                        }
                    }
                }
                // Cap per-chunk
                int alive = 0; for (int i = 0; i < list.size(); i++) if (!used[i] && !list.get(i).isDead()) alive++;
                if (alive > projMaxPerChunk) {
                    List<Projectile> keep = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) if (!used[i] && !list.get(i).isDead()) keep.add(list.get(i));
                    keep.sort(Comparator.comparingInt(Entity::getTicksLived)); // keep older first
                    for (int i = projMaxPerChunk; i < keep.size() && projProcessed < projMaxRemovePerTick; i++) {
                        keep.get(i).remove(); projProcessed++;
                    }
                }
            }
        }
    }

    private long chunkKey(Chunk ch) {
        return (((long) ch.getX()) << 32) ^ (ch.getZ() & 0xffffffffL);
    }
}
