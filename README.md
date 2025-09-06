# gatotkacas

Modern Paper 1.21+ plugin focused on entity culling performance with optional Rust JNI acceleration and helpful server tweaks.

## Features
- Entity culling engine
  - Spatial limiting (per-player nearby entities, de-dup)
  - Async precompute (off-thread) + safe main-thread apply
  - Native Rust JNI path with batch API; Java fallback
  - Optional SIMD (feature-flagged) and preallocated JNI buffer
  - Metrics, rolling window, alarms, world/chunk filters
  - Per-type thresholds override
- Monitoring
  - Tick monitor with MSPT/TPS rolling averages
  - Optional JSON report output
- Tweaks
  - Hopper tick throttling
  - Aggressive item merge (radius + per-tick cap)
  - Per-world tracking-range hints

## Requirements
- Java 21
- Paper/Purpur 1.21+
- Maven 3.9+
- Optional: Rust toolchain (for native acceleration)

## Build
- Plugin (Java):
  - Run Maven from repo root; the JAR will be in `target/`.
- Native (Rust):
  - Windows: `cargo build --release` (outputs `culling_rs.dll`)
  - Linux: `cargo build --release` (outputs `libculling_rs.so`)
  - macOS: `cargo build --release` (outputs `libculling_rs.dylib`)
  - SIMD: add `--features simd` to enable vectorized batch kernel.

## Deploy
1. Copy the plugin JAR from `target/` to your server `plugins/` folder.
2. (Optional) Place the native library in `plugins/gatotkacas/natives/`:
   - Windows: `culling_rs.dll`
   - Linux: `libculling_rs.so`
   - macOS: `libculling_rs.dylib`
   The plugin can also auto-download a native if configured.
3. Start the server. The plugin will create a default `config.yml` if missing.

## Commands & Permissions
- `/gatotkacas` (alias: `/gtk`)
  - `gatotkacas.use`: access the base command
  - Subcommands: `reload`, `info`

## Configuration
See `src/main/resources/config.yml` for all options. Highlights:

```yaml
features:
  native:
    enabled: true
    library: ""      # leave empty to auto-pick by OS from plugin data folder
    auto-download:
      enabled: false
      url: ""
      sha256: ""
  culling:
    enabled: true
    max-distance: 48.0
    interval-ticks: 20
    max-entities-per-tick: 512
    frustum-approx: false
    whitelist: []
    blacklist: ["PLAYER", "ARMOR_STAND", "ITEM_FRAME"]
    ratio-percent: true
    window-seconds: 60
    worlds-include: []
    worlds-exclude: []
    chunk-radius: 0
    type-thresholds: {}

tweaks:
  enabled: true
  hopper:
    throttle-enabled: false
    interval-ticks: 8
  item:
    aggressive-merge-enabled: false
    merge-radius: 1.5
    max-merge-per-tick: 128
  tracking-range-per-world: {}
```

## Native build tips
- Use release builds; consider `RUSTFLAGS="-C target-cpu=native"` for local targets.
- For CI artifacts, build per-OS and upload the correct filename.
- The plugin logs whether it loaded native or uses Java fallback.

## License
MIT (see `LICENSE`).

## Disclaimer
This plugin adjusts entity visibility heuristics. Always test on a staging server before production use.# gatotkacas (Paper 1.21+)

Modern, reloadable Paper plugin skeleton with MiniMessage, auto-generated YAML config, and a JNI-native bridge scaffold for future high-performance modules.

## Features
- Paper 1.21.x, Java 21, no deprecated APIs (uses getPluginMeta, Component messaging)
- MiniMessage-based messages and configurable strings
- Auto-generates `config.yml` on first run
- Command: `/gatotkacas [reload|info]` with permissions
- Paper `paper-plugin.yml` + legacy `plugin.yml` for compatibility
- Optional native bridge scaffold (disabled by default)

## Build (PowerShell)
```powershell
# Requires: JDK 21 and Maven 3.9+
$env:JAVA_HOME
mvn -v
mvn -DskipTests package
```
Artifact: `target/gatotkacas-0.1.0.jar`

## Install
1. Copy the jar to your Paper/Pufferfish server's `plugins/` folder.
2. Start the server once to generate `plugins/gatotkacas/config.yml`.
3. Edit messages/flags as desired.
4. Use `/gatotkacas reload` to apply changes without restarting.

## Commands & Permissions
- `/gatotkacas info` (permission: `gatotkacas.use`, default: op)
- `/gatotkacas reload` (permission: `gatotkacas.reload`, default: op)

## Config
See `src/main/resources/config.yml`. All messages use MiniMessage 4.x.

## Native bridge (future)
- Toggle via `features.native.enabled`.
- Set `features.native.library` to an absolute path for a `.dll/.so/.dylib` if you build one.
- JNI code should attach/detach threads when calling into the JVM and avoid Bukkit API off the main thread.

## Roadmap (suggested)
- Add performance modules (entity culling, hopper tweaks) with config toggles.
- Rust/C++ library via JNI for heavy math; unit tests in Rust, integration tests in Java.
- Add metrics/reporting and spark integration helpers.

## Compatibility
- Target: Paper 1.21.x. Should also load on Pufferfish (Paper fork). Purpur typically works but is not a performance improvement.

## License
You choose. MIT is a good default for plugins.