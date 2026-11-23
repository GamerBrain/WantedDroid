//! Mod management foundation for the NFSMW Android Evolved recompiled runtime.
//!
//! This crate is an incremental Rust component: it models mod metadata, validates
//! overlay-safe paths, and can plan prioritized file resolution. It is designed to be
//! integrated behind a C FFI/JNI boundary so that new mod tooling and validation logic
//! can be written in Rust without rewriting the existing C/C++ guest runtime at once.

use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::ffi::CStr;
use std::os::raw::c_char;
use std::path::{Component, Path, PathBuf};

/// Categories commonly used by NFSMW mods. A mod may affect several areas; this is metadata
/// for UI/filtering, not a hard restriction.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ModCategory {
    Graphics,
    Textures,
    Cars,
    CarsAddOn,
    CarsReplacement,
    Vinyls,
    BodyKits,
    Audio,
    Ui,
    Scripts,
    Other,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModManifest {
    pub name: String,
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub author: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub category: Option<ModCategory>,
    /// Optional list of top-level game folders touched by the mod (e.g. ["CARS", "GLOBAL"]).
    #[serde(default)]
    pub touches: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InstalledMod {
    pub folder: String,
    pub enabled: bool,
    #[serde(default)]
    pub manifest: Option<ModManifest>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ModsConfig {
    /// Enabled mods in priority order (index 0 = highest priority overlay).
    #[serde(default)]
    pub enabled: Vec<InstalledMod>,
}

#[derive(thiserror::Error, Debug)]
pub enum ModError {
    #[error("invalid mod path: {0}")]
    InvalidPath(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
}

/// Returns true if `relative` stays within the overlay root (no absolute paths, no `..`).
pub fn is_safe_relative_path(relative: &Path) -> bool {
    if relative.as_os_str().is_empty() {
        return true;
    }
    let mut normal = PathBuf::new();
    for comp in relative.components() {
        match comp {
            Component::Normal(_) => normal.push(comp.as_os_str()),
            Component::CurDir => {}
            Component::ParentDir => return false,
            Component::RootDir | Component::Prefix(_) => return false,
        }
    }
    !normal.as_os_str().is_empty() || relative.iter().all(|c| c == ".")
}

/// Validates a mod folder name (single directory name under Mods/).
pub fn is_safe_folder_name(folder: &str) -> bool {
    let f = folder.trim();
    if f.is_empty() || f.len() > 128 { return false; }
    if f == "." || f == ".." { return false; }
    !f.contains('/') && !f.contains('\\') && !f.contains("..") && !f.chars().any(|c| c == '\0')
}

/// Plans a case-insensitive overlay lookup: given enabled mod roots in priority order and a
/// game-relative path, returns the first root containing the file. The actual filesystem walk
/// is performed by the caller/runtime; this helper validates and normalizes intent.
pub fn normalize_game_relative(input: &str) -> Result<PathBuf, ModError> {
    let replaced = input.replace('\\', "/");
    let trimmed = replaced.trim_start_matches('/');
    let path = Path::new(trimmed);
    if !is_safe_relative_path(path) {
        return Err(ModError::InvalidPath(input.to_string()));
    }
    Ok(path.to_path_buf())
}

/// Collects unique top-level folders present in a mod directory, useful for UI summaries.
pub fn top_level_folders(mod_root: &Path) -> std::io::Result<Vec<String>> {
    let mut out = BTreeSet::new();
    for entry in std::fs::read_dir(mod_root)? {
        let entry = entry?;
        if entry.file_type()?.is_dir() {
            if let Some(name) = entry.file_name().to_str() {
                out.insert(name.to_string());
            }
        }
    }
    Ok(out.into_iter().collect())
}

/// Reads `mod.json` or `nfsmw_mod.json` from a mod folder when present.
pub fn read_manifest(mod_root: &Path) -> Result<Option<ModManifest>, ModError> {
    for name in ["mod.json", "nfsmw_mod.json"] {
        let path = mod_root.join(name);
        if path.is_file() {
            let data = std::fs::read_to_string(path)?;
            let manifest: ModManifest = serde_json::from_str(&data)?;
            return Ok(Some(manifest));
        }
    }
    Ok(None)
}

// ---- C FFI (for coexistence with C/C++/JNI Android runtime) -----------------

/// Returns 1 if `folder` is a safe single mod folder name, 0 otherwise.
/// # Safety
/// `folder` must be a valid, NUL-terminated UTF-8 string pointer or NULL.
#[no_mangle]
pub unsafe extern "C" fn nfsmw_mods_is_safe_folder_name(folder: *const c_char) -> i32 {
    if folder.is_null() { return 0; }
    let Ok(s) = CStr::from_ptr(folder).to_str() else { return 0; };
    if is_safe_folder_name(s) { 1 } else { 0 }
}

/// Returns 1 if `path` is a safe relative overlay path (no traversal/absolute), 0 otherwise.
/// # Safety
/// `path` must be a valid, NUL-terminated UTF-8 string pointer or NULL.
#[no_mangle]
pub unsafe extern "C" fn nfsmw_mods_is_safe_relative_path(path: *const c_char) -> i32 {
    if path.is_null() { return 0; }
    let Ok(s) = CStr::from_ptr(path).to_str() else { return 0; };
    let replaced = s.replace('\\', "/");
    let trimmed = replaced.trim_start_matches('/');
    if is_safe_relative_path(Path::new(trimmed)) { 1 } else { 0 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_path_traversal() {
        assert!(!is_safe_relative_path(Path::new("../GLOBAL/test.bin")));
        assert!(!is_safe_relative_path(Path::new("GLOBAL/../../x")));
        assert!(is_safe_relative_path(Path::new("GLOBAL/attrib.bin")));
    }

    #[test]
    fn normalizes_windows_separators() {
        let p = normalize_game_relative("GLOBAL\\attributes.bin").unwrap();
        assert_eq!(p, PathBuf::from("GLOBAL/attributes.bin"));
    }

    #[test]
    fn folder_name_validation() {
        assert!(is_safe_folder_name("MyMod"));
        assert!(!is_safe_folder_name("../evil"));
        assert!(!is_safe_folder_name("a/b"));
        assert!(!is_safe_folder_name(""));
    }
}
