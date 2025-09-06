# Native modules

This folder contains Rust crates compiled as JNI dynamic libraries.

- `culling-rs`: exposes `shouldCull` used by the entity culling module.

## Build (Windows, PowerShell)
```powershell
# Requires Rust toolchain (stable), and VS Build Tools
rustup default stable
cd native/culling-rs
cargo build --release
```

The output will be in `target/release/` as `culling_rs.dll`.

To load it, set an absolute path in `plugins/gatotkacas/config.yml`:
```yaml
features:
  native:
    enabled: true
    library: "C:/path/to/culling_rs.dll"
```

Alternatively, add the directory to PATH and rename to `gatotkacas_native.dll` so `System.loadLibrary("gatotkacas_native")` can find it (if you adjust the Java loader accordingly).
