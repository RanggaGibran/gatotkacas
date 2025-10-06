package id.rnggagib.tweaks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.entity.Item;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.bukkit.util.Vector;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.HandlerList;

import java.util.*;
import id.rnggagib.util.EntityUtils;

/**
 * Visual item stacking with modern hologram and timed auto-clear to reduce lag.
 */
public final class ItemStackHologramService implements Listener {
    private final Plugin plugin;
    private final Logger logger;
    private int taskId = -1;

    // Constants
    private static final double HOLO_Y_OFFSET = 0.9;           // vertical offset above item entity
    private static final float HOLO_VIEW_RANGE = 24.0f;        // default text display view range
    private static final double PULL_MAX_SPEED = 0.7;          // clamp speed for pulled items
    private static final double PULL_TELEPORT_Y_OFFSET = 0.05; // slight offset when snap-teleporting
    private static final String HOLO_TAG = "gtk_item_holo";

    // Config
    private boolean enabled;
    private double radius;
    private int lifetimeSeconds;
    private int periodTicks;
    private int minCountToShow;
    private boolean pullEnabled;
    private double pullStrength; // blocks per tick as velocity impulse magnitude
    private double pullTeleportDistance; // if within this, snap-teleport to leader
    // visual
    private boolean holoFullBright;
    private int holoBgAlpha;
    private int holoLineWidth;

    private final Map<UUID, UUID> leaderToHolo = new HashMap<>();
    private final Map<UUID, Long> leaderExpiresAt = new HashMap<>(); // epoch ms

