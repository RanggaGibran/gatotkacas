package id.rnggagib.performance;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

public final class SpawnThrottleService implements Listener {
    private final Plugin plugin;
    private final Logger logger;
    private boolean enabled;
    private double playerRadius;
    private Set<CreatureSpawnEvent.SpawnReason> reasons = java.util.EnumSet.of(CreatureSpawnEvent.SpawnReason.NATURAL, CreatureSpawnEvent.SpawnReason.SPAWNER);

    // AI throttle
    private boolean aiEnabled;
    private double aiRadius;
    private int aiPeriodTicks;
    private int aiMaxPerTick;
    private int aiTaskId = -1;
    private final Set<UUID> aiDisabled = new HashSet<>();
    private Iterator<LivingEntity> scanIterator;

    // Stats
    private long cancelled = 0;
    private long allowed = 0;
    private long aiToggledOff = 0;

    public record Stats(long cancelled, long allowed, long aiSkipped) {}

    public SpawnThrottleService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.spawn-throttle.enabled", false);
        playerRadius = cfg.getDouble("features.spawn-throttle.player-radius", 48.0);
        var list = cfg.getStringList("features.spawn-throttle.reasons");
        if (!list.isEmpty()) {
            reasons = java.util.EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
            for (String s : list) {
                try { reasons.add(CreatureSpawnEvent.SpawnReason.valueOf(s.toUpperCase())); } catch (IllegalArgumentException ignored) {}
            }
        }

        aiEnabled = cfg.getBoolean("features.spawn-throttle.ai.enabled", false);
        aiRadius = cfg.getDouble("features.spawn-throttle.ai.radius", Math.max(64.0, playerRadius + 16.0));
        aiPeriodTicks = Math.max(20, cfg.getInt("features.spawn-throttle.ai.period-ticks", 40));
        aiMaxPerTick = Math.max(50, cfg.getInt("features.spawn-throttle.ai.max-per-tick", 200));
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Spawn throttle disabled"); return; }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        logger.info("Spawn throttle enabled (radius {} blocks)", playerRadius);

        if (aiEnabled) {
            aiTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::aiTick, aiPeriodTicks, aiPeriodTicks);
            logger.info("AI throttle enabled (radius {}, max {} entities per scan)", aiRadius, aiMaxPerTick);
        }
    }

    public void stop() {
        CreatureSpawnEvent.getHandlerList().unregister(this);
        if (aiTaskId != -1) { Bukkit.getScheduler().cancelTask(aiTaskId); aiTaskId = -1; }
        // Re-enable AI on disabled entities to be safe
        if (!aiDisabled.isEmpty()) {
            for (World w : Bukkit.getWorlds()) {
                for (var e : w.getEntitiesByClass(LivingEntity.class)) {
                    if (aiDisabled.contains(e.getUniqueId())) {
                        try { e.setAI(true); } catch (Throwable ignored) {}
                    }
                }
            }
            aiDisabled.clear();
        }
    }

    public Stats getStats() {
        return new Stats(cancelled, allowed, aiToggledOff);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!enabled) return;
        if (!reasons.contains(e.getSpawnReason())) return;
        World w = e.getLocation().getWorld();
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(e.getLocation()) <= playerRadius * playerRadius) {
                allowed++;
                return; // allow
            }
        }
        cancelled++;
        e.setCancelled(true);
    }

    private void aiTick() {
        int processed = 0;
        if (scanIterator == null || !scanIterator.hasNext()) {
            // Refresh iterator across all worlds
            java.util.List<LivingEntity> all = new java.util.ArrayList<>();
            for (World w : Bukkit.getWorlds()) all.addAll(w.getEntitiesByClass(LivingEntity.class));
            scanIterator = all.iterator();
        }
        while (scanIterator.hasNext() && processed < aiMaxPerTick) {
            LivingEntity le = scanIterator.next();
            processed++;
            if (!le.isValid()) continue;
            var loc = le.getLocation();
            World w = loc.getWorld();
            boolean near = false;
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(loc) <= aiRadius * aiRadius) { near = true; break; }
            }
            boolean disabled = aiDisabled.contains(le.getUniqueId());
            if (!near && !disabled) {
                try { le.setAI(false); } catch (Throwable ignored) {}
                aiDisabled.add(le.getUniqueId());
                aiToggledOff++;
            } else if (near && disabled) {
                try { le.setAI(true); } catch (Throwable ignored) {}
                aiDisabled.remove(le.getUniqueId());
            }
        }
    }
}
