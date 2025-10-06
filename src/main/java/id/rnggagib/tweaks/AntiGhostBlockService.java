package id.rnggagib.tweaks;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resend block updates around cobblestone generators to mitigate ghost blocks for players.
 */
public final class AntiGhostBlockService implements Listener {
    private final Plugin plugin;
    private final Logger logger;

    private boolean enabled;
    private int resendDelayTicks;
    private double playerRadius;
    private Set<Material> trackedMaterials = EnumSet.noneOf(Material.class);

    public AntiGhostBlockService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.anti-ghost-block.enabled", false);
        resendDelayTicks = Math.max(0, cfg.getInt("features.anti-ghost-block.resend-delay-ticks", 2));
        playerRadius = Math.max(1.0, cfg.getDouble("features.anti-ghost-block.player-radius", 12.0));

        trackedMaterials = EnumSet.noneOf(Material.class);
        var list = cfg.getStringList("features.anti-ghost-block.materials");
        if (list != null) {
            for (String entry : list) {
                if (entry == null || entry.isEmpty()) continue;
                try {
                    trackedMaterials.add(Material.valueOf(entry.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    logger.warn("Unknown material '{}' in features.anti-ghost-block.materials", entry);
                }
            }
        }
    }

    public void start() {
        stop();
        if (!enabled) {
            logger.info("Anti ghost block disabled");
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        logger.info("Anti ghost block enabled (delay={}t, radius={})", resendDelayTicks, playerRadius);
    }

    public void stop() {
        HandlerList.unregisterAll(this);
    }

    private boolean isTracked(Material material) {
        return trackedMaterials.isEmpty() || trackedMaterials.contains(material);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!enabled) return;
        Block block = event.getBlock();
        if (!isTracked(block.getType())) return;

        Location loc = block.getLocation();
        if (event.isCancelled()) {
            scheduleResend(loc, event.getPlayer(), 0);
        } else {
            scheduleResend(loc, null, resendDelayTicks);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!enabled) return;
        if (!isTracked(event.getBlockPlaced().getType())) return;
        scheduleResend(event.getBlockPlaced().getLocation(), null, resendDelayTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (!enabled) return;
        if (!isTracked(event.getNewState().getType())) return;
        scheduleResend(event.getBlock().getLocation(), null, resendDelayTicks);
    }

    private void scheduleResend(Location loc, Player directTarget, int delayTicks) {
        if (loc == null || loc.getWorld() == null) return;
        Runnable task = () -> sendBlockUpdate(loc, directTarget);
        if (delayTicks <= 0) {
            Bukkit.getScheduler().runTask(plugin, task);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    private void sendBlockUpdate(Location loc, Player directTarget) {
        Block block = loc.getWorld().getBlockAt(loc);
        var data = block.getBlockData();
        if (directTarget != null) {
            directTarget.sendBlockChange(loc, data);
            return;
        }
        for (Player viewer : loc.getWorld().getNearbyPlayers(loc, playerRadius)) {
            viewer.sendBlockChange(loc, data);
        }
    }
}
