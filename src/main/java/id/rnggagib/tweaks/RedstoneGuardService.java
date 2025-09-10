package id.rnggagib.tweaks;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Color;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Display;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.HashMap;
import java.util.Map;
import id.rnggagib.util.EntityUtils;

public final class RedstoneGuardService implements Listener {
    private final Plugin plugin;
    private final Logger logger;
    private boolean enabled;
    private int windowTicks;
    private int toggleLimit;
    private int cleanupTask = -1;
    private int tickTask = -1;
    private final Map<Long, Integer> toggles = new HashMap<>();
    // Throttle state per chunk
    private final Map<Long, Long> throttleUntilTick = new HashMap<>();
    private final Map<Long, Integer> dutyCounter = new HashMap<>();
    private final Map<Long, Integer> cooldownTicksCurrent = new HashMap<>();
    private long tickNow = 0L;
    // Config for throttling behavior
    private int cooldownInitialTicks;
    private int cooldownMaxTicks;
    private double backoffMultiplier;
    private int dutyPassEvery;
    private int throttledLastWindow = 0;
    private long suppressedCount = 0L;
    // Notification holograms per chunk
    private boolean notifEnabled;
    private long notifTtlSeconds;
    private long notifCooldownSeconds;
    private final Map<Long, Long> lastNotifAtMillis = new HashMap<>();
    private final Map<Long, java.util.UUID> notifEntity = new HashMap<>();
    private final Map<Long, Location> notifAnchor = new HashMap<>();
    private final Map<Long, String> notifBase = new HashMap<>();
    private final java.util.LinkedHashMap<Long, Long> notifOrder = new java.util.LinkedHashMap<>(); // key -> firstShownAt
    private final Map<Long, Long> lastTextUpdateTick = new HashMap<>();
    // visual
    private boolean notifFullBright;
    private int notifBgAlpha;
    private int notifLineWidth;
    private int notifMaxActive;
    private boolean notifReplaceOldest;
    private int notifMinUpdateIntervalTicks;

