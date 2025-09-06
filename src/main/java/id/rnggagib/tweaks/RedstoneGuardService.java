package id.rnggagib.tweaks;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

public final class RedstoneGuardService implements Listener {
    private final Plugin plugin;
    private final Logger logger;
    private boolean enabled;
    private int windowTicks;
    private int toggleLimit;
    private int cleanupTask = -1;
    private final Map<Long, Integer> toggles = new HashMap<>();
    private int throttledLastWindow = 0;
    private long suppressedCount = 0L;

    public RedstoneGuardService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.redstone-guard.enabled", false);
        windowTicks = Math.max(20, cfg.getInt("features.redstone-guard.window-ticks", 200));
        toggleLimit = Math.max(50, cfg.getInt("features.redstone-guard.toggle-limit", 500));
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Redstone guard disabled"); return; }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        cleanupTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            // Compute simple metric: how many chunks exceeded limit last window
            int c = 0;
            for (int v : toggles.values()) if (v > toggleLimit) c++;
            throttledLastWindow = c;
            toggles.clear();
        }, windowTicks, windowTicks);
        logger.info("Redstone guard enabled (limit {} per {} ticks)", toggleLimit, windowTicks);
    }

    public void stop() {
        if (cleanupTask != -1) { Bukkit.getScheduler().cancelTask(cleanupTask); cleanupTask = -1; }
        BlockRedstoneEvent.getHandlerList().unregister(this);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        if (!enabled) return;
        Chunk ch = e.getBlock().getChunk();
        long key = (((long) ch.getX()) << 32) ^ (ch.getZ() & 0xffffffffL);
        int c = toggles.getOrDefault(key, 0) + 1;
        toggles.put(key, c);
        if (c > toggleLimit) {
            // Cancel by forcing no change
            e.setNewCurrent(e.getOldCurrent());
        suppressedCount++;
            if (c == toggleLimit + 1) {
                logger.warn("Redstone throttled in chunk {},{}", ch.getX(), ch.getZ());
            }
        }
    }

    public int throttledChunkCountLastWindow() { return throttledLastWindow; }
    public long suppressedToggleCount() { return suppressedCount; }
}
