package id.rnggagib.tweaks;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.*;

/**
 * Small server tweaks: hopper throttling, aggressive item merge, per-world tracking range hooks (config only here).
 */
public final class TweaksService implements Listener {
    private final Plugin plugin;
    private final Logger logger;
    private int taskId = -1;

    // Config
    private boolean enabled;
    private boolean hopperThrottleEnabled;
    private int hopperIntervalTicks;
    private boolean itemMergeEnabled;
    private double itemMergeRadius;
    private int itemMergeMaxPerTick;
    private Map<String, Integer> trackingRangePerWorld = new HashMap<>();

    // State (placeholder for future per-block scheduling)

    public TweaksService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("tweaks.enabled", true);
        hopperThrottleEnabled = cfg.getBoolean("tweaks.hopper.throttle-enabled", false);
        hopperIntervalTicks = cfg.getInt("tweaks.hopper.interval-ticks", 8);
        itemMergeEnabled = cfg.getBoolean("tweaks.item.aggressive-merge-enabled", false);
        itemMergeRadius = cfg.getDouble("tweaks.item.merge-radius", 1.5);
        itemMergeMaxPerTick = cfg.getInt("tweaks.item.max-merge-per-tick", 128);
        trackingRangePerWorld.clear();
        var section = cfg.getConfigurationSection("tweaks.tracking-range-per-world");
        if (section != null) {
            for (String w : section.getKeys(false)) {
                trackingRangePerWorld.put(w, section.getInt(w, 0));
            }
        }
    }

    public void start() {
        stop();
        if (!enabled) {
            logger.info("Tweaks disabled");
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Periodic item merge task on main thread
        if (itemMergeEnabled) {
            taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tickMerge, 20, 20);
        }
        logger.info("Tweaks started (hopperThrottle={}, itemMerge={})", hopperThrottleEnabled, itemMergeEnabled);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        InventoryMoveItemEvent.getHandlerList().unregister(this);
        BlockPhysicsEvent.getHandlerList().unregister(this);
        ChunkLoadEvent.getHandlerList().unregister(this);
    }

    // Hopper throttling: light-touch. We prevent too-frequent moves by cancelling move events on disallowed ticks.
    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        if (!hopperThrottleEnabled) return;
        // Only act if source is a hopper
        if (e.getSource() == null || e.getSource().getLocation() == null) return;
        var block = e.getSource().getLocation().getBlock();
        if (block.getType() != Material.HOPPER) return;
        long tick = Bukkit.getCurrentTick();
        if ((tick % Math.max(1, hopperIntervalTicks)) != 0) {
            e.setCancelled(true);
        }
    }

    // Optional: deny frequent physics for hoppers (extra guard)
    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent e) {
        if (!hopperThrottleEnabled) return;
        if (e.getBlock().getType() != Material.HOPPER) return;
        long tick = Bukkit.getCurrentTick();
        if ((tick % Math.max(1, hopperIntervalTicks)) != 0) e.setCancelled(true);
    }

    // Aggressive item merge runs every second (configurable in the future if needed)
    private void tickMerge() {
        int merged = 0;
        for (World w : Bukkit.getWorlds()) {
            List<Item> items = w.getEntitiesByClass(Item.class).stream().toList();
            // Simple O(n^2) within cap per tick; small radius keeps this affordable
            boolean[] removed = new boolean[items.size()];
            for (int i = 0; i < items.size() && merged < itemMergeMaxPerTick; i++) {
                if (removed[i]) continue;
                Item a = items.get(i);
                if (!a.isValid() || a.isDead()) continue;
                ItemStack sa = a.getItemStack();
                if (sa.getAmount() >= sa.getMaxStackSize()) continue;
                var la = a.getLocation();
                for (int j = i + 1; j < items.size() && merged < itemMergeMaxPerTick; j++) {
                    if (removed[j]) continue;
                    Item b = items.get(j);
                    if (!b.isValid() || b.isDead()) continue;
                    if (!b.getItemStack().isSimilar(sa)) continue;
                    if (la.distanceSquared(b.getLocation()) > itemMergeRadius * itemMergeRadius) continue;
                    // Merge b into a
                    int transfer = Math.min(sa.getMaxStackSize() - sa.getAmount(), b.getItemStack().getAmount());
                    if (transfer <= 0) continue;
                    sa.setAmount(sa.getAmount() + transfer);
                    b.getItemStack().setAmount(b.getItemStack().getAmount() - transfer);
                    a.setItemStack(sa);
                    if (b.getItemStack().getAmount() <= 0) {
                        b.remove();
                        removed[j] = true;
                    }
                    merged++;
                }
            }
        }
        if (merged > 0) {
            logger.debug("Aggressive merge merged {} stacks", merged);
        }
    }

    public Map<String, Integer> trackingRanges() { return Collections.unmodifiableMap(trackingRangePerWorld); }
}