    public RedstoneGuardService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.redstone-guard.enabled", false);
        windowTicks = Math.max(20, cfg.getInt("features.redstone-guard.window-ticks", 200));
        toggleLimit = Math.max(50, cfg.getInt("features.redstone-guard.toggle-limit", 500));
    cooldownInitialTicks = Math.max(20, cfg.getInt("features.redstone-guard.cooldown-initial-ticks", 100));
    cooldownMaxTicks = Math.max(cooldownInitialTicks, cfg.getInt("features.redstone-guard.cooldown-max-ticks", 1200));
    backoffMultiplier = Math.max(1.0, cfg.getDouble("features.redstone-guard.backoff-multiplier", 2.0));
    dutyPassEvery = Math.max(1, cfg.getInt("features.redstone-guard.duty-pass-every", 5)); // allow 1 in N while throttled
    notifEnabled = cfg.getBoolean("features.redstone-guard.notification.enabled", true);
    notifTtlSeconds = Math.max(10, cfg.getLong("features.redstone-guard.notification.ttl-seconds", 300));
    notifCooldownSeconds = Math.max(30, cfg.getLong("features.redstone-guard.notification.cooldown-seconds", 120));
    notifFullBright = cfg.getBoolean("features.redstone-guard.notification.full-bright", true);
    notifBgAlpha = Math.min(255, Math.max(0, cfg.getInt("features.redstone-guard.notification.background-alpha", 190)));
    notifLineWidth = Math.max(80, cfg.getInt("features.redstone-guard.notification.line-width", 240));
    notifMaxActive = Math.max(1, cfg.getInt("features.redstone-guard.notification.max-active", 64));
    notifReplaceOldest = cfg.getBoolean("features.redstone-guard.notification.replace-oldest-when-full", false);
    notifMinUpdateIntervalTicks = Math.max(0, cfg.getInt("features.redstone-guard.notification.min-update-interval-ticks", 20));
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Redstone guard disabled"); return; }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // 1-tick heartbeat to track current tick
        tickTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            tickNow++;
            // Update countdown text every second
            if (notifEnabled && (tickNow % 20 == 0)) updateNotificationCountdowns();
        }, 1L, 1L);
        cleanupTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            // Compute simple metric: how many chunks exceeded limit last window
            int c = 0;
            for (int v : toggles.values()) if (v > toggleLimit) c++;
            throttledLastWindow = c;
            toggles.clear();
            // Housekeeping: drop expired throttles and reset duty counters for clean chunks
            var toDrop = new java.util.ArrayList<Long>();
            for (var e : throttleUntilTick.entrySet()) {
                if (e.getValue() <= tickNow) toDrop.add(e.getKey());
            }
            for (var key : toDrop) {
                throttleUntilTick.remove(key);
                dutyCounter.remove(key);
            }
            // Gradually relax cooldown for chunks that stayed under limit in last window
            for (var entry : cooldownTicksCurrent.entrySet()) {
                long key = entry.getKey();
                int used = toggles.getOrDefault(key, 0);
                if (used <= toggleLimit / 2) {
                    cooldownTicksCurrent.put(key, Math.max(cooldownInitialTicks, entry.getValue() / 2));
                }
            }
            // Clean expired notifications
            long nowMs = System.currentTimeMillis();
            var toRemoveNotif = new java.util.ArrayList<Long>();
            for (var e : notifEntity.entrySet()) {
                var uuid = e.getValue();
                var td = EntityUtils.findTextDisplay(uuid);
                if (td == null || td.isDead() || (lastNotifAtMillis.getOrDefault(e.getKey(), 0L) + notifTtlSeconds * 1000L) <= nowMs) {
                    if (td != null && !td.isDead()) td.remove();
                    toRemoveNotif.add(e.getKey());
                }
            }
            for (var k : toRemoveNotif) { 
                notifEntity.remove(k); lastNotifAtMillis.remove(k); notifAnchor.remove(k); notifBase.remove(k);
                notifOrder.remove(k); lastTextUpdateTick.remove(k);
            }
        }, windowTicks, windowTicks);
        logger.info("Redstone guard enabled (limit {} per {} ticks; cooldown {}-{} ticks, pass 1/{} when throttled)",
            toggleLimit, windowTicks, cooldownInitialTicks, cooldownMaxTicks, dutyPassEvery);
    }

    public void stop() {
    if (cleanupTask != -1) { Bukkit.getScheduler().cancelTask(cleanupTask); cleanupTask = -1; }
    if (tickTask != -1) { Bukkit.getScheduler().cancelTask(tickTask); tickTask = -1; tickNow = 0L; }
        BlockRedstoneEvent.getHandlerList().unregister(this);
    toggles.clear();
    throttleUntilTick.clear();
    dutyCounter.clear();
    cooldownTicksCurrent.clear();
        // Remove any active notifications
        for (var uuid : notifEntity.values()) {
            var td = EntityUtils.findTextDisplay(uuid);
            if (td != null && !td.isDead()) td.remove();
        }
        lastNotifAtMillis.clear();
        notifEntity.clear();
        notifAnchor.clear();
        notifBase.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        if (!enabled) return;
        Chunk ch = e.getBlock().getChunk();
        long key = (((long) ch.getX()) << 32) ^ (ch.getZ() & 0xffffffffL);
        // If currently throttled, allow 1 in N pulses; otherwise suppress
        long until = throttleUntilTick.getOrDefault(key, 0L);
        if (tickNow < until) {
            int n = dutyCounter.getOrDefault(key, 0) + 1;
            dutyCounter.put(key, n);
            if (n % dutyPassEvery != 0) {
                e.setNewCurrent(e.getOldCurrent());
                suppressedCount++;
                return;
            }
            // else pass-through this pulse but do not count toward window toggles to avoid immediate re-trigger
            return;
        }

        int c = toggles.getOrDefault(key, 0) + 1;
        toggles.put(key, c);
        if (c > toggleLimit) {
            // Enter throttled state with exponential backoff cooldown
            int currentCd = cooldownTicksCurrent.getOrDefault(key, cooldownInitialTicks);
            throttleUntilTick.put(key, tickNow + currentCd);
            int nextCd = (int) Math.min((long) (currentCd * backoffMultiplier), (long) cooldownMaxTicks);
            cooldownTicksCurrent.put(key, nextCd);
            dutyCounter.put(key, 0);
            // Cancel this pulse
            e.setNewCurrent(e.getOldCurrent());
            suppressedCount++;
            if (c == toggleLimit + 1) {
                logger.warn("Redstone throttled in chunk {},{}", ch.getX(), ch.getZ());
                maybeNotifyHologram(e.getBlock().getLocation(), ch, key, currentCd, nextCd);
            }
        }
    }

    private void maybeNotifyHologram(Location at, Chunk ch, long key, int cooldownTicks, int nextCooldownTicks) {
        if (!notifEnabled) return;
        long now = System.currentTimeMillis();

        World w = ch.getWorld();
        Location anchor = at.toCenterLocation().add(0, 1.2, 0);
    String base = "<white>Chunk</white> <yellow>" + ch.getX() + "," + ch.getZ() + "</yellow> " +
        "<red>REDSTONE DITAHANKAN</red> <gray>(limit=" + toggleLimit + ", cd=" + cooldownTicks + "→" + nextCooldownTicks + ")</gray>" +
        "\n<white>Laporkan ke admin jika ini mengganggu farm</white>";
        // If one already exists for this chunk, refresh it instead of spawning a new one
        java.util.UUID existingId = notifEntity.get(key);
        TextDisplay existing = existingId != null ? EntityUtils.findTextDisplay(existingId) : null;
        // If we know the hologram UUID but it's not currently loaded, avoid spawning a duplicate
        if (existingId != null && existing == null) {
            lastNotifAtMillis.put(key, now);
            notifAnchor.put(key, anchor.clone());
            notifBase.put(key, base);
            return;
        }
        if (existing != null && !existing.isDead()) {
            // rate limit text updates to avoid spammy edits
            long lastUpdateTick = lastTextUpdateTick.getOrDefault(key, -999999L);
            if (notifMinUpdateIntervalTicks > 0 && (tickNow - lastUpdateTick) < notifMinUpdateIntervalTicks) {
                // Just extend TTL, move anchor, and return without heavy text edits
                lastNotifAtMillis.put(key, now);
                notifAnchor.put(key, anchor.clone());
                existing.teleport(anchor);
                return;
            }
            lastTextUpdateTick.put(key, tickNow);
            lastNotifAtMillis.put(key, now); // reset TTL
            notifAnchor.put(key, anchor.clone());
            notifBase.put(key, base);
            int secs = (int) Math.max(0, (notifTtlSeconds * 1000L + 500) / 1000L);
            Component text = MiniMessage.miniMessage().deserialize(base + "\n<gray>Hilang dalam</gray> [<white>" + secs + "s</white>]");
            existing.text(text);
            existing.teleport(anchor);
            return;
        }

        // No current hologram → respect cooldown before creating a new one
        long last = lastNotifAtMillis.getOrDefault(key, 0L);
        if (now - last < notifCooldownSeconds * 1000L) return; // avoid spam
        // Enforce global cap
        if (notifEntity.size() >= notifMaxActive) {
            if (!notifReplaceOldest) return;
            // Evict the oldest shown hologram
            Long oldestKey = null; Long oldestTime = null;
            for (var entry : notifOrder.entrySet()) {
                if (oldestKey == null || entry.getValue() < oldestTime) { oldestKey = entry.getKey(); oldestTime = entry.getValue(); }
            }
            if (oldestKey != null) {
                var uuidOld = notifEntity.remove(oldestKey);
                var tdOld = uuidOld != null ? EntityUtils.findTextDisplay(uuidOld) : null;
                if (tdOld != null && !tdOld.isDead()) tdOld.remove();
                lastNotifAtMillis.remove(oldestKey); notifAnchor.remove(oldestKey); notifBase.remove(oldestKey);
                notifOrder.remove(oldestKey); lastTextUpdateTick.remove(oldestKey);
            } else { return; }
        }
        lastNotifAtMillis.put(key, now);
        notifAnchor.put(key, anchor.clone());
        notifBase.put(key, base);
        int secs = (int) Math.max(0, (notifTtlSeconds * 1000L + 500) / 1000L);
        Component text = MiniMessage.miniMessage().deserialize(base + "\n<gray>Hilang dalam</gray> [<white>" + secs + "s</white>]");
        TextDisplay td = w.spawn(anchor, TextDisplay.class, d -> {
            d.text(text);
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setShadowed(false);
            try { d.setAlignment(TextDisplay.TextAlignment.CENTER); } catch (Throwable ignored) {}
            try { d.setTextOpacity((byte) 0xFF); } catch (Throwable ignored) {}
            try { d.setViewRange(32.0f); } catch (Throwable ignored) {}
            try { d.setInterpolationDelay(0); } catch (Throwable ignored) {}
            try { d.setInterpolationDuration(0); } catch (Throwable ignored) {}
            try { d.setSeeThrough(true); } catch (Throwable ignored) {}
            try { d.setDefaultBackground(false); } catch (Throwable ignored) {}
            try { d.setLineWidth(notifLineWidth); } catch (Throwable ignored) {}
            try { d.setBackgroundColor(Color.fromARGB(notifBgAlpha, 0, 0, 0)); } catch (Throwable ignored) {}
            if (notifFullBright) { try { d.setBrightness(new Display.Brightness(15, 15)); } catch (Throwable ignored) {} }
        });
        notifEntity.put(key, td.getUniqueId());
        notifOrder.put(key, now);
        lastTextUpdateTick.put(key, tickNow);
    }

    private void updateNotificationCountdowns() {
        long nowMs = System.currentTimeMillis();
        for (var entry : notifEntity.entrySet()) {
            long key = entry.getKey();
        var td = EntityUtils.findTextDisplay(entry.getValue());
            if (td == null || td.isDead()) continue;
            long start = lastNotifAtMillis.getOrDefault(key, nowMs);
            long remaining = Math.max(0, (notifTtlSeconds * 1000L) - (nowMs - start));
            int secs = (int) ((remaining + 500) / 1000L);
            String base = notifBase.getOrDefault(key, "");
            Component text = MiniMessage.miniMessage().deserialize(base + "\n<gray>Hilang dalam</gray> [<white>" + secs + "s</white>]");
            td.text(text);
            // Keep the hologram anchored at spawn location
            Location anchor = notifAnchor.get(key);
            if (anchor != null) td.teleport(anchor);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        // Clean up stray redstone holograms in this chunk that are no longer tracked
        Chunk ch = e.getChunk();
        var trackedIds = new java.util.HashSet<>(notifEntity.values());
        for (var ent : ch.getEntities()) {
            if (ent instanceof TextDisplay td) {
                // We did not tag these displays; rely on tracking map only
                if (!trackedIds.contains(td.getUniqueId())) continue;
                // Tracked ones are fine; skip removal here
            }
        }
        // If a tracked hologram UUID in this chunk isn't actually present, drop mapping so we can respawn on next event
        var toDrop = new java.util.ArrayList<Long>();
        for (var entry : notifEntity.entrySet()) {
            var id = entry.getValue();
            var td = EntityUtils.findTextDisplay(id);
            if (td == null) {
                toDrop.add(entry.getKey());
            }
        }
        for (var key : toDrop) {
            notifEntity.remove(key);
            notifAnchor.remove(key);
            notifBase.remove(key);
            lastTextUpdateTick.remove(key);
        }
    }

    // findTextDisplay moved to EntityUtils

    public int throttledChunkCountLastWindow() { return throttledLastWindow; }
    public long suppressedToggleCount() { return suppressedCount; }
}
