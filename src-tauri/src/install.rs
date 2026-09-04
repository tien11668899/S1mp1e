//! Install a Minecraft version end-to-end into `.minecraft`: version JSON,
//! client jar, libraries (rule-filtered), asset index + objects, and the exact
//! Java runtime Mojang pairs with the version. Vanilla and Fabric, 1.8.9 → 26.2.

use crate::download::{client, download_file, get_json, get_text};
use crate::meta::*;
use crate::paths::*;
use anyhow::{anyhow, Context, Result};
use serde::Serialize;
use std::path::PathBuf;

/// One progress tick pushed to the UI.
#[derive(Serialize, Clone)]
pub struct Progress {
    pub phase: String,   // manifest | version | client | libraries | assets | java | fabric | done
    pub message: String,
    pub done: u64,       // bytes or item count so far in this phase
    pub total: u64,      // bytes or item count for this phase (0 = indeterminate)
}

pub type Emit = std::sync::Arc<dyn Fn(Progress) + Send + Sync>;

fn tick(emit: &Emit, phase: &str, message: impl Into<String>, done: u64, total: u64) {
    emit(Progress { phase: phase.into(), message: message.into(), done, total });
}

const OS_KEY: &str = "windows-x64";

/// Resolve a version id to its manifest entry url.
async fn manifest_url_for(cl: &reqwest::Client, id: &str) -> Result<String> {
    let man: VersionManifest = get_json(cl, VERSION_MANIFEST).await
        .context("fetch version_manifest_v2")?;
    man.versions
        .into_iter()
        .find(|v| v.id == id)
        .map(|v| v.url)
        .ok_or_else(|| anyhow!("version {id} not found in manifest"))
}

/// Fetch + persist a vanilla version JSON, returning the parsed model.
async fn fetch_version_json(cl: &reqwest::Client, root: &PathBuf, id: &str) -> Result<VersionJson> {
    let url = manifest_url_for(cl, id).await?;
    let text = get_text(cl, &url).await.context("fetch version json")?;
    let dest = version_json(root, id);
    if let Some(p) = dest.parent() { tokio::fs::create_dir_all(p).await?; }
    tokio::fs::write(&dest, &text).await?;
    Ok(serde_json::from_str(&text)?)
}

/// Download every allowed library artifact + native classifier.
async fn install_libraries(
    cl: &reqwest::Client, root: &PathBuf, vj: &VersionJson, emit: &Emit,
) -> Result<()> {
    let libs_root = libraries_dir(root);
    // build the concrete download list first (path, url, sha1, size)
    let mut jobs: Vec<(PathBuf, String, Option<String>, u64)> = Vec::new();
    for lib in &vj.libraries {
        if !Rule::allows(&lib.rules) { continue; }
        if let Some(dl) = &lib.downloads {
            if let Some(a) = &dl.artifact {
                let rel = a.path.clone().unwrap_or_else(|| maven_to_path(&lib.name));
                jobs.push((libs_root.join(&rel), a.url.clone(), a.sha1.clone(), a.size.unwrap_or(0)));
            }
            if let Some(classifiers) = &dl.classifiers {
                if let Some(nat) = &lib.natives {
                    if let Some(cls) = nat.get("windows") {
                        if let Some(a) = classifiers.get(cls) {
                            let rel = a.path.clone().unwrap_or_else(|| maven_to_path(&lib.name));
                            jobs.push((libs_root.join(&rel), a.url.clone(), a.sha1.clone(), a.size.unwrap_or(0)));
                        }
                    }
                }
            }
        } else if let Some(base) = &lib.url {
            // Fabric/legacy: derive path + url from the maven coordinate.
            let rel = maven_to_path(&lib.name);
            let url = format!("{}{}", base.trim_end_matches('/'), format!("/{rel}"));
            jobs.push((libs_root.join(&rel), url, None, 0));
        } else if lib.natives.is_none() {
            // Raw Mojang-style entry (name only, no downloads/url) — e.g. Forge's
            // `net.minecraft:launchwrapper:1.12`. Resolve from the default libraries
            // maven so Forge profiles other launchers wrote get their missing jars.
            let rel = maven_to_path(&lib.name);
            let url = format!("https://libraries.minecraft.net/{rel}");
            jobs.push((libs_root.join(&rel), url, None, 0));
        }
    }
    let total: u64 = jobs.iter().map(|j| j.3).sum();
    let mut done: u64 = 0;
    tick(emit, "libraries", format!("{} 個庫", jobs.len()), 0, total.max(1));
    for (dest, url, sha1, _size) in jobs {
        download_file(cl, &url, &dest, sha1.as_deref(), |n| {
            // per-chunk accounting handled below via closure capture workaround
            let _ = n;
        }).await.with_context(|| format!("library {url}"))?;
        done += std::fs::metadata(&dest).map(|m| m.len()).unwrap_or(0);
        tick(emit, "libraries", "下載庫檔", done, total.max(1));
    }
    Ok(())
}

