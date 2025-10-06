package id.rnggagib.tweaks;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.*;

/**
 * Hopper/Inventory Microscheduler
 * - Time-slice InventoryMoveItemEvent per grid cell (token bucket per cell)
 * - Coalesce repeated moves between same source->dest within a short window
 * - Locality-aware refill: more credits when players nearby
 */
public final class HopperMicroschedulerService implements Listener {
    private final Plugin plugin;
    private final Logger logger;

    private boolean enabled;
    private int gridSize; // chunks per cell
    private int baseCreditsPerTick;
    private int nearPlayerBonus;
    private double playerRadius;
    private int coalesceWindowTicks;
    private int maxBurst;
    private int stallBypassAttempts;
    private int maxDebt;

    private int tickTask = -1;
    private long tickNow = 0L;

    private static final class CellKey { final String world; final int gx; final int gz; CellKey(String w,int x,int z){world=w;gx=x;gz=z;} public boolean equals(Object o){if(this==o)return true; if(!(o instanceof CellKey c))return false; return gx==c.gx&&gz==c.gz&&world.equals(c.world);} public int hashCode(){return Objects.hash(world,gx,gz);} }
    private static final class PairKey { final String world; final long a; final long b; PairKey(String w,long a,long b){this.world=w;this.a=a;this.b=b;} public boolean equals(Object o){if(this==o)return true; if(!(o instanceof PairKey p))return false; return a==p.a&&b==p.b&&world.equals(p.world);} public int hashCode(){return Objects.hash(world,a,b);} }
    private static final class StallState { int attempts; long lastTick; }

    private static long blockKey(Block b){ return (((long)b.getX() & 0x3FFFFFF) << 38) | (((long)b.getY() & 0xFFF) << 26) | ((long)b.getZ() & 0x3FFFFFF); }
    private final Map<CellKey, Integer> credits = new HashMap<>();
    private final Map<PairKey, Long> lastTransferTick = new HashMap<>();
    private final Map<PairKey, StallState> stallStates = new HashMap<>();
    private final Map<PairKey, Integer> deniedCounts = new HashMap<>();

