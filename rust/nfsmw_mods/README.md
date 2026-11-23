# nfsmw_mods (Rust, coexistence scaffold)

Foundation crate for incremental Rust adoption in **NFSMW Android Evolved**. It coexists with the existing C/C++ and generated recompilation code instead of replacing them.

## Current scope
- Mod manifest/config model (`mod.json` / `nfsmw_mod.json`)
- Safe mod folder name and relative-path validation (no traversal/absolute paths)
- Overlay/path normalization helpers and basic mod-folder discovery utilities
- C FFI exports for native integration:
  - `nfsmw_mods_is_safe_folder_name`
  - `nfsmw_mods_is_safe_relative_path`
- C header: `include/nfsmw_mods.h`

## Local validation
```bash
cd rust/nfsmw_mods
cargo test
```

## Android coexistence (NDK)
Build static libraries per ABI with your preferred Rust+NDK workflow (for example `cargo-ndk`), then pass the ABI-specific library to Android CMake:

```bash
# example (per ABI)
cargo ndk -t arm64-v8a -o ./jniLibs build --release
```

When configuring the Android native build, provide:

- `-DNFSMW_RUST_MODS_LIB=/path/to/<abi>/libnfsmw_mods.a`

`android/app/src/main/cpp/CMakeLists.txt` links the library into `nfsmw_android` and defines `NFS_RUST_MODS` when present. This allows C/C++/JNI code to call Rust helpers for mod validation and future performance-sensitive mod tooling while keeping the game/runtime in existing languages.

## Roadmap
- Use Rust validation in native mod overlay/config loading
- Mod indexing/caching and metadata parsing
- JNI bindings for launcher/mod manager operations
- Optional migration of selected safe, measurable hot paths to Rust