/// Download the client jar named by the version json.
async fn install_client_jar(
    cl: &reqwest::Client, root: &PathBuf, id: &str, vj: &VersionJson, emit: &Emit,
) -> Result<()> {
    let Some(dl) = &vj.downloads else { return Ok(()); };
    let Some(client_art) = &dl.client else { return Ok(()); };
    let dest = version_jar(root, id);
    let total = client_art.size.unwrap_or(0).max(1);
    tick(emit, "client", "下載遊戲主程式", 0, total);
    let mut got = 0u64;
    download_file(cl, &client_art.url, &dest, client_art.sha1.as_deref(), |n| { got += n; }).await?;
    tick(emit, "client", "下載遊戲主程式", total, total);
    let _ = got;
    Ok(())
}

/// Asset index + all objects into the shared assets store.
async fn install_assets(
    cl: &reqwest::Client, root: &PathBuf, vj: &VersionJson, emit: &Emit,
) -> Result<()> {
    let Some(idx_ref) = &vj.asset_index else { return Ok(()); };
    // persist the index
    let idx_text = get_text(cl, &idx_ref.url).await.context("fetch asset index")?;
    let idx_path = asset_indexes_dir(root).join(format!("{}.json", idx_ref.id));
    if let Some(p) = idx_path.parent() { tokio::fs::create_dir_all(p).await?; }
    tokio::fs::write(&idx_path, &idx_text).await?;
    let index: AssetIndex = serde_json::from_str(&idx_text)?;

    let objects_root = asset_objects_dir(root);
    let total: u64 = idx_ref.total_size.or(idx_ref.size)
        .unwrap_or_else(|| index.objects.values().map(|o| o.size).sum());
    let mut done: u64 = 0;
    let n = index.objects.len();
    tick(emit, "assets", format!("{n} 個資源"), 0, total.max(1));
    for (i, (_name, obj)) in index.objects.iter().enumerate() {
        let sub = &obj.hash[0..2];
        let dest = objects_root.join(sub).join(&obj.hash);
        let url = format!("https://resources.download.minecraft.net/{sub}/{}", obj.hash);
        download_file(cl, &url, &dest, Some(&obj.hash), |_| {}).await
            .with_context(|| format!("asset {}", obj.hash))?;
        done += obj.size;
        if i % 25 == 0 || i + 1 == n {
            tick(emit, "assets", format!("資源 {}/{n}", i + 1), done, total.max(1));
        }
    }
    Ok(())
}

/// Download the Java runtime component this version requires (jre-legacy=8,
/// gamma=17, delta=21, java-runtime-epsilon=25, ...). Idempotent + verified.
async fn install_java(
    cl: &reqwest::Client, root: &PathBuf, jv: &JavaVersion, emit: &Emit,
) -> Result<PathBuf> {
    let out = runtime_dir(root, &jv.component);
    // Idempotent fast path: if this runtime is already unpacked, do NOT re-fetch
    // its manifest or re-hash its ~600 files every launch — just use it.
    if out.join("bin").join("javaw.exe").exists() {
        tick(emit, "java", format!("Java {} 已就緒", jv.major_version), 1, 1);
        return Ok(out);
    }
    let all: JavaRuntimeAll = get_json(cl, JAVA_RUNTIME_ALL).await.context("java-runtime all.json")?;
    let entry = all.get(OS_KEY)
        .and_then(|m| m.get(&jv.component))
        .and_then(|v| v.first())
        .ok_or_else(|| anyhow!("no java runtime {} for {OS_KEY}", jv.component))?;
    let manifest: JavaRuntimeManifest = get_json(cl, &entry.manifest.url).await?;

    let total: u64 = manifest.files.values()
        .filter_map(|f| f.downloads.as_ref().and_then(|d| d.raw.as_ref()).and_then(|r| r.size))
        .sum();
    let mut done: u64 = 0;
    tick(emit, "java", format!("Java {} 執行環境", jv.major_version), 0, total.max(1));

    for (rel, f) in &manifest.files {
        let dest = out.join(rel);
        match f.kind.as_str() {
            "directory" => { tokio::fs::create_dir_all(&dest).await.ok(); }
            "file" => {
                if let Some(raw) = f.downloads.as_ref().and_then(|d| d.raw.as_ref()) {
                    download_file(cl, &raw.url, &dest, raw.sha1.as_deref(), |_| {}).await
                        .with_context(|| format!("java file {rel}"))?;
                    done += raw.size.unwrap_or(0);
                    tick(emit, "java", format!("Java {}", jv.major_version), done, total.max(1));
                }
            }
            _ => {} // "link" — rare on windows; skip
        }
    }
    Ok(out)
}

