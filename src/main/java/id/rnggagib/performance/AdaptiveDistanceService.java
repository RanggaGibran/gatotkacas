package id.rnggagib.performance;

import id.rnggagib.monitor.TickMonitor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

public final class AdaptiveDistanceService {
    private final Plugin plugin;
    private final Logger logger;
    private final TickMonitor monitor;
    private int taskId = -1;

    private boolean enabled;
    private double highMspt;
    private double lowMspt;
    private int minView;
    private int maxView;
    private int minSim;
    private int maxSim;
    private int periodTicks;

    public AdaptiveDistanceService(Plugin plugin, Logger logger, TickMonitor monitor) {
        this.plugin = plugin;
        this.logger = logger;
        this.monitor = monitor;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.adaptive-distance.enabled", false);
        highMspt = cfg.getDouble("features.adaptive-distance.high-mspt", 45.0);
        lowMspt = cfg.getDouble("features.adaptive-distance.low-mspt", 30.0);
        minView = cfg.getInt("features.adaptive-distance.min-view-distance", 6);
        maxView = cfg.getInt("features.adaptive-distance.max-view-distance", 10);
        minSim = cfg.getInt("features.adaptive-distance.min-sim-distance", 4);
        maxSim = cfg.getInt("features.adaptive-distance.max-sim-distance", 8);
        periodTicks = Math.max(20, cfg.getInt("features.adaptive-distance.period-ticks", 200));
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Adaptive distance disabled"); return; }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, periodTicks, periodTicks);
        logger.info("Adaptive distance enabled ({}-{} view, {}-{} sim)", minView, maxView, minSim, maxSim);
    }

    public void stop() {
        if (taskId != -1) { Bukkit.getScheduler().cancelTask(taskId); taskId = -1; }
    }

    private void tick() {
        double mspt = monitor.avgMspt();
        for (World w : Bukkit.getWorlds()) {
            if (mspt > highMspt) {
                w.setViewDistance(Math.max(minView, w.getViewDistance() - 1));
                w.setSimulationDistance(Math.max(minSim, w.getSimulationDistance() - 1));
            } else if (mspt < lowMspt) {
                w.setViewDistance(Math.min(maxView, w.getViewDistance() + 1));
                w.setSimulationDistance(Math.min(maxSim, w.getSimulationDistance() + 1));
            }
        }
    }
}
