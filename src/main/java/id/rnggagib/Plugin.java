package id.rnggagib;

import id.rnggagib.command.GatotkacasCommand;
import id.rnggagib.nativebridge.NativeBridge;
import id.rnggagib.performance.CullingService;
import id.rnggagib.monitor.TickMonitor;
import id.rnggagib.tweaks.TweaksService;
import id.rnggagib.performance.AdaptiveDistanceService;
import id.rnggagib.performance.SpawnThrottleService;
import id.rnggagib.tweaks.RedstoneGuardService;
import id.rnggagib.performance.PacketCullingReflectService;
import id.rnggagib.tweaks.ItemStackHologramService;
import id.rnggagib.tweaks.SweeperService;
import id.rnggagib.ui.ParticleLimitService;
import org.bukkit.plugin.java.JavaPlugin;
// bStats (shade will relocate packages at build time)
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.MultiLineChart;
import org.bstats.charts.SingleLineChart;


public class Plugin extends JavaPlugin implements GatotkacasCommand.Reloadable {
  private NativeBridge nativeBridge;
  private CullingService cullingService;
  private TickMonitor tickMonitor;
  private TweaksService tweaksService;
  private AdaptiveDistanceService adaptiveDistanceService;
  private SpawnThrottleService spawnThrottleService;
  private RedstoneGuardService redstoneGuardService;
  private PacketCullingReflectService packetCullingService;
  private ItemStackHologramService itemStackHologramService;
  private SweeperService sweeperService;
  private ParticleLimitService particleLimitService;
  private Metrics bstats;

