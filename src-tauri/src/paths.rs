//! Filesystem layout — identical to the vanilla launcher so S1mp1e shares an
//! existing `.minecraft` with the official launcher / Feather (verified by
//! reverse-engineering Feather's install).

use std::path::PathBuf;

/// The `.minecraft` root. Honours an explicit override, else %APPDATA%\.minecraft.
pub fn mc_root(override_dir: Option<&str>) -> PathBuf {
    if let Some(d) = override_dir {
        if !d.is_empty() {
            return PathBuf::from(d);
        }
    }
    // Windows: %APPDATA%\.minecraft ; other OSes fall back to a sane default.
    if let Some(data) = dirs::data_dir() {
        // dirs::data_dir() on Windows = %APPDATA% (Roaming) — exactly right.
        return data.join(".minecraft");
    }
    PathBuf::from(".minecraft")
}

pub fn versions_dir(root: &PathBuf) -> PathBuf { root.join("versions") }
pub fn version_dir(root: &PathBuf, id: &str) -> PathBuf { versions_dir(root).join(id) }
pub fn version_json(root: &PathBuf, id: &str) -> PathBuf { version_dir(root, id).join(format!("{id}.json")) }
pub fn version_jar(root: &PathBuf, id: &str) -> PathBuf { version_dir(root, id).join(format!("{id}.jar")) }

pub fn libraries_dir(root: &PathBuf) -> PathBuf { root.join("libraries") }
pub fn assets_dir(root: &PathBuf) -> PathBuf { root.join("assets") }
pub fn asset_objects_dir(root: &PathBuf) -> PathBuf { assets_dir(root).join("objects") }
pub fn asset_indexes_dir(root: &PathBuf) -> PathBuf { assets_dir(root).join("indexes") }

/// S1mp1e keeps downloaded Java runtimes under the game root so they travel with
/// the install: `.minecraft/s1mp1e-runtimes/<component>/`.
pub fn runtime_dir(root: &PathBuf, component: &str) -> PathBuf {
    root.join("s1mp1e-runtimes").join(component)
}

/// Convert a Maven coordinate `group:artifact:version[:classifier]` into the
/// `libraries/` relative path the launcher expects.
pub fn maven_to_path(coord: &str) -> String {
    let parts: Vec<&str> = coord.split(':').collect();
    if parts.len() < 3 {
        return coord.replace(':', "/");
    }
    let group = parts[0].replace('.', "/");
    let artifact = parts[1];
    let version = parts[2];
    let classifier = parts.get(3).map(|c| format!("-{c}")).unwrap_or_default();
    format!("{group}/{artifact}/{version}/{artifact}-{version}{classifier}.jar")
}
