//! The mods every Fabric profile gets by default: Fabric API (the glass client needs it),
//! Sodium, MaLiLib, Item Scroller and Entity Culling.
//!
//! They are fetched from Modrinth into the per-version folder the launcher already loads
//! (`.minecraft/s1mp1e-mods/<mc>/`, see `launch::pick_user_mods`), so they show up in the
//! launcher's mod list like any mod the player added and can be updated, disabled or
//! removed there.
//!
//! Rules:
//! - A mod that is already in the folder (any version, also a `.jar.disabled` one) is left
//!   alone — presence is decided by the `id` in the jar's `fabric.mod.json`, not by file name.
//! - Each mod is offered once per version: a `.s1mp1e-defaults` file in the folder lists what
//!   was offered, so a mod the player deleted stays deleted. Fabric API is the exception, it
//!   is restored whenever it is missing (without it the glass client renders nothing).
//! - This runs on every Fabric launch, so installs made by an earlier launcher (no marker
//!   yet) receive the default set on their next launch.
//! - Never fatal: offline, a version Modrinth has no build for, or a failed download keep
//!   whatever is installed; a failed mod is simply tried again next time.
//!
//! Sodium is pinned on the versions whose glass Video Settings page is written against a
//! specific Sodium options screen; everything else takes the newest release for the version.

use crate::download::{client, download_file};
use crate::install::{Emit, Progress};
use anyhow::{anyhow, Result};
use serde::Deserialize;
use std::collections::BTreeSet;
use std::io::Read;
use std::path::{Path, PathBuf};

struct DefaultMod {
    /// Modrinth project slug.
    slug: &'static str,
    /// `fabric.mod.json` ids that count as "this mod is installed".
    ids: &'static [&'static str],
    name: &'static str,
}

const MODS: [DefaultMod; 5] = [
    DefaultMod { slug: "fabric-api", ids: &["fabric-api", "fabric"], name: "Fabric API" },
    DefaultMod { slug: "sodium", ids: &["sodium"], name: "Sodium" },
    DefaultMod { slug: "malilib", ids: &["malilib"], name: "MaLiLib" },
    DefaultMod { slug: "item-scroller", ids: &["itemscroller"], name: "Item Scroller" },
    DefaultMod { slug: "entityculling", ids: &["entityculling"], name: "Entity Culling" },
];

/// (slug, mc, Modrinth version_number). The glass restyle of Sodium's settings screen on
/// these versions targets this Sodium line's options GUI; newer Sodium replaced that GUI.
const PINS: [(&str, &str, &str); 2] = [
    ("sodium", "26.2", "mc26.2-0.9.1-fabric"),
    ("sodium", "1.21.1", "mc1.21.1-0.6.13-fabric"),
];

const MARKER: &str = ".s1mp1e-defaults";
const MODRINTH: &str = "https://api.modrinth.com/v2";

#[derive(Deserialize)]
struct MrVersion {
    version_number: String,
    version_type: String,
    files: Vec<MrFile>,
}

#[derive(Deserialize)]
struct MrFile {
    url: String,
    filename: String,
    #[serde(default)]
    primary: bool,
    #[serde(default)]
    hashes: MrHashes,
}

#[derive(Deserialize, Default)]
struct MrHashes {
    #[serde(default)]
    sha1: Option<String>,
}

fn tick(emit: &Emit, message: String, done: u64, total: u64) {
    emit(Progress { phase: "mods".into(), message, done, total });
}

/// The `id` of a Fabric mod jar, or None when it is not one / unreadable.
fn fabric_mod_id(jar: &Path) -> Option<String> {
    let f = std::fs::File::open(jar).ok()?;
    let mut zip = zip::ZipArchive::new(f).ok()?;
    let mut entry = zip.by_name("fabric.mod.json").ok()?;
    let mut text = String::new();
    entry.read_to_string(&mut text).ok()?;
    if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
        return v.get("id").and_then(|i| i.as_str()).map(str::to_owned);
    }
    // Some mods ship a fabric.mod.json strict JSON rejects (raw newlines in the description):
    // take the first top-level-looking "id": "…" pair instead.
    let at = text.find("\"id\"")?;
    let rest = &text[at + 4..];
    let start = rest.find('"')? + 1;
    let end = rest[start..].find('"')? + start;
    Some(rest[start..end].to_owned())
}