  @Override
  public void onEnable() {
    // Generate default config if missing
    saveDefaultConfig();

    // Register command programmatically using Bukkit CommandMap
    var cmd = new GatotkacasCommand(this);
    org.bukkit.command.Command runtimeCmd = new org.bukkit.command.Command(
        "gatotkacas",
        "Base command for gatotkacas",
        "/gatotkacas [reload|info]",
        java.util.List.of("gtk")
    ) {
      @Override
      public boolean execute(@org.jetbrains.annotations.NotNull org.bukkit.command.CommandSender sender,
                  @org.jetbrains.annotations.NotNull String label,
                  String[] args) {
        if (!testPermission(sender)) return true;
        return cmd.onCommand(sender, this, label, args);
      }

      @Override
      public java.util.@org.jetbrains.annotations.NotNull List<String> tabComplete(@org.jetbrains.annotations.NotNull org.bukkit.command.CommandSender sender,
                                              @org.jetbrains.annotations.NotNull String alias,
                                              String[] args) {
        return cmd.onTabComplete(sender, this, alias, args);
      }
    };
    runtimeCmd.setPermission("gatotkacas.use");
    org.bukkit.Bukkit.getCommandMap().register(getName().toLowerCase(), runtimeCmd);

    // bStats (optional)
    boolean bstatsEnabled = getConfig().getBoolean("features.bstats.enabled", false);
    int bstatsId = getConfig().getInt("features.bstats.service-id", 0);
    if (bstatsEnabled) {
      if (bstatsId > 0) {
        try {
          bstats = new Metrics(this, bstatsId);
          // Simple pies for environment & feature toggles
          bstats.addCustomChart(new SimplePie("paper_version", () -> getServer().getBukkitVersion()));
          bstats.addCustomChart(new SimplePie("java_version", () -> System.getProperty("java.version", "unknown")));
          bstats.addCustomChart(new SimplePie("culling_enabled", () -> getConfig().getBoolean("features.culling.enabled", true) ? "true" : "false"));
          bstats.addCustomChart(new SimplePie("packet_culling_enabled", () -> getConfig().getBoolean("features.packet-culling.enabled", false) ? "true" : "false"));
          bstats.addCustomChart(new SimplePie("redstone_guard_enabled", () -> getConfig().getBoolean("features.redstone-guard.enabled", false) ? "true" : "false"));
          bstats.addCustomChart(new SimplePie("spawn_throttle_enabled", () -> getConfig().getBoolean("features.spawn-throttle.enabled", false) ? "true" : "false"));
          // Is native active
          bstats.addCustomChart(new SimplePie("native_active", () -> nativeBridge != null && nativeBridge.isLoaded() ? "true" : "false"));
          // Culling counts (updated on pull by bStats)
          bstats.addCustomChart(new MultiLineChart("culling_counts", () -> {
            java.util.Map<String, Integer> m = new java.util.HashMap<>();
            m.put("culled", culledLastTick());
            m.put("processed", processedLastTick());
            return m;
          }));
          bstats.addCustomChart(new SingleLineChart("culling_ratio_percent", () -> (int) Math.round(ratioLastTick() * (ratioPercent() ? 1 : 100))));
          // MSPT buckets (one-hot)
          bstats.addCustomChart(new MultiLineChart("mspt_buckets", () -> {
            java.util.Map<String, Integer> m = new java.util.HashMap<>();
            double v = tickMonitor != null ? tickMonitor.avgMspt() : 0.0;
            m.put("lt25", v < 25.0 ? 1 : 0);
            m.put("25_35", v >= 25.0 && v < 35.0 ? 1 : 0);
            m.put("35_45", v >= 35.0 && v < 45.0 ? 1 : 0);
            m.put("gt45", v >= 45.0 ? 1 : 0);
            return m;
          }));
        } catch (Throwable t) {
          getSLF4JLogger().warn("Failed to init bStats, proceeding without metrics", t);
        }
      } else {
        getSLF4JLogger().warn("bStats enabled but features.bstats.service-id is not set (>0). Skipping metrics init.");
      }
    }

  // Try load native bridge if enabled
  nativeBridge = new NativeBridge(getSLF4JLogger(), getDataFolder());
  boolean nativeEnabled = getConfig().getBoolean("features.native.enabled", false);
  String libPath = getConfig().getString("features.native.library", "");
  boolean dlEnabled = getConfig().getBoolean("features.native.auto-download.enabled", false);
  String dlUrl = getConfig().getString("features.native.auto-download.url", "");
  String dlSha = getConfig().getString("features.native.auto-download.sha256", "");
  // Auto-pick per-OS URL/SHA if url is empty
  if (dlEnabled && (dlUrl == null || dlUrl.isBlank())) {
    String os = System.getProperty("os.name", "").toLowerCase();
    String key = os.contains("win") ? "windows" : (os.contains("mac") || os.contains("darwin") ? "macos" : "linux");
    String base = "features.native.auto-download.per-os." + key + ".";
    String kUrl = getConfig().getString(base + "url", "");
    String kSha = getConfig().getString(base + "sha256", "");
    if (kUrl != null && !kUrl.isBlank()) dlUrl = kUrl;
    if (kSha != null && !kSha.isBlank()) dlSha = kSha;
  }
  nativeBridge.tryLoad(nativeEnabled, libPath, dlEnabled, dlUrl, dlSha);

  // Culling module
  cullingService = new CullingService(this, getSLF4JLogger(), nativeBridge);
  cullingService.loadFromConfig();
  cullingService.start();

  // Tick monitor for MSPT/TPS snapshots and reports
  tickMonitor = new TickMonitor(this, getSLF4JLogger(), cullingService);
  tickMonitor.loadFromConfig();
  tickMonitor.start();

  // Tweaks module (hopper throttle, aggressive item merge)
  tweaksService = new TweaksService(this, getSLF4JLogger());
  tweaksService.loadFromConfig();
  tweaksService.start();

  // Visual item stacks with holograms and countdown
  itemStackHologramService = new ItemStackHologramService(this, getSLF4JLogger());
  itemStackHologramService.loadFromConfig();
  itemStackHologramService.start();

  // XP/Projectile sweeper
  sweeperService = new SweeperService(this, getSLF4JLogger());
  sweeperService.loadFromConfig();
  sweeperService.start();

  // Per-player particle limit GUI
  particleLimitService = new ParticleLimitService(this, getSLF4JLogger());
  particleLimitService.loadFromConfig();
  particleLimitService.start();

  // Register /plimit via CommandMap so it's available immediately
  org.bukkit.command.Command plCmd = new org.bukkit.command.Command(
      "plimit",
      "Open particle limit GUI",
      "/plimit",
      java.util.List.of()
  ) {
    @Override
    public boolean execute(@org.jetbrains.annotations.NotNull org.bukkit.command.CommandSender sender,
                           @org.jetbrains.annotations.NotNull String label,
                           String[] args) {
  if (!testPermission(sender)) return true;
  return particleLimitService.onCommand(sender, this, label, args);
    }

    @Override
    public java.util.@org.jetbrains.annotations.NotNull List<String> tabComplete(@org.jetbrains.annotations.NotNull org.bukkit.command.CommandSender sender,
                                                                                @org.jetbrains.annotations.NotNull String alias,
                                                                                String[] args) {
      return particleLimitService.onTabComplete(sender, this, alias, args);
    }
  };
  plCmd.setPermission("gatotkacas.plimit");
  org.bukkit.Bukkit.getCommandMap().register(getName().toLowerCase(), plCmd);


  // Adaptive view/sim distance based on MSPT
  adaptiveDistanceService = new AdaptiveDistanceService(this, getSLF4JLogger(), tickMonitor);
  adaptiveDistanceService.loadFromConfig();
  adaptiveDistanceService.start();

  // Spawn throttle and redstone guard
  spawnThrottleService = new SpawnThrottleService(this, getSLF4JLogger());
  spawnThrottleService.loadFromConfig();
  spawnThrottleService.start();
  tickMonitor.setSpawnThrottleService(spawnThrottleService);

  redstoneGuardService = new RedstoneGuardService(this, getSLF4JLogger());
  redstoneGuardService.loadFromConfig();
  redstoneGuardService.start();
  tickMonitor.setRedstoneGuardService(redstoneGuardService);

  // Optional packet-level culling via ProtocolLib (reflection)
  packetCullingService = new PacketCullingReflectService(this, getSLF4JLogger(), cullingService);
  packetCullingService.loadFromConfig();
  packetCullingService.start();

    getSLF4JLogger().info("gatotkacas enabled");
  }

