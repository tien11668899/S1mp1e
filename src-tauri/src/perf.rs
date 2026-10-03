//! Performance pack: optimisation mods the launcher manages by itself, per game version, plus the
//! JVM flags that measured better — so a player gets the gain just by pressing Play.
//!
//! Every entry here was chosen by benchmark (the reproducible in-game harness in
//! `versions/mc262/src/main/java/dev/s1mp1e/client/Bench.java`, driven by `bench.ps1`; results
//! and method in `docs/PERFORMANCE.md`): a mod is in the pack only if it improved the measured
//! frame times without changing what is drawn or how the game plays.
//!
//! How it is applied:
//! - The jars live in `.minecraft/s1mp1e-mods/<mc>/.perf/` — a launcher-owned folder, apart from
//!   the player's own mods (`launch::pick_user_mods` only reads the top level). Each file is
//!   pinned to an exact Modrinth version and checked against its SHA-1 before every launch; a
//!   missing or damaged file is fetched again, only from `https://cdn.modrinth.com/`.
//! - A mod the player already installed themselves (same `fabric.mod.json` id, in
//!   `s1mp1e-mods/<mc>/`, enabled) wins: the pack copy is not loaded, so nothing loads twice.
//! - Failure recovery: before a launch the pack writes `state.json` with `pending`. If the game
//!   then exits with an error within [`EARLY_CRASH_SECS`] while the pack was loaded, the pack is
//!   switched off for that version (reason recorded, newest crash report named) and the next
//!   launch runs without it. A new pack revision (different contents) clears that and tries again.
//! - Everything is logged to `.perf/perf.log`. Nothing outside `.perf/` is touched, so the
//!   player's mods, options and worlds are never modified, and turning the setting off returns
//!   the game to exactly what it was.

use crate::download::{client, download_file};
use crate::install::Emit;
use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

/// A crash this soon after start, with the pack loaded, switches the pack off for that version.
pub const EARLY_CRASH_SECS: u64 = 90;

/// One pinned optimisation mod.
pub struct PerfMod {
    pub mc: &'static str,
    /// Modrinth project slug.
    pub slug: &'static str,
    /// Modrinth `version_number` of the exact build.
    pub version: &'static str,
    /// File name and SHA-1 of that build's primary file.
    pub file: &'static str,
    pub sha1: &'static str,
    /// `fabric.mod.json` ids that mean "already installed by the player".
    pub ids: &'static [&'static str],
}

/// The pack. Filled from benchmark results (see `docs/PERFORMANCE.md`): on 26.2 at 4K with ZGC these
/// three raised the entity-heavy scenario from 381 to 447 FPS (interleaved runs, non-overlapping
/// ranges) and chunk loading from 626 to 673, with no visual change (compared screenshots).
/// Lithium: game-logic optimisation, keeps vanilla behaviour (allowed in speedrunning).
/// FerriteCore: memory layout of block states / models. ImmediatelyFast: batches immediate-mode
/// drawing (entities, text, HUD). Cull Leaves (a culling mod — allowed even though it changes the
/// picture slightly: it drops leaf faces hidden behind other leaves): 4K, terrain-following routes,
/// chunk loading 578 -> 659 FPS and explore 614 -> 651 (non-overlapping ranges). MoreCulling was
/// measured too and did not help. Other versions are not in the pack until measured there.
pub const PACK: &[PerfMod] = &[
    PerfMod {
        mc: "26.2",
        slug: "lithium",
        version: "mc26.2-0.25.3-fabric",
        file: "lithium-fabric-0.25.3+mc26.2.jar",
        sha1: "435ad0289209bb72195e7a423756a03f5dfa5a9a",
        ids: &["lithium"],
    },
    PerfMod {
        mc: "26.2",
        slug: "ferrite-core",
        version: "9.0.0-fabric",
        file: "ferritecore-9.0.0-fabric.jar",
        sha1: "eac76ff0f3753422c61b2c44487d0d195d88d4bc",
        ids: &["ferritecore"],
    },
    PerfMod {
        mc: "26.2",
        slug: "cull-leaves",
        version: "4.1.2+26.2-fabric",
        file: "cullleaves-fabric-4.1.2+26.2.jar",
        sha1: "1df64a439a6cf79b785882c94b39ac04e67c63bd",
        ids: &["cullleaves"],
    },
    PerfMod {
        mc: "26.2",
        slug: "immediatelyfast",
        version: "1.16.5+26.2-fabric",
        file: "ImmediatelyFast-Fabric-1.16.5+26.2.jar",
        sha1: "0286260261591fe77ba0d332d5d5a2d2e868c2c6",
        ids: &["immediatelyfast"],
    },
];