/// Ids of every Fabric mod in `dir` (enabled or `.disabled`).
fn installed_ids(dir: &Path) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for e in rd.flatten() {
            let p = e.path();
            let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_ascii_lowercase();
            if name.ends_with(".jar") || name.contains(".jar.") {
                if let Some(id) = fabric_mod_id(&p) {
                    out.insert(id);
                }
            }
        }
    }
    out
}

fn read_marker(dir: &Path) -> BTreeSet<String> {
    std::fs::read_to_string(dir.join(MARKER))
        .map(|s| s.lines().map(|l| l.trim().to_owned()).filter(|l| !l.is_empty()).collect())
        .unwrap_or_default()
}

fn write_marker(dir: &Path, offered: &BTreeSet<String>) {
    let body: String = offered.iter().map(|s| format!("{s}\n")).collect();
    let _ = std::fs::write(dir.join(MARKER), body);
}

/// The file to install for `m` on `mc`: Ok(None) = Modrinth has no Fabric build for it.
async fn resolve(cl: &reqwest::Client, m: &DefaultMod, mc: &str) -> Result<Option<MrFile>> {
    let url = format!("{MODRINTH}/project/{}/version", m.slug);
    let versions: Vec<MrVersion> = cl
        .get(&url)
        .query(&[("game_versions", format!("[\"{mc}\"]")), ("loaders", "[\"fabric\"]".to_owned())])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let pin = PINS.iter().find(|(slug, v, _)| *slug == m.slug && *v == mc).map(|(_, _, n)| *n);
    let chosen = pin
        .and_then(|n| versions.iter().position(|v| v.version_number == n))
        .or_else(|| versions.iter().position(|v| v.version_type == "release"))
        .or(if versions.is_empty() { None } else { Some(0) });
    let Some(i) = chosen else { return Ok(None) };
    let mut files = versions.into_iter().nth(i).map(|v| v.files).unwrap_or_default();
    if files.is_empty() {
        return Ok(None);
    }
    let at = files.iter().position(|f| f.primary).unwrap_or(0);
    Ok(Some(files.swap_remove(at)))
}

/// Make sure the default Fabric mods are in `.minecraft/s1mp1e-mods/<mc>/`. See the module doc.
pub async fn ensure_default_mods(root: &PathBuf, mc: &str, emit: &Emit) -> Result<()> {
    let dir = root.join("s1mp1e-mods").join(mc);
    let present = installed_ids(&dir);
    let mut offered = read_marker(&dir);
    let wanted: Vec<&DefaultMod> = MODS
        .iter()
        .filter(|m| !m.ids.iter().any(|id| present.contains(*id)))
        .filter(|m| m.slug == "fabric-api" || !offered.contains(m.slug))
        .collect();
    if wanted.is_empty() {
        // everything is here (or was declined): remember that the set was offered
        if MODS.iter().any(|m| !offered.contains(m.slug)) && dir.exists() {
            offered.extend(MODS.iter().map(|m| m.slug.to_owned()));
            write_marker(&dir, &offered);
        }
        return Ok(());
    }

    let cl = client();
    let total = wanted.len() as u64;
    let mut dirty = false;
    for (n, m) in wanted.iter().enumerate() {
        tick(emit, format!("準備預設模組 {}", m.name), n as u64, total);
        match install_one(&cl, m, mc, &dir).await {
            Ok(_) => {
                offered.insert(m.slug.to_owned());
                dirty = true;
            }
            Err(_) => {} // offline / download failed: try again on the next launch
        }
    }
    // mods that were already present count as offered too, so deleting one later sticks
    for m in MODS.iter() {
        if m.ids.iter().any(|id| present.contains(*id)) && offered.insert(m.slug.to_owned()) {
            dirty = true;
        }
    }
    if dirty {
        let _ = std::fs::create_dir_all(&dir);
        write_marker(&dir, &offered);
    }
    tick(emit, "預設模組就緒".into(), total, total);
    Ok(())
}