    public HopperMicroschedulerService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.hopper-microscheduler.enabled", false);
        gridSize = Math.max(1, cfg.getInt("features.hopper-microscheduler.grid-size-chunks", 2));
        baseCreditsPerTick = Math.max(1, cfg.getInt("features.hopper-microscheduler.base-credits-per-tick", 2));
        nearPlayerBonus = Math.max(0, cfg.getInt("features.hopper-microscheduler.near-player-bonus", 3));
        playerRadius = Math.max(8.0, cfg.getDouble("features.hopper-microscheduler.player-radius", 64.0));
        coalesceWindowTicks = Math.max(1, cfg.getInt("features.hopper-microscheduler.coalesce-window-ticks", 5));
        maxBurst = Math.max(2, cfg.getInt("features.hopper-microscheduler.max-burst", 8));
        stallBypassAttempts = Math.max(1, cfg.getInt("features.hopper-microscheduler.stall-bypass-attempts", 3));
        maxDebt = Math.max(1, cfg.getInt("features.hopper-microscheduler.max-debt", maxBurst));
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Hopper microscheduler disabled"); return; }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        tickTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, 1L);
        logger.info("Hopper microscheduler enabled (grid={} chunks, base={}, bonus={}, window={}t, burst={}, bypass={}, debt={})",
                gridSize, baseCreditsPerTick, nearPlayerBonus, coalesceWindowTicks, maxBurst, stallBypassAttempts, maxDebt);
    }

    public void stop() {
        if (tickTask != -1) { Bukkit.getScheduler().cancelTask(tickTask); tickTask = -1; }
        credits.clear();
        lastTransferTick.clear();
        stallStates.clear();
        deniedCounts.clear();
    }

    private void tick() {
        tickNow++;
        // Refill credits for loaded worlds/chunks that have players nearby
        for (World w : Bukkit.getWorlds()) {
            // Precompute player chunks
            List<Player> players = w.getPlayers();
            if (players.isEmpty()) continue;
            int cellRadius = Math.max(0, (int) Math.ceil(playerRadius / (gridSize * 16.0)));
            int creditAdd = clampTokens(baseCreditsPerTick + nearPlayerBonus);
            for (Player p : players) {
                var loc = p.getLocation();
                int cx = loc.getBlockX() >> 4;
                int cz = loc.getBlockZ() >> 4;
                int pgx = Math.floorDiv(cx, gridSize);
                int pgz = Math.floorDiv(cz, gridSize);
                for (int dx = -cellRadius; dx <= cellRadius; dx++) {
                    for (int dz = -cellRadius; dz <= cellRadius; dz++) {
                        CellKey key = new CellKey(w.getName(), pgx + dx, pgz + dz);
                        credits.merge(key, creditAdd, (oldV, add) -> clampTokens(oldV + add));
                    }
                }
            }
        }
        // Passive refill to prevent starvation in empty regions
        credits.replaceAll((k, v) -> clampTokens(v + baseCreditsPerTick));
        // Cleanup old coalesce entries
        if (tickNow % 20 == 0) {
            lastTransferTick.entrySet().removeIf(e -> (tickNow - e.getValue()) > (coalesceWindowTicks * 4L));
            stallStates.entrySet().removeIf(e -> (tickNow - e.getValue().lastTick) > 200L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        // Determine grid cell by source block location if available
        InventoryHolder srcHolder = e.getSource().getHolder();
        InventoryHolder dstHolder = e.getDestination().getHolder();
        Block srcBlock = holderBlock(srcHolder);
        Block dstBlock = holderBlock(dstHolder);
        if (srcBlock == null || dstBlock == null) return; // non-block inventories (skip scheduling)

        World w = srcBlock.getWorld();
        int scx = srcBlock.getX() >> 4;
        int scz = srcBlock.getZ() >> 4;
        CellKey cell = new CellKey(w.getName(), Math.floorDiv(scx, gridSize), Math.floorDiv(scz, gridSize));

        // Coalesce repeated src->dst within window
        PairKey pair = new PairKey(w.getName(), blockKey(srcBlock), blockKey(dstBlock));
        Long last = lastTransferTick.get(pair);
        if (last != null && (tickNow - last) < coalesceWindowTicks) {
            e.setCancelled(true);
            return;
        }

        int available = credits.computeIfAbsent(cell, k -> clampTokens(baseCreditsPerTick));
        if (available <= 0) {
            StallState stall = stallStates.computeIfAbsent(pair, k -> new StallState());
            stall.attempts++;
            stall.lastTick = tickNow;
            if (stall.attempts < stallBypassAttempts) {
                credits.put(cell, clampTokens(available));
                e.setCancelled(true);
                deniedCounts.merge(pair, 1, Integer::sum);
                return;
            }
            stallStates.remove(pair);
            credits.put(cell, clampTokens(available - 1));
        } else {
            stallStates.remove(pair);
            credits.put(cell, clampTokens(available - 1));
        }
        deniedCounts.remove(pair);
        // mark success so cancelled events don't swallow items
        if (!e.isCancelled()) {
            e.setCancelled(false);
        }
        lastTransferTick.put(pair, tickNow);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        String world = event.getWorld().getName();
        int gx = Math.floorDiv(event.getChunk().getX(), gridSize);
        int gz = Math.floorDiv(event.getChunk().getZ(), gridSize);
        CellKey cell = new CellKey(world, gx, gz);
        credits.remove(cell);
        // remove associated pairs to avoid stale debt
        var removePairs = new ArrayList<PairKey>();
        for (var entry : lastTransferTick.entrySet()) {
            PairKey key = entry.getKey();
            if (!key.world.equals(world)) continue;
            if (isBlockInChunk(key.a, event.getChunk().getX(), event.getChunk().getZ()) ||
                isBlockInChunk(key.b, event.getChunk().getX(), event.getChunk().getZ())) {
                removePairs.add(key);
            }
        }
        for (PairKey key : removePairs) {
            lastTransferTick.remove(key);
            stallStates.remove(key);
            deniedCounts.remove(key);
        }
    }

    private boolean isBlockInChunk(long packed, int chunkX, int chunkZ) {
        int bx = (int) (packed >> 38);
        int bz = (int) (packed & 0x3FFFFFFL);
        if (bx >= (1 << 25)) bx -= (1 << 26);
        if (bz >= (1 << 25)) bz -= (1 << 26);
        int cx = bx >> 4;
        int cz = bz >> 4;
        return cx == chunkX && cz == chunkZ;
    }

    private int clampTokens(int value) {
        if (value > maxBurst) return maxBurst;
        if (value < -maxDebt) return -maxDebt;
        return value;
    }

    private static Block holderBlock(InventoryHolder h) {
        if (h == null) return null;
        if (h instanceof org.bukkit.block.BlockState bs) return bs.getBlock();
        if (h instanceof org.bukkit.entity.minecart.HopperMinecart hm) return hm.getLocation().getBlock();
        if (h instanceof org.bukkit.entity.minecart.StorageMinecart sm) return sm.getLocation().getBlock();
        return null;
    }
}