/// Where the prebuilt liquid-glass client jars live in the S1mp1e repo.
const GLASS_BASE_URL: &str =
    "https://raw.githubusercontent.com/tien11668899/S1mp1e/main/glass-mods";

/// Ensure the liquid-glass client jar for `mc` is present in `.minecraft/s1mp1e-mods/`.
/// On a fresh machine the jar was never built locally, so fetch it from the repo's
/// `glass-mods/`. No-op if already present. A missing build (404) is not an error —
/// the game just launches without glass for that version.
pub async fn ensure_glass(root: &PathBuf, mc: &str, emit: &Emit) -> Result<()> {
    let dst = root.join("s1mp1e-mods").join(format!("glass-{mc}.jar"));
    if dst.exists() {
        return Ok(());
    }
    tick(emit, "glass", format!("下載 {mc} 液態玻璃"), 0, 0);
    let cl = client();
    let url = format!("{GLASS_BASE_URL}/glass-{mc}.jar");
    let resp = match cl.get(&url).send().await {
        Ok(r) if r.status().is_success() => r,
        _ => return Ok(()), // no glass build for this version → skip silently
    };
    let bytes = resp.bytes().await.context("download glass jar")?;
    if let Some(p) = dst.parent() {
        tokio::fs::create_dir_all(p).await?;
    }
    // Atomic: temp + rename so a mid-download abort never leaves a truncated jar.
    let tmp = dst.with_extension("jar.part");
    tokio::fs::write(&tmp, &bytes).await?;
    tokio::fs::rename(&tmp, &dst).await?;
    tick(emit, "glass", format!("{mc} 液態玻璃就緒"), 1, 1);
    Ok(())
}

/// Full vanilla install for `id`. Returns the java runtime dir used (if any).
pub async fn install_version(root: PathBuf, id: String, emit: Emit) -> Result<()> {
    let cl = client();
    tick(&emit, "manifest", "讀取版本清單", 0, 0);
    let vj = fetch_version_json(&cl, &root, &id).await?;

    install_client_jar(&cl, &root, &id, &vj, &emit).await?;
    install_libraries(&cl, &root, &vj, &emit).await?;
    install_assets(&cl, &root, &vj, &emit).await?;
    if let Some(jv) = &vj.java_version {
        install_java(&cl, &root, jv, &emit).await?;
    }
    tick(&emit, "done", format!("{id} 安裝完成"), 1, 1);
    Ok(())
}

/// Install Fabric on top of a base MC version. Picks the newest stable loader,
/// ensures the base version is installed, writes the merged `fabric-loader-…`
/// version json, downloads Fabric libraries, and returns the new version id.
pub async fn install_fabric(root: PathBuf, mc: String, emit: Emit) -> Result<String> {
    let cl = client();
    // base vanilla first
    install_version(root.clone(), mc.clone(), emit.clone()).await?;

    tick(&emit, "fabric", "查詢 Fabric 載入器", 0, 0);
    // Fabric proper covers 1.14+. 1.13.2 and older use LegacyFabric (same API shape,
    // net.legacyfabric.net). Try official first, fall back to LegacyFabric.
    const META_HOSTS: [&str; 2] = ["https://meta.fabricmc.net", "https://meta.legacyfabric.net"];
    let mut picked: Option<(&str, String)> = None;
    for host in META_HOSTS {
        let url = format!("{host}/v2/versions/loader/{mc}");
        if let Ok(loaders) = get_json::<Vec<FabricLoaderInfo>>(&cl, &url).await {
            if let Some(l) = loaders.first() {
                picked = Some((host, l.loader.version.clone()));
                break;
            }
        }
    }
    let (host, loader) = picked
        .ok_or_else(|| anyhow!("no Fabric/LegacyFabric loader for {mc}"))?;

    let profile_url = format!("{host}/v2/versions/loader/{mc}/{loader}/profile/json");
    let text = get_text(&cl, &profile_url).await.context("fabric profile json")?;
    let vj: VersionJson = serde_json::from_str(&text)?;
    let new_id = vj.id.clone();

    // persist under versions/<fabric-loader-...>/
    let dest = version_json(&root, &new_id);
    if let Some(p) = dest.parent() { tokio::fs::create_dir_all(p).await?; }
    tokio::fs::write(&dest, &text).await?;

    // fabric libraries (maven.fabricmc.net + others via lib.url)
    install_libraries(&cl, &root, &vj, &emit).await?;
    tick(&emit, "done", format!("Fabric {loader} 安裝完成"), 1, 1);
    Ok(new_id)
}

