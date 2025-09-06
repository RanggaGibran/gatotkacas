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
import org.bukkit.plugin.java.JavaPlugin;


public class Plugin extends JavaPlugin implements GatotkacasCommand.Reloadable {
  private NativeBridge nativeBridge;
  private CullingService cullingService;
  private TickMonitor tickMonitor;
  private TweaksService tweaksService;
  private AdaptiveDistanceService adaptiveDistanceService;
  private SpawnThrottleService spawnThrottleService;
  private RedstoneGuardService redstoneGuardService;
  private PacketCullingReflectService packetCullingService;

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

  // Try load native bridge if enabled
  nativeBridge = new NativeBridge(getSLF4JLogger(), getDataFolder());
  boolean nativeEnabled = getConfig().getBoolean("features.native.enabled", false);
  String libPath = getConfig().getString("features.native.library", "");
  boolean dlEnabled = getConfig().getBoolean("features.native.auto-download.enabled", false);
  String dlUrl = getConfig().getString("features.native.auto-download.url", "");
  String dlSha = getConfig().getString("features.native.auto-download.sha256", "");
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


  // Adaptive view/sim distance based on MSPT
  adaptiveDistanceService = new AdaptiveDistanceService(this, getSLF4JLogger(), tickMonitor);
  adaptiveDistanceService.loadFromConfig();
  adaptiveDistanceService.start();

  // Spawn throttle and redstone guard
  spawnThrottleService = new SpawnThrottleService(this, getSLF4JLogger());
  spawnThrottleService.loadFromConfig();
  spawnThrottleService.start();

  redstoneGuardService = new RedstoneGuardService(this, getSLF4JLogger());
  redstoneGuardService.loadFromConfig();
  redstoneGuardService.start();

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
    }
    if (redstoneGuardService != null) {
      redstoneGuardService.loadFromConfig();
      redstoneGuardService.start();
    }
    if (packetCullingService != null) {
      packetCullingService.loadFromConfig();
      packetCullingService.start();
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

  @Override
  public String diag() {
    StringBuilder sb = new StringBuilder();
    double mspt = tickMonitor != null ? tickMonitor.avgMspt() : 0.0;
    sb.append("<gray>MSPT:</gray> <green>").append(String.format("%.2f", mspt)).append("</green>\n");
    // Worlds
    sb.append("<gray>World distances:</gray> ");
    var worlds = org.bukkit.Bukkit.getWorlds();
    for (int i = 0; i < worlds.size(); i++) {
      var w = worlds.get(i);
      sb.append("<yellow>").append(w.getName()).append("</yellow>")
        .append("[view=").append(w.getViewDistance()).append(", sim=").append(w.getSimulationDistance()).append("]");
      if (i < worlds.size() - 1) sb.append(", ");
    }
    sb.append("\n");

    // Redstone suppressed info (approx)
    sb.append("<gray>Redstone guard:</gray> ");
    if (redstoneGuardService != null) {
      sb.append("<green>enabled</green> <gray>throttled-chunks:</gray> <yellow>")
        .append(redstoneGuardService.throttledChunkCountLastWindow())
        .append("</yellow> <gray>suppressed-toggles:</gray> <yellow>")
        .append(redstoneGuardService.suppressedToggleCount()).append("</yellow>\n");
    } else {
      sb.append("<red>disabled</red>\n");
    }

    // Spawn throttle stats (we'll expose counters inside service; fallback text if not available)
    if (spawnThrottleService != null) {
      sb.append("<gray>Spawn throttle:</gray> <green>enabled</green>\n");
      var st = spawnThrottleService.getStats();
      sb.append("<gray>  cancelled:</gray> <yellow>").append(st.cancelled()).append("</yellow>")
        .append(" <gray>allowed:</gray> <yellow>").append(st.allowed()).append("</yellow>")
        .append(" <gray>ai-skipped:</gray> <yellow>").append(st.aiSkipped()).append("</yellow>")
        .append("\n");
    } else {
      sb.append("<gray>Spawn throttle:</gray> <red>disabled</red>\n");
    }

    return sb.toString();
  }
}
