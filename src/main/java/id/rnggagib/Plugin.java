package id.rnggagib;

import id.rnggagib.command.GatotkacasCommand;
import id.rnggagib.nativebridge.NativeBridge;
import id.rnggagib.performance.CullingService;
import id.rnggagib.monitor.TickMonitor;
import id.rnggagib.tweaks.TweaksService;
import org.bukkit.plugin.java.JavaPlugin;


public class Plugin extends JavaPlugin implements GatotkacasCommand.Reloadable {
  private NativeBridge nativeBridge;
  private CullingService cullingService;
  private TickMonitor tickMonitor;
  private TweaksService tweaksService;

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

    getSLF4JLogger().info("gatotkacas enabled");
  }

  @Override
  public void onDisable() {
  if (cullingService != null) cullingService.stop();
  if (tickMonitor != null) tickMonitor.stop();
  if (tweaksService != null) tweaksService.stop();
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
}