/// Ok(true) = downloaded, Ok(false) = no build for this version (still counts as offered).
async fn install_one(cl: &reqwest::Client, m: &DefaultMod, mc: &str, dir: &Path) -> Result<bool> {
    let Some(file) = resolve(cl, m, mc).await? else { return Ok(false) };
    // The name comes from the network: keep it a plain file name, and only take Modrinth's CDN.
    let name = Path::new(&file.filename)
        .file_name()
        .and_then(|n| n.to_str())
        .filter(|n| n.to_ascii_lowercase().ends_with(".jar"))
        .ok_or_else(|| anyhow!("unexpected file name for {}", m.slug))?
        .to_owned();
    if !file.url.starts_with("https://cdn.modrinth.com/") {
        return Err(anyhow!("unexpected download host for {}", m.slug));
    }
    download_file(cl, &file.url, &dir.join(&name), file.hashes.sha1.as_deref(), |_| {}).await?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn quiet() -> Emit {
        std::sync::Arc::new(|_| {})
    }

    fn jars(dir: &Path) -> Vec<String> {
        let mut v: Vec<String> = std::fs::read_dir(dir)
            .map(|rd| rd.flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect())
            .unwrap_or_default();
        v.retain(|n| n.ends_with(".jar"));
        v.sort();
        v
    }

    /// Network test against Modrinth (about 6 MB).
    #[tokio::test]
    async fn default_set_is_installed_once_and_respects_removal() {
        let root = std::env::temp_dir().join(format!("s1mp1e-defmods-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let dir = root.join("s1mp1e-mods").join("1.21.1");

        // 1) a profile with no mods: all five arrive, Sodium is the pinned line
        ensure_default_mods(&root, "1.21.1", &quiet()).await.unwrap();
        let ids = installed_ids(&dir);
        for id in ["fabric-api", "sodium", "malilib", "itemscroller", "entityculling"] {
            assert!(ids.contains(id), "{id} should be installed, have {ids:?}");
        }
        let first = jars(&dir);
        assert_eq!(first.len(), 5, "{first:?}");
        assert!(first.iter().any(|n| n.contains("sodium") && n.contains("0.6.13")), "{first:?}");

        // 2) again: nothing changes
        ensure_default_mods(&root, "1.21.1", &quiet()).await.unwrap();
        assert_eq!(jars(&dir), first);

        // 3) the player removes Sodium and Fabric API: Sodium stays gone, Fabric API returns
        for n in &first {
            if n.contains("sodium") || n.contains("fabric-api") {
                std::fs::remove_file(dir.join(n)).unwrap();
            }
        }
        ensure_default_mods(&root, "1.21.1", &quiet()).await.unwrap();
        let ids = installed_ids(&dir);
        assert!(!ids.contains("sodium"), "a removed mod must not come back");
        assert!(ids.contains("fabric-api"), "Fabric API must be restored");

        // 4) a mod that is present under another file name / version is not duplicated
        let other = root.join("s1mp1e-mods").join("1.20.1");
        std::fs::create_dir_all(&other).unwrap();
        let api = jars(&dir).into_iter().find(|n| n.contains("fabric-api")).unwrap();
        std::fs::copy(dir.join(&api), other.join("my own api.jar")).unwrap();
        ensure_default_mods(&root, "1.20.1", &quiet()).await.unwrap();
        let apis = jars(&other).into_iter().filter(|n| fabric_mod_id(&other.join(n)).as_deref() == Some("fabric-api")).count();
        assert_eq!(apis, 1, "an existing Fabric API must not be duplicated");

        // 5) a version Modrinth has nothing for is not an error
        ensure_default_mods(&root, "1.13.2", &quiet()).await.unwrap();

        let _ = std::fs::remove_dir_all(&root);
    }
}
