//! Library mods that Forge mods need but do not bundle, fetched before a Forge launch.
//!
//! Forge 1.12.2 resolves none of this itself, and a missing library fails in one of two ways:
//! - a coremod whose class implements an interface from the library makes LaunchWrapper fail
//!   while loading coremods: the game exits before it opens a window, with no crash report;
//! - an `@Mod` that declares `required-after:<lib>` stops on Forge's "missing mods" screen.
//!
//! 1.12.2 stopped launching (2026-10-03) with BOTH, one after the other: Entity Culling 1.6.3
//! implements `zone.rong.mixinbooter.IEarlyMixinLoader` (MixinBooter missing), and Xaero's
//! Minimap 26.x / World Map 1.45 require `xaerolib@[1.7.0,)` (XaeroLib missing).
//!
//! Detection is by what the jars actually contain, not by mod names, so a mod the player adds
//! later that needs one of these libraries is covered too: a jar needs a library when one of
//! its classes mentions the library's package, and the library is present when a jar ships
//! that package. The library comes from Modrinth (Forge build for this Minecraft version,
//! newest release), sha1-checked, into the per-version folder the launcher already copies into
//! the instance, so the player sees it in the mod list like any other mod.
//!
//! Rules:
//! - Only when an enabled mod needs it and no copy is there (enabled or `.disabled` — a player
//!   who disabled a library on purpose keeps that choice).
//! - Never fatal: offline or no build for this version leaves the folder as it is.

use crate::download::{client, download_file};
use anyhow::{anyhow, Result};
use serde::Deserialize;
use std::io::Read;
use std::path::{Path, PathBuf};

struct Library {
    /// Modrinth project slug.
    slug: &'static str,
    /// Entry-name prefix that only the library jar itself contains.
    provides: &'static str,
    /// Class-file bytes that mean "this class uses the library".
    used_as: &'static [u8],
}

const LIBRARIES: [Library; 2] = [
    Library {
        slug: "mixinbooter",
        provides: "zone/rong/mixinbooter/MixinBooterPlugin",
        used_as: b"zone/rong/mixinbooter/",
    },
    Library { slug: "xaerolib", provides: "xaero/lib/", used_as: b"xaero/lib/" },
];

const MODRINTH: &str = "https://api.modrinth.com/v2";

#[derive(Deserialize)]
struct MrVersion {
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

/// For one jar and each library: (provides it, uses it).
fn scan(jar: &Path) -> Vec<(bool, bool)> {
    let mut out = vec![(false, false); LIBRARIES.len()];
    let Ok(f) = std::fs::File::open(jar) else { return out };
    let Ok(mut zip) = zip::ZipArchive::new(f) else { return out };
    let mut buf = Vec::new();
    for i in 0..zip.len() {
        let Ok(mut e) = zip.by_index(i) else { continue };
        let name = e.name().to_owned();
        if !name.ends_with(".class") {
            continue;
        }
        for (k, lib) in LIBRARIES.iter().enumerate() {
            if name.starts_with(lib.provides) {
                out[k].0 = true;
            }
        }
        if out.iter().all(|r| r.0 || r.1) {
            break;
        }
        buf.clear();
        if e.read_to_end(&mut buf).is_err() {
            continue;
        }
        for (k, lib) in LIBRARIES.iter().enumerate() {
            if !out[k].1 && buf.windows(lib.used_as.len()).any(|w| w == lib.used_as) {
                out[k].1 = true;
            }
        }
    }
    // A library's own classes reference its own package: that is not "needing" it.
    for r in out.iter_mut() {
        if r.0 {
            r.1 = false;
        }
    }
    out
}

/// Jars in `dir`: (path, enabled). `.jar.disabled` and similar count as disabled.
fn jars(dir: &Path) -> Vec<(PathBuf, bool)> {
    let mut out = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for e in rd.flatten() {
            let p = e.path();
            let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_ascii_lowercase();
            if name.starts_with("glass-") {
                continue;
            }
            if name.ends_with(".jar") {
                out.push((p, true));
            } else if name.contains(".jar.") {
                out.push((p, false));
            }
        }
    }
    out
}

/// Slugs of the libraries an enabled mod in `dir` needs and no jar there provides.
fn missing(dir: &Path) -> Vec<&'static str> {
    let mut provided = vec![false; LIBRARIES.len()];
    let mut needed = vec![false; LIBRARIES.len()];
    for (p, enabled) in jars(dir) {
        for (k, (prov, uses)) in scan(&p).into_iter().enumerate() {
            provided[k] |= prov;
            needed[k] |= uses && enabled;
        }
    }
    LIBRARIES.iter().enumerate().filter(|(k, _)| needed[*k] && !provided[*k]).map(|(_, l)| l.slug).collect()
}

async fn resolve(cl: &reqwest::Client, slug: &str, mc: &str) -> Result<Option<MrFile>> {
    let versions: Vec<MrVersion> = cl
        .get(format!("{MODRINTH}/project/{slug}/version"))
        .query(&[("game_versions", format!("[\"{mc}\"]")), ("loaders", "[\"forge\"]".to_owned())])
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    let Some(v) = versions.into_iter().find(|v| v.version_type == "release") else { return Ok(None) };
    let mut files = v.files;
    if files.is_empty() {
        return Ok(None);
    }
    let at = files.iter().position(|f| f.primary).unwrap_or(0);
    Ok(Some(files.swap_remove(at)))
}