/// JVM flags the pack adds for a given Java feature version, when the player has not chosen a
/// garbage collector themselves. Measured, not guessed — see `docs/PERFORMANCE.md`.
///
/// Java 25 (Minecraft 26.2): ZGC, which is generational-only there. On the benchmark it removed the
/// ~20 ms young-collection pause G1 took about once a second (frames over 20 ms per minute
/// 46–62 → 1), and raised 1% lows ~2.5×. Other Java versions keep their default collector until
/// they are measured: Java 21's ZGC needs `-XX:+ZGenerational` and Java 17's is the older
/// non-generational one, so the 25 result does not carry over. ZGC needs heap headroom (it used
/// ~2.3–2.6 GB of the 4 GB heap where it was measured), so it is only added from 4 GB up.
pub fn jvm_flags(java_major: u32, ram_mb: u32) -> Vec<String> {
    if java_major >= 25 && ram_mb >= 4096 {
        vec!["-XX:+UseZGC".to_string()]
    } else {
        Vec::new()
    }
}

/// Whether the pack adds JVM flags on any Java version (then a launch counts as "with the pack"
/// for crash recovery even when no pack mod applies).
pub fn has_jvm_flags() -> bool {
    [8u32, 17, 21, 25].iter().any(|&j| !jvm_flags(j, 4096).is_empty())
}

/// Whether the player's own JVM arguments already pick a collector or tune it — then the pack
/// adds no GC flags, so nothing is stacked or contradicted.
pub fn user_sets_gc(user_args: &str) -> bool {
    user_args.split_whitespace().any(|a| {
        let a = a.to_ascii_lowercase();
        (a.starts_with("-xx:+use") && a.ends_with("gc"))
            || a.starts_with("-xx:maxgcpausemillis")
            || a.starts_with("-xx:g1")
            || a.starts_with("-xx:+zgenerational")
            || a.starts_with("-xx:-zgenerational")
    })
}

#[derive(Serialize, Deserialize, Default, Debug, Clone)]
pub struct PackState {
    /// Hash of the pack contents this state belongs to; a different pack resets the state.
    #[serde(default)]
    pub revision: String,
    #[serde(default)]
    pub disabled: bool,
    #[serde(default)]
    pub reason: String,
    /// Set right before a launch with the pack loaded; cleared when the launch is judged.
    #[serde(default)]
    pub pending_since: u64,
    #[serde(default)]
    pub ok_launches: u32,
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn pack_for(mc: &str) -> Vec<&'static PerfMod> {
    PACK.iter().filter(|m| m.mc == mc).collect()
}

/// Content hash of the pack for one version (slug, version, sha1 of every entry, and the JVM flags).
pub fn revision(mc: &str) -> String {
    let mut s = String::new();
    for m in pack_for(mc) {
        s.push_str(m.slug);
        s.push('@');
        s.push_str(m.version);
        s.push(':');
        s.push_str(m.sha1);
        s.push(';');
    }
    for f in jvm_flags(25, 4096) {
        s.push_str(&f);
        s.push(' ');
    }
    format!("{:016x}", fnv1a(s.as_bytes()))
}

