package id.rnggagib.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player particle limit (client-side): simple GUI to choose percentage and store in PDC.
 * Packet layer will downsample WORLD_PARTICLES accordingly.
 */
public final class ParticleLimitService implements Listener, org.bukkit.command.TabExecutor {
    private final Plugin plugin;
    private final Logger logger;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final NamespacedKey pdcKey;
    private boolean enabled = true;

    // in-memory cache for fast reads from packet layer
    private final Map<UUID, Integer> percentByPlayer = new HashMap<>(); // 0..100

    public ParticleLimitService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.pdcKey = new NamespacedKey(plugin, "gtk_plimit");
    }

    public void loadFromConfig() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.particle-limit.enabled", true);
    }

    public void start() {
        stop();
        if (!enabled) { logger.info("Particle limit disabled"); return; }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Preload online players (reload case)
        for (Player p : Bukkit.getOnlinePlayers()) loadFromPdc(p);
        logger.info("Particle limit enabled");
    }

    public void stop() {
        // Listener unregistered on plugin disable automatically
    }

    public int getPercent(@NotNull Player p) {
        if (!enabled) return 100;
        return percentByPlayer.getOrDefault(p.getUniqueId(), 100);
    }

    private void setPercent(@NotNull Player p, int percent) {
        percent = Math.max(0, Math.min(100, percent));
        percentByPlayer.put(p.getUniqueId(), percent);
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.set(pdcKey, PersistentDataType.INTEGER, percent);
        p.sendMessage(mm.deserialize("<gray>Particle limit:</gray> <green>" + percent + "%</green>"));
    }

    private void loadFromPdc(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Integer pct = pdc.get(pdcKey, PersistentDataType.INTEGER);
        percentByPlayer.put(p.getUniqueId(), pct == null ? 100 : Math.max(0, Math.min(100, pct)));
    }

    private Inventory buildMenu(Player p) {
        Inventory inv = Bukkit.createInventory(p, 27, Component.text("Particle Limit"));
        int current = getPercent(p);
        addOption(inv, 10, 0, current);
        addOption(inv, 12, 25, current);
        addOption(inv, 14, 50, current);
        addOption(inv, 16, 75, current);
        addOption(inv, 22, 100, current);
        return inv;
    }

    private void addOption(Inventory inv, int slot, int percent, int current) {
        Material mat;
        if (percent == 0) mat = Material.RED_STAINED_GLASS_PANE;
        else if (percent == 100) mat = Material.LIME_STAINED_GLASS_PANE;
        else mat = Material.YELLOW_STAINED_GLASS_PANE;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(percent + "%"));
        if (percent == current) meta.lore(java.util.List.of(Component.text("Selected")));
        it.setItemMeta(meta);
        inv.setItem(slot, it);
    }

    // Command: /plimit
    @Override
    public boolean onCommand(@NotNull org.bukkit.command.CommandSender sender, @NotNull org.bukkit.command.Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only");
            return true;
        }
        if (!sender.hasPermission("gatotkacas.plimit")) {
            sender.sendMessage("No permission");
            return true;
        }
        p.openInventory(buildMenu(p));
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(@NotNull org.bukkit.command.CommandSender sender, @NotNull org.bukkit.command.Command command, @NotNull String alias, @NotNull String[] args) {
        return java.util.Collections.emptyList();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { loadFromPdc(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // ensure persisted
        Player p = e.getPlayer();
        Integer pct = percentByPlayer.get(p.getUniqueId());
        if (pct != null) p.getPersistentDataContainer().set(pdcKey, PersistentDataType.INTEGER, pct);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
    if (Component.text("Particle Limit").equals(e.getView().title())) {
            e.setCancelled(true);
            if (e.getClickedInventory() == null) return;
            int slot = e.getSlot();
            Integer choose = switch (slot) {
                case 10 -> 0;
                case 12 -> 25;
                case 14 -> 50;
                case 16 -> 75;
                case 22 -> 100;
                default -> null;
            };
            if (choose != null) {
                setPercent(p, choose);
                p.closeInventory();
            }
        }
    }
}