async fn install(cl: &reqwest::Client, slug: &str, mc: &str, dir: &Path) -> Result<Option<String>> {
    let Some(file) = resolve(cl, slug, mc).await? else { return Ok(None) };
    // The name comes from the network: keep it a plain file name, and only take Modrinth's CDN.
    let name = Path::new(&file.filename)
        .file_name()
        .and_then(|n| n.to_str())
        .filter(|n| n.to_ascii_lowercase().ends_with(".jar"))
        .ok_or_else(|| anyhow!("unexpected file name for {slug}"))?
        .to_owned();
    if !file.url.starts_with("https://cdn.modrinth.com/") {
        return Err(anyhow!("unexpected download host for {slug}"));
    }
    download_file(cl, &file.url, &dir.join(&name), file.hashes.sha1.as_deref(), |_| {}).await?;
    Ok(Some(name))
}

/// Make sure the libraries the Forge mods in `.minecraft/s1mp1e-mods/<mc>/` need are there.
/// Returns the file names it installed. One library failing does not stop the others.
pub async fn ensure_forge_deps(root: &PathBuf, mc: &str) -> Result<Vec<String>> {
    let dir = root.join("s1mp1e-mods").join(mc);
    let want = missing(&dir);
    if want.is_empty() {
        return Ok(Vec::new());
    }
    let cl = client();
    let mut added = Vec::new();
    let mut first_err = None;
    for slug in want {
        match install(&cl, slug, mc, &dir).await {
            Ok(Some(n)) => added.push(n),
            Ok(None) => {}
            Err(e) => {
                first_err.get_or_insert(e);
            }
        }
    }
    match (added.is_empty(), first_err) {
        (true, Some(e)) => Err(e),
        _ => Ok(added),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn jar(path: &Path, entries: &[(&str, &[u8])]) {
        let f = std::fs::File::create(path).unwrap();
        let mut z = zip::ZipWriter::new(f);
        let opt = zip::write::SimpleFileOptions::default();
        for (name, body) in entries {
            z.start_file(*name, opt).unwrap();
            z.write_all(body).unwrap();
        }
        z.finish().unwrap();
    }

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("s1mp1e-forgedeps-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    const USES_MB: &[u8] = b"\xca\xfe\xba\xbe....zone/rong/mixinbooter/IEarlyMixinLoader....";
    const USES_XL: &[u8] = b"\xca\xfe\xba\xbe....xaero/lib/common/config/Option....";
    const MB_SELF: &str = "zone/rong/mixinbooter/MixinBooterPlugin.class";

    #[test]
    fn detects_each_missing_library() {
        let d = tmp("needs");
        jar(&d.join("entityculling.jar"), &[("dev/tr7zw/A.class", USES_MB)]);
        jar(&d.join("xaerominimap.jar"), &[("xaero/common/B.class", USES_XL)]);
        jar(&d.join("plain.jar"), &[("a/B.class", b"\xca\xfe\xba\xbe nothing here")]);
        assert_eq!(missing(&d), vec!["mixinbooter", "xaerolib"]);
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_present_library_is_not_needed_even_disabled() {
        let d = tmp("present");
        jar(&d.join("entityculling.jar"), &[("dev/tr7zw/A.class", USES_MB)]);
        jar(&d.join("xaerominimap.jar"), &[("xaero/common/B.class", USES_XL)]);
        jar(&d.join("!mixinbooter.jar.disabled"), &[(MB_SELF, USES_MB)]);
        // the library references its own package: that must not read as "needs itself"
        jar(&d.join("xaerolib.jar"), &[("xaero/lib/common/config/Option.class", USES_XL)]);
        assert!(missing(&d).is_empty(), "{:?}", missing(&d));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn nothing_to_do_without_a_dependent_mod() {
        let d = tmp("none");
        jar(&d.join("plain.jar"), &[("a/B.class", b"\xca\xfe\xba\xbe")]);
        // a disabled dependent mod does not count
        jar(&d.join("entityculling.jar.disabled"), &[("dev/tr7zw/A.class", USES_MB)]);
        assert!(missing(&d).is_empty());
        assert!(missing(&d.join("does-not-exist")).is_empty());
        let _ = std::fs::remove_dir_all(&d);
    }

    /// Network test against Modrinth (about 3 MB).
    #[tokio::test]
    async fn fetches_both_libraries_for_1_12_2() {
        let root = tmp("net");
        let dir = root.join("s1mp1e-mods").join("1.12.2");
        std::fs::create_dir_all(&dir).unwrap();
        jar(&dir.join("entityculling.jar"), &[("dev/tr7zw/A.class", USES_MB)]);
        jar(&dir.join("xaerominimap.jar"), &[("xaero/common/B.class", USES_XL)]);
        let mut got = ensure_forge_deps(&root, "1.12.2").await.unwrap();
        got.sort();
        assert_eq!(got.len(), 2, "{got:?}");
        assert!(got[0].to_ascii_lowercase().contains("mixinbooter"), "{got:?}");
        assert!(got[1].to_ascii_lowercase().contains("xaerolib"), "{got:?}");
        assert!(missing(&dir).is_empty(), "both libraries must now count as present");
        // a second run installs nothing
        assert!(ensure_forge_deps(&root, "1.12.2").await.unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }
}