    public ItemStackHologramService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.item-stacks.enabled", false);
        radius = Math.max(0.5, cfg.getDouble("features.item-stacks.radius", 3.0));
        lifetimeSeconds = Math.max(5, cfg.getInt("features.item-stacks.lifetime-seconds", 60));
    // Allow fast updates to reduce perceived delay
    periodTicks = Math.max(1, cfg.getInt("features.item-stacks.update-period-ticks", 5));
        minCountToShow = Math.max(2, cfg.getInt("features.item-stacks.min-count-to-show", 5));
    pullEnabled = cfg.getBoolean("features.item-stacks.pull-enabled", true);
    pullStrength = Math.max(0.0, cfg.getDouble("features.item-stacks.pull-strength", 0.22));
    pullTeleportDistance = Math.max(0.0, cfg.getDouble("features.item-stacks.pull-teleport-distance", 0.35));
    // visual
    holoFullBright = cfg.getBoolean("features.item-stacks.holo-full-bright", true);
    holoBgAlpha = Math.min(255, Math.max(0, cfg.getInt("features.item-stacks.holo-background-alpha", 170)));
    holoLineWidth = Math.max(80, cfg.getInt("features.item-stacks.holo-line-width", 180));
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Item stacks disabled"); return; }
    Bukkit.getPluginManager().registerEvents(this, plugin);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, periodTicks, periodTicks);
        logger.info("Item stacks enabled (r={} blocks, ttl={}s)", radius, lifetimeSeconds);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    HandlerList.unregisterAll(this);
        // Cleanup holograms
        for (var e : leaderToHolo.entrySet()) {
            var holo = EntityUtils.findTextDisplay(e.getValue());
            if (holo != null && !holo.isDead()) holo.remove();
        }
        leaderToHolo.clear();
        leaderExpiresAt.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Set<UUID> activeLeaders = new HashSet<>();
        // Recompute groups per world (union-like BFS to collapse all connected items by similarity within radius)
        for (World w : Bukkit.getWorlds()) {
            var items = new ArrayList<>(w.getEntitiesByClass(Item.class));
            var assigned = new HashSet<UUID>(items.size() * 2);
            for (int i = 0; i < items.size(); i++) {
                Item seed = items.get(i);
                if (!isItemValid(seed) || assigned.contains(seed.getUniqueId())) continue;
                var group = new ArrayList<Item>();
                group.add(seed);
                assigned.add(seed.getUniqueId());
                // BFS within radius for similar items
                for (int gi = 0; gi < group.size(); gi++) {
                    Item cur = group.get(gi);
                    var nearby = cur.getNearbyEntities(radius, radius, radius);
                    for (var e : nearby) {
                        if (!(e instanceof Item it)) continue;
                        if (!isItemValid(it)) continue;
                        if (assigned.contains(it.getUniqueId())) continue;
                        if (!isSimilarIgnoreAmount(seed.getItemStack(), it.getItemStack())) continue;
                        group.add(it);
                        assigned.add(it.getUniqueId());
                    }
                }

                int total = 0;
                for (var it : group) total += Math.max(0, it.getItemStack().getAmount());
                if (total < minCountToShow) {
                    // Clean any hologram that belonged to any member of this under-threshold group
                    for (var it : group) cleanupIfLeader(it.getUniqueId());
                    continue;
                }

                // Choose deterministic leader: largest stack, then lowest UUID
                Item leader = group.get(0);
                int bestAmt = leader.getItemStack().getAmount();
                UUID bestId = leader.getUniqueId();
                for (int gi = 1; gi < group.size(); gi++) {
                    Item it = group.get(gi);
                    int amt = it.getItemStack().getAmount();
                    UUID id = it.getUniqueId();
                    if (amt > bestAmt || (amt == bestAmt && id.compareTo(bestId) < 0)) {
                        leader = it; bestAmt = amt; bestId = id;
                    }
                }
                UUID leaderId = leader.getUniqueId();

                // Retire any old holograms that belonged to other members of this group
                for (var it : group) {
                    UUID mid = it.getUniqueId();
                    if (!mid.equals(leaderId) && leaderToHolo.containsKey(mid)) {
                        cleanupLeader(mid);
                    }
                }

                // Handle countdown
                long clearAt = leaderExpiresAt.getOrDefault(leaderId, now + lifetimeSeconds * 1000L);
                if (now >= clearAt) {
                    // Clear entire group
                    for (var it : group) { if (it.isValid() && !it.isDead()) it.remove(); }
                    cleanupLeader(leaderId);
                    continue;
                } else {
                    if (!leaderExpiresAt.containsKey(leaderId)) clearAt = now + lifetimeSeconds * 1000L;
                    leaderExpiresAt.put(leaderId, clearAt);
                }

                // Ensure/create hologram
                TextDisplay holo = ensureHolo(leader.getWorld(), leader, leaderId);
                int secs = (int) Math.max(0, (clearAt - now + 500) / 1000L);
                Component text = formatText(leader.getItemStack().getType(), total, secs);
                if (holo != null) {
                    holo.text(text);
                    var loc = leader.getLocation().add(0, HOLO_Y_OFFSET, 0);
                    holo.teleport(loc);
                }

                // Gently pull group members toward leader to physically merge and reduce spread
                if (pullEnabled) {
                    var leaderLoc = leader.getLocation();
                    for (var it : group) {
                        if (it.getUniqueId().equals(leaderId)) continue;
                        var from = it.getLocation();
                        Vector delta = leaderLoc.toVector().subtract(from.toVector());
                        double dist = delta.length();
                        if (dist < 1.0E-3) continue;
                        if (pullTeleportDistance > 0.0 && dist <= pullTeleportDistance) {
                            // Snap to near leader to force merge sooner
                            it.teleport(leaderLoc.clone().add(0, PULL_TELEPORT_Y_OFFSET, 0));
                            continue;
                        }
                        if (pullStrength > 0.0) {
                            Vector impulse = delta.normalize().multiply(pullStrength);
                            Vector vel = it.getVelocity().add(impulse);
                            // clamp to avoid excessive speeds
                            double max = PULL_MAX_SPEED;
                            if (vel.lengthSquared() > max*max) vel = vel.normalize().multiply(max);
                            it.setVelocity(vel);
                        }
                    }
                }

                activeLeaders.add(leaderId);
            }
        }

        // Clean up holograms whose leaders vanished (collect first to avoid CME)
        List<UUID> vanished = new ArrayList<>();
        for (var entry : leaderToHolo.entrySet()) {
            UUID lid = entry.getKey();
            var ent = EntityUtils.findItem(lid);
            if (ent == null || ent.isDead() || !ent.isValid()) {
                vanished.add(lid);
            }
        }
        for (UUID lid : vanished) {
            cleanupLeader(lid);
        }

        // Retire holograms for leaders not active this tick (e.g., leadership changed)
        if (!leaderToHolo.isEmpty()) {
            var toRemove = new ArrayList<UUID>();
            for (var lid : leaderToHolo.keySet()) {
                if (!activeLeaders.contains(lid)) toRemove.add(lid);
            }
            for (var id : toRemove) {
                cleanupLeader(id);
            }
        }
    }

    private boolean isItemValid(Item it) {
        return it != null && it.isValid() && !it.isDead() && it.getItemStack() != null && it.getItemStack().getType() != Material.AIR;
    }

    private boolean isSimilarIgnoreAmount(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;
        ItemStack ac = a.clone(); ac.setAmount(1);
        ItemStack bc = b.clone(); bc.setAmount(1);
        return ac.isSimilar(bc);
    }

    private TextDisplay ensureHolo(World w, Item leader, UUID leaderId) {
        UUID hid = leaderToHolo.get(leaderId);
        TextDisplay td = hid != null ? EntityUtils.findTextDisplay(hid) : null;
        // If we have a known hologram UUID but cannot currently resolve it (likely chunk unloaded),
        // do NOT spawn a new one to avoid duplicates. We'll resume updating once it loads back.
        if (hid != null && td == null) return null;
        if (td != null && td.isValid() && !td.isDead()) return td;
        var loc = leader.getLocation().add(0, HOLO_Y_OFFSET, 0);
        td = w.spawn(loc, TextDisplay.class, d -> {
            d.text(Component.text(""));
            d.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            d.setShadowed(false);
            try { d.setAlignment(TextDisplay.TextAlignment.CENTER); } catch (Throwable ignored) {}
            try { d.setTextOpacity((byte) 0xFF); } catch (Throwable ignored) {}
            try { d.setViewRange(HOLO_VIEW_RANGE); } catch (Throwable ignored) {}
            try { d.setInterpolationDelay(0); } catch (Throwable ignored) {}
            try { d.setInterpolationDuration(0); } catch (Throwable ignored) {}
            try { d.setSeeThrough(true); } catch (Throwable ignored) {}
            try { d.setDefaultBackground(false); } catch (Throwable ignored) {}
            try { d.setLineWidth(holoLineWidth); } catch (Throwable ignored) {}
            try { d.setBackgroundColor(Color.fromARGB(holoBgAlpha, 0, 0, 0)); } catch (Throwable ignored) {}
            if (holoFullBright) { try { d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15)); } catch (Throwable ignored) {} }
            try { d.addScoreboardTag(HOLO_TAG); } catch (Throwable ignored) {}
        });
        leaderToHolo.put(leaderId, td.getUniqueId());
        return td;
    }

    private Component formatText(Material mat, int count, int secs) {
        String nice = toNiceName(mat);
    String mm = "<white>" + nice + "</white> <yellow>x" + count + "</yellow> <white>[" + secs + "]</white>";
        return MiniMessage.miniMessage().deserialize(mm);
    }

    private String toNiceName(Material m) {
        String s = m.name().toLowerCase(Locale.ENGLISH).replace('_', ' ');
        StringBuilder sb = new StringBuilder(s.length());
        boolean up = true;
        for (char c : s.toCharArray()) {
            if (up) { sb.append(Character.toUpperCase(c)); up = false; }
            else { sb.append(c); }
            if (c == ' ') up = true;
        }
        return sb.toString();
    }

    private void cleanupIfLeader(UUID leaderId) {
        if (leaderToHolo.containsKey(leaderId)) {
            cleanupLeader(leaderId);
        }
    }

    private void cleanupLeader(UUID leaderId) {
        removeHolo(leaderId);
        leaderExpiresAt.remove(leaderId);
    }

    private void removeHolo(UUID leaderId) {
        UUID hid = leaderToHolo.remove(leaderId);
        if (hid != null) {
            var td = EntityUtils.findTextDisplay(hid);
            if (td != null && !td.isDead()) td.remove();
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        // Remove any stray item holograms in this chunk that are not tracked in leaderToHolo
        Chunk ch = e.getChunk();
        var trackedHoloIds = new java.util.HashSet<>(leaderToHolo.values());
        var presentHoloIds = new java.util.HashSet<java.util.UUID>();
        for (var ent : ch.getEntities()) {
            if (ent instanceof TextDisplay td) {
                try {
                    if (td.getScoreboardTags().contains(HOLO_TAG)) {
                        presentHoloIds.add(td.getUniqueId());
                        if (!trackedHoloIds.contains(td.getUniqueId())) {
                            // Stray, not tracked
                            td.remove();
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        // If a leader's hologram UUID is missing in this chunk but the leader item is here, clear mapping so we can respawn
        var toClear = new java.util.ArrayList<java.util.UUID>();
        for (var entry : leaderToHolo.entrySet()) {
            var leaderId = entry.getKey();
            var holoId = entry.getValue();
            if (presentHoloIds.contains(holoId)) continue;
            var item = EntityUtils.findItem(leaderId);
            if (item == null || !item.isValid() || item.isDead()) { toClear.add(leaderId); continue; }
            try {
                var leaderChunk = item.getLocation().getChunk();
                if (leaderChunk.getX() == ch.getX() && leaderChunk.getZ() == ch.getZ() && leaderChunk.getWorld().equals(ch.getWorld())) {
                    toClear.add(leaderId);
                }
            } catch (Throwable ignored) {}
        }
        for (var lid : toClear) { leaderToHolo.remove(lid); }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk ch = e.getChunk();
        // remove holograms in this chunk immediately to avoid stranded displays
        for (var ent : ch.getEntities()) {
            if (ent instanceof TextDisplay td) {
                try {
                    if (td.getScoreboardTags().contains(HOLO_TAG)) {
                        td.remove();
                    }
                } catch (Throwable ignored) {}
            }
        }

        var toClear = new ArrayList<UUID>();
        for (var entry : leaderToHolo.entrySet()) {
            UUID leaderId = entry.getKey();
            var item = EntityUtils.findItem(leaderId);
            if (item == null || !item.isValid() || item.isDead()) {
                toClear.add(leaderId);
                continue;
            }
            try {
                var leaderChunk = item.getLocation().getChunk();
                if (leaderChunk.getX() == ch.getX() && leaderChunk.getZ() == ch.getZ() && leaderChunk.getWorld().equals(ch.getWorld())) {
                    toClear.add(leaderId);
                }
            } catch (Throwable ignored) {}
        }
        for (var lid : toClear) {
            cleanupLeader(lid);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent e) {
        cleanupLeader(e.getItem().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent e) {
        cleanupLeader(e.getEntity().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent e) {
        cleanupLeader(e.getEntity().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent e) {
        cleanupLeader(e.getItem().getUniqueId());
    }
}