fn fnv1a(b: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for &x in b {
        h ^= x as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

pub fn perf_dir(root: &Path, mc: &str) -> PathBuf {
    root.join("s1mp1e-mods").join(mc).join(".perf")
}

fn log(dir: &Path, msg: &str) {
    let _ = std::fs::create_dir_all(dir);
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(dir.join("perf.log")) {
        let _ = writeln!(f, "{} {}", now_secs(), msg);
    }
}

pub fn load_state(root: &Path, mc: &str) -> PackState {
    let p = perf_dir(root, mc).join("state.json");
    let mut st: PackState = std::fs::read_to_string(&p)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();
    let rev = revision(mc);
    if st.revision != rev {
        st = PackState { revision: rev, ..Default::default() };
    }
    st
}

fn save_state(root: &Path, mc: &str, st: &PackState) {
    let dir = perf_dir(root, mc);
    let _ = std::fs::create_dir_all(&dir);
    if let Ok(s) = serde_json::to_string_pretty(st) {
        let tmp = dir.join("state.json.tmp");
        if std::fs::write(&tmp, s).is_ok() {
            let _ = std::fs::rename(&tmp, dir.join("state.json"));
        }
    }
}

fn sha1_file(p: &Path) -> Option<String> {
    let mut f = std::fs::File::open(p).ok()?;
    let mut h = sha1_smol::Sha1::new();
    let mut buf = vec![0u8; 1 << 16];
    loop {
        let n = f.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Some(h.digest().to_string())
}

/// `fabric.mod.json` ids of the player's own enabled mods in `s1mp1e-mods/<mc>/`.
fn user_mod_ids(root: &Path, mc: &str) -> BTreeSet<String> {
    let mut ids = BTreeSet::new();
    let dir = root.join("s1mp1e-mods").join(mc);
    let Ok(rd) = std::fs::read_dir(&dir) else { return ids };
    for e in rd.flatten() {
        let p = e.path();
        let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
        if !name.ends_with(".jar") || name.starts_with("glass-") {
            continue;
        }
        if let Some(id) = crate::default_mods::fabric_mod_id(&p) {
            ids.insert(id);
        }
    }
    ids
}

#[derive(Deserialize)]
struct MrVersion {
    version_number: String,
    files: Vec<MrFile>,
}
#[derive(Deserialize)]
struct MrFile {
    url: String,
    filename: String,
    hashes: MrHashes,
}
#[derive(Deserialize)]
struct MrHashes {
    sha1: String,
}

/// Fetch the pinned build into `dir/<file>` (via a temp name, verified, then renamed).
async fn fetch(dir: &Path, m: &PerfMod) -> Result<()> {
    let url = format!(
        "https://api.modrinth.com/v2/project/{}/version?game_versions=%5B%22{}%22%5D&loaders=%5B%22fabric%22%5D",
        m.slug, m.mc
    );
    let cl = client();
    let vers: Vec<MrVersion> = cl.get(&url).send().await?.error_for_status()?.json().await?;
    let v = vers
        .into_iter()
        .find(|v| v.version_number == m.version)
        .ok_or_else(|| anyhow!("{} {} not on Modrinth", m.slug, m.version))?;
    let f = v
        .files
        .into_iter()
        .find(|f| f.hashes.sha1.eq_ignore_ascii_case(m.sha1))
        .ok_or_else(|| anyhow!("{} {}: no file with the pinned SHA-1", m.slug, m.version))?;
    if !f.url.starts_with("https://cdn.modrinth.com/") {
        return Err(anyhow!("{}: refusing download from {}", m.slug, f.url));
    }
    if f.filename != m.file {
        return Err(anyhow!("{}: file name changed ({} != {})", m.slug, f.filename, m.file));
    }
    let tmp = dir.join(format!("{}.part", m.file));
    download_file(&cl, &f.url, &tmp, Some(m.sha1), |_| {}).await?;
    match sha1_file(&tmp) {
        Some(h) if h.eq_ignore_ascii_case(m.sha1) => {
            std::fs::rename(&tmp, dir.join(m.file))?;
            Ok(())
        }
        _ => {
            let _ = std::fs::remove_file(&tmp);
            Err(anyhow!("{}: SHA-1 mismatch after download", m.slug))
        }
    }
}

/// Make sure the pack for `mc` is present and intact, and return the jars to load this launch
/// (empty when the pack is off, switched off after a crash, or has nothing for this version).
/// Writes `active.txt` (one path per line) for `launch::plan_launch`. Never fatal: a mod that
/// cannot be fetched is skipped for this launch and logged.
pub async fn ensure_pack(root: &Path, mc: &str, _emit: &Emit) -> Vec<PathBuf> {
    let dir = perf_dir(root, mc);
    let mods = pack_for(mc);
    let mut active = Vec::new();
    let st = load_state(root, mc);
    if st.disabled {
        log(&dir, &format!("pack {} off for {mc}: {}", st.revision, st.reason));
        write_active(&dir, &active);
        return active;
    }
    if mods.is_empty() {
        write_active(&dir, &active);
        return active;
    }
    let _ = std::fs::create_dir_all(&dir);
    let user = user_mod_ids(root, mc);
    for m in mods {
        if m.ids.iter().any(|id| user.contains(*id)) {
            log(&dir, &format!("{}: the player's own copy is installed, pack copy not loaded", m.slug));
            continue;
        }
        let path = dir.join(m.file);
        let good = path.exists() && sha1_file(&path).map(|h| h.eq_ignore_ascii_case(m.sha1)).unwrap_or(false);
        if !good {
            if path.exists() {
                log(&dir, &format!("{}: SHA-1 mismatch, fetching again", m.slug));
                let _ = std::fs::remove_file(&path);
            }
            match fetch(&dir, m).await {
                Ok(()) => log(&dir, &format!("{} {}: installed", m.slug, m.version)),
                Err(e) => {
                    log(&dir, &format!("{} {}: not available this launch: {e:#}", m.slug, m.version));
                    continue;
                }
            }
        }
        active.push(path);
    }
    // stale files of earlier pack revisions
    if let Ok(rd) = std::fs::read_dir(&dir) {
        let keep: BTreeSet<&str> = pack_for(mc).iter().map(|m| m.file).collect();
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            if n.ends_with(".jar") && !keep.contains(n.as_str()) {
                let _ = std::fs::remove_file(e.path());
                log(&dir, &format!("removed old pack file {n}"));
            }
        }
    }
    write_active(&dir, &active);
    active
}

fn write_active(dir: &Path, active: &[PathBuf]) {
    let _ = std::fs::create_dir_all(dir);
    let s: Vec<String> = active.iter().map(|p| p.to_string_lossy().into_owned()).collect();
    let _ = std::fs::write(dir.join("active.txt"), s.join("\n"));
}

/// The jars `ensure_pack` chose for this launch.
pub fn active_jars(root: &Path, mc: &str) -> Vec<PathBuf> {
    std::fs::read_to_string(perf_dir(root, mc).join("active.txt"))
        .map(|s| s.lines().filter(|l| !l.trim().is_empty()).map(PathBuf::from).filter(|p| p.exists()).collect())
        .unwrap_or_default()
}

/// Call right before starting the game with the pack loaded.
pub fn mark_launch(root: &Path, mc: &str) {
    let mut st = load_state(root, mc);
    st.pending_since = now_secs();
    save_state(root, mc, &st);
}

/// Call when the game has exited. Returns a message when the pack was switched off.
pub fn judge_exit(root: &Path, mc: &str, gamedir: &Path, exit_code: i32) -> Option<String> {
    let mut st = load_state(root, mc);
    if st.pending_since == 0 {
        return None;
    }
    let ran = now_secs().saturating_sub(st.pending_since);
    let started = st.pending_since;
    st.pending_since = 0;
    let dir = perf_dir(root, mc);
    let msg = if exit_code != 0 && ran < EARLY_CRASH_SECS {
        let report = newest_crash_report(gamedir, started);
        st.disabled = true;
        st.reason = format!(
            "the game exited with code {exit_code} after {ran} s while the performance pack was loaded{}",
            report.map(|r| format!(" (crash report: {r})")).unwrap_or_default()
        );
        log(&dir, &format!("pack switched off: {}", st.reason));
        Some(st.reason.clone())
    } else {
        st.ok_launches += 1;
        log(&dir, &format!("launch ok (exit {exit_code}, ran {ran} s)"));
        None
    };
    save_state(root, mc, &st);
    msg
}

fn newest_crash_report(gamedir: &Path, since: u64) -> Option<String> {
    let rd = std::fs::read_dir(gamedir.join("crash-reports")).ok()?;
    rd.flatten()
        .filter_map(|e| {
            let t = e.metadata().ok()?.modified().ok()?.duration_since(UNIX_EPOCH).ok()?.as_secs();
            (t + 5 >= since).then(|| (t, e.file_name().to_string_lossy().into_owned()))
        })
        .max()
        .map(|(_, n)| n)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zgc_only_where_measured() {
        assert_eq!(jvm_flags(25, 4096), vec!["-XX:+UseZGC".to_string()]);
        assert_eq!(jvm_flags(25, 8192), vec!["-XX:+UseZGC".to_string()]);
        assert!(jvm_flags(25, 2048).is_empty(), "too little heap headroom");
        assert!(jvm_flags(21, 8192).is_empty(), "Java 21 not measured");
        assert!(jvm_flags(17, 8192).is_empty());
        assert!(jvm_flags(8, 8192).is_empty());
    }

    #[tokio::test]
    async fn pack_installs_verified_and_respects_player_mods() {
        let root = std::env::temp_dir().join(format!("s1mp1e-perf-net-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let emit: Emit = std::sync::Arc::new(|_| {});
        let jars = ensure_pack(&root, "26.2", &emit).await;
        assert_eq!(jars.len(), 4, "{jars:?}");
        for j in &jars {
            assert!(j.exists());
        }
        // a damaged file is fetched again
        std::fs::write(&jars[0], b"broken").unwrap();
        let again = ensure_pack(&root, "26.2", &emit).await;
        assert_eq!(again.len(), 4);
        assert_ne!(std::fs::read(&again[0]).unwrap(), b"broken");
        // the player's own copy wins: put lithium in the player's folder
        let user = root.join("s1mp1e-mods").join("26.2");
        std::fs::copy(&again.iter().find(|p| p.to_string_lossy().contains("lithium")).unwrap(), user.join("my-lithium.jar")).unwrap();
        let third = ensure_pack(&root, "26.2", &emit).await;
        assert_eq!(third.len(), 3, "{third:?}");
        // nothing for a version without measured entries
        assert!(ensure_pack(&root, "1.8.9", &emit).await.is_empty());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn gc_detection() {
        assert!(user_sets_gc("-XX:+UseZGC"));
        assert!(user_sets_gc("-Xmx4G -XX:+UseG1GC -XX:MaxGCPauseMillis=50"));
        assert!(user_sets_gc("-XX:G1NewSizePercent=30"));
        assert!(!user_sets_gc("-Xmx4G -Dfoo=bar"));
        assert!(!user_sets_gc(""));
    }

    #[test]
    fn early_crash_switches_pack_off_and_new_revision_resets() {
        let root = std::env::temp_dir().join(format!("s1mp1e-perf-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let game = root.join("game");
        std::fs::create_dir_all(&game).unwrap();
        mark_launch(&root, "26.2");
        assert!(judge_exit(&root, "26.2", &game, 1).is_some());
        assert!(load_state(&root, "26.2").disabled);
        // an ok launch after a manual reset counts
        let mut st = load_state(&root, "26.2");
        st.disabled = false;
        save_state(&root, "26.2", &st);
        mark_launch(&root, "26.2");
        assert!(judge_exit(&root, "26.2", &game, 0).is_none());
        assert_eq!(load_state(&root, "26.2").ok_launches, 1);
        // a different revision starts clean
        let mut st = load_state(&root, "26.2");
        st.revision = "something-else".into();
        st.disabled = true;
        save_state(&root, "26.2", &st);
        assert!(!load_state(&root, "26.2").disabled);
        let _ = std::fs::remove_dir_all(&root);
    }
}