/// Install legacy Forge (1.8.x–1.12.2) for a base MC version. These builds don't
/// patch the client jar — the installer just carries a `version.json` profile plus
/// the forge universal jar (which isn't on the public maven). So: pick the build
/// from Forge's promotions, download the installer, lift `version.json` → a version
/// profile, lift the bundled forge jar → the libraries store, install the vanilla
/// base, and return the new `<mc>-forge-<build>` id. Remaining libs come from
/// `ensure_libraries` at launch. (1.13+ Forge uses a different patched installer —
/// not handled here; the glass line only needs 1.8.9 / 1.12.2.)
pub async fn install_forge(root: PathBuf, mc: String, emit: Emit) -> Result<String> {
    use std::io::Read;
    let cl = client();
    tick(&emit, "forge", "查詢 Forge 版本", 0, 0);
    let promos: serde_json::Value =
        get_json(&cl, "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
            .await.context("forge promotions")?;
    let build = promos["promos"].get(format!("{mc}-recommended"))
        .or_else(|| promos["promos"].get(format!("{mc}-latest")))
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow!("no Forge build published for {mc}"))?
        .to_string();
    tick(&emit, "forge", format!("下載 Forge {build} 安裝檔"), 0, 0);
    // Forge's maven directory is usually "<mc>-<build>" (1.12.2-14.23.5.2859), but the
    // older lines append the MC version AGAIN as a branch suffix — 1.8.9's real artifact
    // is "1.8.9-11.15.1.2318-1.8.9". promotions_slim only gives the build number, so try
    // the plain form first and fall back to the branch-suffixed one instead of 404ing.
    let mut full = String::new();
    let mut bytes = None;
    for cand in [format!("{mc}-{build}"), format!("{mc}-{build}-{mc}")] {
        let url = format!(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/{cand}/forge-{cand}-installer.jar");
        match cl.get(&url).send().await {
            Ok(r) if r.status().is_success() => {
                if let Ok(b) = r.bytes().await {
                    full = cand;
                    bytes = Some(b);
                    break;
                }
            }
            _ => {}
        }
    }
    let bytes = bytes.ok_or_else(||
        anyhow!("no Forge installer published for {mc} build {build}"))?;
    let mut zip = zip::ZipArchive::new(std::io::Cursor::new(bytes))
        .context("open forge installer")?;

    // The version profile. Modern-era installers ship a top-level `version.json`,
    // but the LEGACY ones this function targets (verified against the real 1.8.9 and
    // 1.12.2 installers) have no such entry — the profile lives inside
    // `install_profile.json` under "versionInfo", and the universal jar sits at the
    // ARCHIVE ROOT (named by install.filePath), not under a `maven/` tree. Reading
    // only `version.json` meant install_forge could never actually install either of
    // the two Forge versions this launcher supports; it just happened to go unnoticed
    // wherever another launcher had already written the profile.
    // Probe in its own statement so the mutable borrow of `zip` ends before the
    // branch below re-borrows it.
    let has_version_json = zip.by_name("version.json").is_ok();
    let (vtext, id, jar_in_zip, maven_coord) = {
        if has_version_json {
            // Modern layout: profile at the root, universal jar under maven/.
            let mut raw = String::new();
            zip.by_name("version.json")?.read_to_string(&mut raw)?;
            let vj: serde_json::Value = serde_json::from_str(&raw)?;
            let id = vj["id"].as_str()
                .ok_or_else(|| anyhow!("forge version.json has no id"))?.to_string();
            let coord = format!("net.minecraftforge:forge:{full}");
            (raw, id, format!("maven/{}", maven_to_path(&coord)), coord)
        } else {
            let mut prof = String::new();
            zip.by_name("install_profile.json")
                .context("neither version.json nor install_profile.json in installer")?
                .read_to_string(&mut prof)?;
            let pj: serde_json::Value = serde_json::from_str(&prof)?;
            let vi = pj.get("versionInfo")
                .ok_or_else(|| anyhow!("install_profile.json has no versionInfo"))?;
            let id = vi["id"].as_str()
                .ok_or_else(|| anyhow!("forge versionInfo has no id"))?.to_string();
            // install.path is the maven coordinate the profile's libraries reference,
            // install.filePath is the entry name inside this archive.
            let coord = pj["install"]["path"].as_str()
                .unwrap_or(&format!("net.minecraftforge:forge:{full}")).to_string();
            let file = pj["install"]["filePath"].as_str()
                .ok_or_else(|| anyhow!("install_profile.json has no install.filePath"))?
                .to_string();
            (serde_json::to_string_pretty(vi)?, id, file, coord)
        }
    };

    let dest = version_json(&root, &id);
    if let Some(p) = dest.parent() { tokio::fs::create_dir_all(p).await?; }
    tokio::fs::write(&dest, &vtext).await?;

    // Bundled forge jar → libraries/<maven path of the coordinate the profile uses>.
    let rel = maven_to_path(&maven_coord);
    let out = libraries_dir(&root).join(&rel);
    if let Some(p) = out.parent() { tokio::fs::create_dir_all(p).await?; }
    let mut buf = Vec::new();
    zip.by_name(&jar_in_zip).with_context(|| format!("{jar_in_zip} missing in installer"))?
        .read_to_end(&mut buf)?;
    tokio::fs::write(&out, &buf).await?;

    // vanilla base (client jar + assets + the rest of the libraries)
    install_version(root.clone(), mc.clone(), emit.clone()).await?;
    tick(&emit, "done", format!("Forge {build} 安裝完成"), 1, 1);
    Ok(id)
}

/// Ensure the Java runtime a locally-installed version needs is present, without
/// touching Mojang's *version* manifest — so it works for versions this launcher
/// never installed (26.x, Forge, OptiFine, Feather's install). Walks the local
/// `inheritsFrom` chain for the declared `javaVersion`, then installs that runtime
/// component only if it is missing (idempotent; a no-op once unpacked).
pub async fn ensure_java(root: PathBuf, id: String, emit: Emit) -> Result<()> {
    // find the javaVersion declared anywhere in the local inheritance chain
    let mut cur = id.clone();
    let mut jv: Option<JavaVersion> = None;
    for _ in 0..8 {
        let p = version_json(&root, &cur);
        let text = tokio::fs::read_to_string(&p).await
            .with_context(|| format!("read version json {}", p.display()))?;
        let vj: VersionJson = serde_json::from_str(&text)
            .with_context(|| format!("parse version json {}", p.display()))?;
        if let Some(j) = &vj.java_version { jv = Some(j.clone()); }
        match vj.inherits_from { Some(par) => cur = par, None => break }
    }
    // pre-1.7 versions omit javaVersion → Mojang pairs them with jre-legacy (Java 8)
    let jv = jv.unwrap_or(JavaVersion { component: "jre-legacy".into(), major_version: 8 });
    if runtime_dir(&root, &jv.component).join("bin").join("javaw.exe").exists() {
        return Ok(());
    }
    let cl = client();
    install_java(&cl, &root, &jv, &emit).await?;
    Ok(())
}

/// Ensure every library a locally-installed version needs is on disk, walking the
/// `inheritsFrom` chain and downloading only what's missing (download_file skips
/// files already present). Completes Forge/OptiFine profiles another launcher
/// wrote but whose libraries S1mp1e never fetched (the classpath silently drops
/// missing jars, so a missing launchwrapper = instant crash). Cheap no-op once
/// everything is present.
pub async fn ensure_libraries(root: PathBuf, id: String, emit: Emit) -> Result<()> {
    let cl = client();
    let mut cur = id;
    for _ in 0..8 {
        let p = version_json(&root, &cur);
        let text = tokio::fs::read_to_string(&p).await
            .with_context(|| format!("read version json {}", p.display()))?;
        let vj: VersionJson = serde_json::from_str(&text)
            .with_context(|| format!("parse version json {}", p.display()))?;
        install_libraries(&cl, &root, &vj, &emit).await?;
        match vj.inherits_from { Some(par) => cur = par, None => break }
    }
    Ok(())
}
