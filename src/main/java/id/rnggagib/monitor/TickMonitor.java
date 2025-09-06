package id.rnggagib.monitor;

import id.rnggagib.performance.CullingService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public final class TickMonitor {
    private final Plugin plugin;
    private final Logger logger;
    private final @Nullable CullingService cullingService;
    private @Nullable id.rnggagib.performance.SpawnThrottleService spawnThrottleService;
    private @Nullable id.rnggagib.tweaks.RedstoneGuardService redstoneGuardService;
    private int tickTask = -1;
    private int reportTask = -1;

    private int windowTicks;
    private boolean reportEnabled;
    private int reportPeriodSec;
    private String reportPath;

    private long lastNano = -1L;
    private double[] window;
    private int idx = 0;
    private int count = 0;
    private double sumMs = 0.0;

    public TickMonitor(Plugin plugin, Logger logger, @Nullable CullingService cullingService) {
        this.plugin = plugin;
        this.logger = logger;
        this.cullingService = cullingService;
    }

    public void setSpawnThrottleService(@Nullable id.rnggagib.performance.SpawnThrottleService svc) {
        this.spawnThrottleService = svc;
    }

    public void setRedstoneGuardService(@Nullable id.rnggagib.tweaks.RedstoneGuardService svc) {
        this.redstoneGuardService = svc;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        this.windowTicks = Math.max(20, cfg.getInt("monitor.window-ticks", 1200));
        this.reportEnabled = cfg.getBoolean("monitor.report.enabled", false);
        this.reportPeriodSec = Math.max(1, cfg.getInt("monitor.report.period-seconds", 15));
        this.reportPath = cfg.getString("monitor.report.path", "reports/status.json");
        this.window = new double[windowTicks];
        this.idx = 0;
        this.count = 0;
        this.sumMs = 0.0;
        this.lastNano = -1L;
    }

    public void start() {
        stop();
        tickTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, 1L);
        if (reportEnabled) {
            long periodTicks = Math.max(1L, reportPeriodSec * 20L);
            reportTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::writeReport, periodTicks, periodTicks);
        }
    }

    public void stop() {
        if (tickTask != -1) { Bukkit.getScheduler().cancelTask(tickTask); tickTask = -1; }
        if (reportTask != -1) { Bukkit.getScheduler().cancelTask(reportTask); reportTask = -1; }
    }

    private void tick() {
        long now = System.nanoTime();
        if (lastNano > 0) {
            double ms = (now - lastNano) / 1_000_000.0;
            if (count < window.length) {
                window[idx] = ms;
                sumMs += ms;
                idx = (idx + 1) % window.length;
                count++;
            } else {
                sumMs -= window[idx];
                window[idx] = ms;
                sumMs += ms;
                idx = (idx + 1) % window.length;
            }
        }
        lastNano = now;
    }

    public double avgMspt() {
        return count == 0 ? 0.0 : (sumMs / (double) count);
    }

    public double avgTps() {
        double mspt = avgMspt();
        if (mspt <= 0.0) return 20.0;
        double tps = 1000.0 / mspt;
        return Math.min(20.0, tps);
    }

    private void writeReport() {
        try {
            File out = resolveReportFile();
            if (!out.getParentFile().exists() && !out.getParentFile().mkdirs()) {
                logger.warn("Failed to create report directory: {}", out.getParent());
                return;
            }
            try (FileWriter fw = new FileWriter(out, false)) {
                fw.write("{");
                fw.write("\"msptAvg\":" + String.format("%.3f", avgMspt()) + ",");
                fw.write("\"tpsAvg\":" + String.format("%.2f", avgTps()));
                if (cullingService != null) {
                    fw.write(",\"culling\":{");
                    fw.write("\"tick\":{\"culled\":" + cullingService.getLastCulledCount() + ",\"processed\":" + cullingService.getLastProcessedCount() + ",\"ratio\":" + String.format("%.4f", cullingService.getLastCullRatio()) + "},");
                    fw.write("\"window\":{\"culled\":" + cullingService.getWindowCulled() + ",\"processed\":" + cullingService.getWindowProcessed() + ",\"ratio\":" + String.format("%.4f", cullingService.getWindowRatio()) + "}");
                    fw.write("}");
                }
                if (spawnThrottleService != null) {
                    var st = spawnThrottleService.getStats();
                    fw.write(",\"spawnThrottle\":{\"cancelled\":" + st.cancelled() + ",\"allowed\":" + st.allowed() + ",\"aiSkipped\":" + st.aiSkipped() + "}");
                }
                if (redstoneGuardService != null) {
                    fw.write(",\"redstoneGuard\":{\"throttledChunks\":" + redstoneGuardService.throttledChunkCountLastWindow() + ",\"suppressed\":" + redstoneGuardService.suppressedToggleCount() + "}");
                }
                fw.write("}\n");
            }
        } catch (IOException e) {
            logger.warn("Failed writing monitor report: {}", e.toString());
        }
    }

    private File resolveReportFile() {
        File f = new File(reportPath);
        if (f.isAbsolute()) return f;
        return new File(plugin.getDataFolder(), reportPath);
    }
}