  @Override
  public void onDisable() {
  if (cullingService != null) cullingService.stop();
  if (tickMonitor != null) tickMonitor.stop();
  if (tweaksService != null) tweaksService.stop();
  if (adaptiveDistanceService != null) adaptiveDistanceService.stop();
  if (spawnThrottleService != null) spawnThrottleService.stop();
  if (redstoneGuardService != null) redstoneGuardService.stop();
  if (packetCullingService != null) packetCullingService.stop();
  if (itemStackHologramService != null) itemStackHologramService.stop();
  if (sweeperService != null) sweeperService.stop();
  if (particleLimitService != null) particleLimitService.stop();
    getSLF4JLogger().info("gatotkacas disabled");
  }

  // Reloadable
  @Override
  public void reload() {
    reloadConfig();
    // Attempt native (only if not already loaded)
    if (nativeBridge != null && !nativeBridge.isLoaded()) {
      boolean nativeEnabled = getConfig().getBoolean("features.native.enabled", false);
      String libPath = getConfig().getString("features.native.library", "");
      boolean dlEnabled = getConfig().getBoolean("features.native.auto-download.enabled", false);
      String dlUrl = getConfig().getString("features.native.auto-download.url", "");
      String dlSha = getConfig().getString("features.native.auto-download.sha256", "");
      if (dlEnabled && (dlUrl == null || dlUrl.isBlank())) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String key = os.contains("win") ? "windows" : (os.contains("mac") || os.contains("darwin") ? "macos" : "linux");
        String base = "features.native.auto-download.per-os." + key + ".";
        String kUrl = getConfig().getString(base + "url", "");
        String kSha = getConfig().getString(base + "sha256", "");
        if (kUrl != null && !kUrl.isBlank()) dlUrl = kUrl;
        if (kSha != null && !kSha.isBlank()) dlSha = kSha;
      }
      nativeBridge.tryLoad(nativeEnabled, libPath, dlEnabled, dlUrl, dlSha);
    }
    if (cullingService != null) {
      cullingService.loadFromConfig();
      cullingService.start();
    }
    if (tickMonitor != null) {
      tickMonitor.loadFromConfig();
      tickMonitor.start();
    }
    if (tweaksService != null) {
      tweaksService.loadFromConfig();
      tweaksService.start();
    }
    if (adaptiveDistanceService != null) {
      adaptiveDistanceService.loadFromConfig();
      adaptiveDistanceService.start();
    }
    if (spawnThrottleService != null) {
      spawnThrottleService.loadFromConfig();
      spawnThrottleService.start();
  if (tickMonitor != null) tickMonitor.setSpawnThrottleService(spawnThrottleService);
    }
    if (redstoneGuardService != null) {
      redstoneGuardService.loadFromConfig();
      redstoneGuardService.start();
  if (tickMonitor != null) tickMonitor.setRedstoneGuardService(redstoneGuardService);
    }
    if (packetCullingService != null) {
      packetCullingService.loadFromConfig();
      packetCullingService.start();
    }
    if (itemStackHologramService != null) {
      itemStackHologramService.loadFromConfig();
      itemStackHologramService.start();
    }
    if (sweeperService != null) {
      sweeperService.loadFromConfig();
      sweeperService.start();
    }
    if (particleLimitService != null) {
      particleLimitService.loadFromConfig();
      particleLimitService.start();
    }
  }

  @Override
  public String version() {
    return getPluginMeta().getVersion();
  }

  @Override
  public String mm(String key, String def) {
    return getConfig().getString(key, def);
  }

  @Override
  public int culledLastTick() {
    return cullingService != null ? cullingService.getLastCulledCount() : 0;
  }

  public int processedLastTick() {
    return cullingService != null ? cullingService.getLastProcessedCount() : 0;
  }

  public double ratioLastTick() {
    return cullingService != null ? cullingService.getLastCullRatio() : 0.0;
  }

  public int windowCulled() { return cullingService != null ? cullingService.getWindowCulled() : 0; }
  public int windowProcessed() { return cullingService != null ? cullingService.getWindowProcessed() : 0; }
  public double windowRatio() { return cullingService != null ? cullingService.getWindowRatio() : 0.0; }
  public boolean ratioPercent() { return cullingService != null && cullingService.isRatioPercent(); }

  // Exposed for reflection packet layer (particle downsample)
  public int particlePercent(org.bukkit.entity.Player p) {
    if (particleLimitService == null) return 100;
    return particleLimitService.getPercent(p);
  }

  @Override
  public String diag() {
    StringBuilder sb = new StringBuilder();
    double mspt = tickMonitor != null ? tickMonitor.avgMspt() : 0.0;
    sb.append("<gold><bold>== Diagnostics ==</bold></gold>\n");
    sb.append("<gray>MSPT:</gray> <green>").append(String.format("%.2f", mspt)).append("</green>\n");
    sb.append("\n<yellow><bold>Worlds</bold></yellow>\n");
    var worlds = org.bukkit.Bukkit.getWorlds();
    for (int i = 0; i < worlds.size(); i++) {
      var w = worlds.get(i);
      sb.append("  <yellow>").append(w.getName()).append("</yellow> ")
        .append("[view=").append(w.getViewDistance()).append(", sim=").append(w.getSimulationDistance()).append("]\n");
    }
    sb.append("\n");

    // Redstone suppressed info (approx)
    sb.append("<yellow><bold>Redstone</bold></yellow>\n");
    if (redstoneGuardService != null) {
      sb.append("  <green>enabled</green> <gray>throttled-chunks:</gray> <yellow>")
        .append(redstoneGuardService.throttledChunkCountLastWindow())
        .append("</yellow> <gray>suppressed-toggles:</gray> <yellow>")
        .append(redstoneGuardService.suppressedToggleCount()).append("</yellow>\n");
    } else {
      sb.append("  <red>disabled</red>\n");
    }

    // Spawn throttle stats (we'll expose counters inside service; fallback text if not available)
    if (spawnThrottleService != null) {
      sb.append("<yellow><bold>Spawn/AI</bold></yellow>\n");
      sb.append("  <green>enabled</green>\n");
      var st = spawnThrottleService.getStats();
      sb.append("  <gray>cancelled:</gray> <yellow>").append(st.cancelled()).append("</yellow> ")
        .append("<gray>allowed:</gray> <yellow>").append(st.allowed()).append("</yellow> ")
        .append("<gray>ai-skipped:</gray> <yellow>").append(st.aiSkipped()).append("</yellow>")
        .append("\n");
    } else {
      sb.append("<yellow><bold>Spawn/AI</bold></yellow>\n  <red>disabled</red>\n");
    }

    return sb.toString();
  }
}
