//! L3 — resolve a version (merging Fabric's `inheritsFrom` chain), extract
//! natives, assemble classpath + era-correct arguments, and spawn the JVM with
//! live log streaming. Offline mode (no network / no auth) for now.

use crate::meta::*;
use crate::paths::*;
use anyhow::{anyhow, Context, Result};
use std::collections::BTreeMap;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Command, Stdio};

/// A version JSON collapsed across its inheritance chain.
struct Merged {
    id: String,
    base_id: String, // the vanilla root that owns the client jar + assets
    main_class: String,
    libraries: Vec<Library>,
    asset_index_id: String,
    java_component: Option<String>,
    java_major: u32,
    // exactly one of these describes the game args
    modern_jvm: Vec<String>,
    modern_game: Vec<String>,
    legacy_args: Option<String>,
    /// 舊格式（minecraftArguments）的原版之上，子層 profile 又用新格式加的參數（Ornithe 1.8.9）。
    extra_jvm: Vec<String>,
    extra_game: Vec<String>,
}

fn read_version_json(root: &PathBuf, id: &str) -> Result<VersionJson> {
    let p = version_json(root, id);
    let text = std::fs::read_to_string(&p)
        .with_context(|| format!("version json missing: {}", p.display()))?;
    Ok(serde_json::from_str(&text)?)
}

/// Flatten Conditional/Plain arg entries whose rules pass for this OS.
fn collect_args(entries: &[ArgEntry], out: &mut Vec<String>) {
    for e in entries {
        match e {
            ArgEntry::Plain(s) => out.push(s.clone()),
            ArgEntry::Conditional { rules, value } => {
                if Rule::allows(rules) {
                    match value {
                        StringOrList::One(s) => out.push(s.clone()),
                        StringOrList::Many(v) => out.extend(v.iter().cloned()),
                    }
                }
            }
        }
    }
}

fn resolve(root: &PathBuf, id: &str) -> Result<Merged> {
    // walk the inheritsFrom chain, child-first
    let mut chain: Vec<VersionJson> = Vec::new();
    let mut cur = id.to_string();
    loop {
        let vj = read_version_json(root, &cur)?;
        let parent = vj.inherits_from.clone();
        chain.push(vj);
        match parent {
            Some(p) => cur = p,
            None => break,
        }
    }
    // base = last (vanilla root); child = first
    let base = chain.last().unwrap();
    let child = chain.first().unwrap();
    let base_id = base.id.clone();

    let main_class = chain.iter().find_map(|v| v.main_class.clone())
        .ok_or_else(|| anyhow!("no mainClass in chain"))?;
    let asset_index_id = base.asset_index.as_ref().map(|a| a.id.clone())
        .or_else(|| base.assets.clone())
        .unwrap_or_else(|| "legacy".into());
    // 子層優先：載入器 profile 可以要求比原版更新的 Java（Ornithe 1.8.9 → Java 25）
    let jv = chain.iter().find_map(|v| v.java_version.as_ref());
    let java_component = jv.map(|j| j.component.clone());
    let java_major = jv.map(|j| j.major_version).unwrap_or(8);

    // libraries: child chain first, dedupe keeping the first of each. The key MUST
    // include the classifier: modern (1.19+) natives ship as a sibling artifact
    // `group:artifact:version:natives-windows` alongside the plain
    // `group:artifact:version`. Keying on group:artifact alone collides them, so the
    // natives entry gets dropped, `extract_natives` never sees lwjgl.dll, and the
    // game dies with "Failed to locate library: lwjgl.dll". Key on
    // group:artifact[:classifier] (version excluded so child overrides parent).
    let mut libraries: Vec<Library> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    // 整條鏈宣告要排除的函式庫前綴（s1mp1eExclude），以及「子層提供了同一個 group:artifact
    // 就丟掉父層任何版本」——Ornithe 的 gen2 函式庫升級（log4j 2.19、gson 2.10…）要真的取代原版舊版，
    // 不能兩版並存在 classpath 上。
    let exclude: Vec<String> = chain.iter().flat_map(|v| v.s1mp1e_exclude.iter().cloned()).collect();
    let ga = |name: &str| -> String { name.split(':').take(2).collect::<Vec<_>>().join(":") };
    let mut child_ga: std::collections::HashSet<String> = std::collections::HashSet::new();
    for (depth, vj) in chain.iter().enumerate() {
        let provided_by_child = child_ga.clone();
        for lib in &vj.libraries {
            if exclude.iter().any(|e| lib.name.starts_with(e.as_str())) { continue; }
            // 只對 S1mp1e 自己產生的 profile（帶 s1mp1eExclude，目前是 Ornithe）做取代；
            // Forge 等其他 profile 維持原本的 classpath（子層本來就排前面，行為不變也不冒險）
            if depth > 0 && !exclude.is_empty() && lib.natives.is_none() && provided_by_child.contains(&ga(&lib.name)) { continue; }
            let parts: Vec<&str> = lib.name.split(':').collect();
            // Dedupe key: group:artifact[:classifier] + (natives-marker if legacy natives)
            // + version. Version MUST be in the key because 1.13-1.18 list the SAME
            // legacy-natives entry twice with different versions and different OS rules
            // (e.g. org.lwjgl:lwjgl:3.2.1 osx-only + org.lwjgl:lwjgl:3.2.2 non-osx). If
            // we drop by group:artifact the wrong-OS one wins on Windows, extract_natives
            // finds nothing, and MC silent-exits right after "Setting user".
            let mut key = match parts.as_slice() {
                [g, a, v, c, ..] => format!("{g}:{a}:{c}:{v}"),
                [g, a, v, ..]    => format!("{g}:{a}:{v}"),
                [g, a, ..]       => format!("{g}:{a}"),
                _ => lib.name.clone(),
            };
            if lib.natives.is_some() { key.push_str("!natives"); }
            if seen.insert(key) {
                libraries.push(lib.clone());
            }
        }
        for lib in &vj.libraries {
            if lib.natives.is_none() { child_ga.insert(ga(&lib.name)); }
        }
    }

    // arguments — modern (any chain member has `arguments`) vs legacy string
    let mut modern_jvm = Vec::new();
    let mut modern_game = Vec::new();
    let mut has_modern = false;
    for vj in chain.iter().rev() {
        // base first so parent args precede child (Fabric appends)
        if let Some(a) = &vj.arguments {
            has_modern = true;
            collect_args(&a.jvm, &mut modern_jvm);
            collect_args(&a.game, &mut modern_game);
        }
    }
    // 原版是舊格式（1.12.2 以前，只有 minecraftArguments）但子層用新格式加參數（Ornithe 的
    // profile 只帶幾個 -D）：照舊格式組命令列，子層的 jvm/game 參數當額外參數附上。
    // 以前只要任何一層有 `arguments` 就整個改走新格式，舊參數和 -cp 會一起不見。
    let mut extra_jvm = Vec::new();
    let mut extra_game = Vec::new();
    if base.arguments.is_none() && base.minecraft_arguments.is_some() && has_modern {
        has_modern = false;
        extra_jvm = std::mem::take(&mut modern_jvm);
        extra_game = std::mem::take(&mut modern_game);
    }
    let legacy_args = if has_modern { None } else {
        chain.iter().find_map(|v| v.minecraft_arguments.clone())
    };

    Ok(Merged {
        id: child.id.clone(), base_id, main_class, libraries,
        asset_index_id, java_component, java_major, modern_jvm, modern_game, legacy_args,
        extra_jvm, extra_game,
    })
}

/// Absolute path of a library's jar in the libraries store.
fn lib_jar_path(root: &PathBuf, lib: &Library) -> Option<PathBuf> {
    if let Some(dl) = &lib.downloads {
        if let Some(a) = &dl.artifact {
            let rel = a.path.clone().unwrap_or_else(|| maven_to_path(&lib.name));
            return Some(libraries_dir(root).join(rel));
        }
    }
    Some(libraries_dir(root).join(maven_to_path(&lib.name)))
}

/// Extract every natives jar in the chain into `versions/<id>/natives/`.
fn extract_natives(root: &PathBuf, merged: &Merged) -> Result<PathBuf> {
    let nat_dir = version_dir(root, &merged.id).join("natives");
    std::fs::create_dir_all(&nat_dir)?;
    for lib in &merged.libraries {
        if !Rule::allows(&lib.rules) { continue; }
        // legacy: natives map + classifier; modern: artifact path contains "natives-windows"
        let mut jars: Vec<PathBuf> = Vec::new();
        if let Some(dl) = &lib.downloads {
            if let (Some(nat), Some(cls)) = (&lib.natives, dl.classifiers.as_ref()) {
                if let Some(key) = nat.get("windows") {
                    if let Some(a) = cls.get(key) {
                        let rel = a.path.clone().unwrap_or_else(|| maven_to_path(&lib.name));
                        jars.push(libraries_dir(root).join(rel));
                    }
                }
            }
            if let Some(a) = &dl.artifact {
                let rel = a.path.clone().unwrap_or_else(|| maven_to_path(&lib.name));
                if rel.contains("natives-windows") || lib.name.contains("natives-windows") {
                    jars.push(libraries_dir(root).join(rel));
                }
            }
        }
        for jar in jars {
            if let Ok(f) = std::fs::File::open(&jar) {
                if let Ok(mut zip) = zip::ZipArchive::new(f) {
                    for i in 0..zip.len() {
                        let mut ze = zip.by_index(i)?;
                        let name = ze.name().to_string();
                        if name.starts_with("META-INF/") || ze.is_dir() { continue; }
                        if name.ends_with(".dll") || name.ends_with(".so") || name.ends_with(".dylib") {
                            let out = nat_dir.join(PathBuf::from(&name).file_name().unwrap());
                            let mut of = std::fs::File::create(&out)?;
                            std::io::copy(&mut ze, &mut of)?;
                        }
                    }
                }
            }
        }
    }
    Ok(nat_dir)
}

fn offline_uuid(name: &str) -> String {
    // vanilla offline uuid = md5("OfflinePlayer:<name>") with version/variant bits set
    let mut ctx = md5_like(format!("OfflinePlayer:{name}").as_bytes());
    ctx[6] = (ctx[6] & 0x0f) | 0x30; // version 3
    ctx[8] = (ctx[8] & 0x3f) | 0x80; // variant
    let h: String = ctx.iter().map(|b| format!("{b:02x}")).collect();
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

/// Minimal MD5 (offline uuid only). Public-domain style reference impl.
fn md5_like(input: &[u8]) -> [u8; 16] {
    // RFC 1321
    let s: [u32; 64] = [
        7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,
        5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
        4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,
        6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21];
    let k: [u32; 64] = [
        0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,
        0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
        0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
        0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
        0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,
        0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
        0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,
        0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391];
    let mut msg = input.to_vec();
    let bitlen = (input.len() as u64).wrapping_mul(8);
    msg.push(0x80);
    while msg.len() % 64 != 56 { msg.push(0); }
    msg.extend_from_slice(&bitlen.to_le_bytes());
    let (mut a0, mut b0, mut c0, mut d0) = (0x67452301u32,0xefcdab89u32,0x98badcfeu32,0x10325476u32);
    for chunk in msg.chunks(64) {
        let mut m = [0u32; 16];
        for i in 0..16 { m[i] = u32::from_le_bytes([chunk[i*4],chunk[i*4+1],chunk[i*4+2],chunk[i*4+3]]); }
        let (mut a, mut b, mut c, mut d) = (a0,b0,c0,d0);
        for i in 0..64 {
            let (f, g) = match i {
                0..=15 => ((b & c) | (!b & d), i),
                16..=31 => ((d & b) | (!d & c), (5*i+1)%16),
                32..=47 => (b ^ c ^ d, (3*i+5)%16),
                _ => (c ^ (b | !d), (7*i)%16),
            };
            let f = f.wrapping_add(a).wrapping_add(k[i]).wrapping_add(m[g]);
            a = d; d = c; c = b;
            b = b.wrapping_add(f.rotate_left(s[i]));
        }
        a0 = a0.wrapping_add(a); b0 = b0.wrapping_add(b);
        c0 = c0.wrapping_add(c); d0 = d0.wrapping_add(d);
    }
    let mut out = [0u8; 16];
    out[0..4].copy_from_slice(&a0.to_le_bytes());
    out[4..8].copy_from_slice(&b0.to_le_bytes());
    out[8..12].copy_from_slice(&c0.to_le_bytes());
    out[12..16].copy_from_slice(&d0.to_le_bytes());
    out
}

/// Resolved player identity for the launch: either an online MSA account or an
/// offline pseudo-account. Build the offline variant with `AuthInfo::offline`.
pub struct AuthInfo {
    pub name: String,
    pub uuid: String,
    pub token: String,
    pub user_type: String, // "msa" online, "legacy" offline
}

impl AuthInfo {
    pub fn offline(name: &str) -> Self {
        AuthInfo {
            name: name.to_string(),
            uuid: offline_uuid(name),
            token: "0".into(),
            user_type: "legacy".into(),
        }
    }
}

/// Pick the glass mod jar for this MC version from `.minecraft/s1mp1e-mods/`.
/// EXACT match only: glass mixins are version-specific (a 1.21.1 build's
/// HandledScreen redirect fails injection on 1.21.8 and crashes the game), so we
/// never substitute a same-family build. Versions without their own `glass-<mc>.jar`
/// simply launch without glass rather than crash-loading an incompatible one.
fn pick_glass_jar(root: &PathBuf, mc: &str) -> Option<PathBuf> {
    let exact = root.join("s1mp1e-mods").join(format!("glass-{mc}.jar"));
    if exact.exists() { Some(exact) } else { None }
}

/// The player's enabled mods for this version: every `*.jar` (any case) in `s1mp1e-mods/<mc>/`, the one folder the
/// launcher installs into, updates, disables and shows. The shared root `s1mp1e-mods/` holds only the launcher's glass
/// jars; it is never loaded as a mod folder (it used to be a fallback when the version folder was empty, which made a
/// stale set from another version load the moment the version folder was emptied or first used).
fn pick_user_mods(root: &PathBuf, mc: &str) -> Vec<PathBuf> {
    let mut out = Vec::new();
    if let Ok(rd) = std::fs::read_dir(root.join("s1mp1e-mods").join(mc)) {
        for e in rd.flatten() {
            let p = e.path();
            let lower = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_ascii_lowercase();
            if lower.ends_with(".jar") && p.is_file() {
                out.push(p);
            }
        }
    }
    out.sort();
    out
}

/// Keep one jar per Fabric mod id — the highest `version`, or the newer file when versions tie or are unreadable.
/// Fabric aborts on a duplicate id, so two copies (an update that could not move the old jar aside, a hand-copied
/// second version) must not both reach the loader. Jars without a `fabric.mod.json` pass through unchanged. Each
/// skipped copy is reported on stdout as `MOD_DUPLICATE\t<id>\t<kept file>\t<skipped file>` for the launcher UI.
fn dedupe_by_mod_id(jars: Vec<PathBuf>) -> Vec<PathBuf> {
    use std::cmp::Ordering;
    let mtime = |p: &PathBuf| std::fs::metadata(p).and_then(|m| m.modified()).ok();
    let mut by_id: BTreeMap<String, PathBuf> = BTreeMap::new();
    let mut out: Vec<PathBuf> = Vec::new();
    for j in jars {
        let Some(id) = crate::default_mods::fabric_mod_id(&j) else { out.push(j); continue };
        match by_id.get(&id) {
            None => { by_id.insert(id, j); }
            Some(cur) => {
                let (va, vb) = (crate::default_mods::fabric_mod_version(&j), crate::default_mods::fabric_mod_version(cur));
                let newer = match (va.as_deref(), vb.as_deref()) {
                    (Some(a), Some(b)) => match crate::default_mods::compare_versions(a, b) {
                        Ordering::Equal => mtime(&j) > mtime(cur),
                        o => o == Ordering::Greater,
                    },
                    _ => mtime(&j) > mtime(cur),
                };
                let name = |p: &PathBuf| p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                let (kept, skipped) = if newer { (j.clone(), cur.clone()) } else { (cur.clone(), j.clone()) };
                println!("MOD_DUPLICATE\t{id}\t{}\t{}", name(&kept), name(&skipped));
                by_id.insert(id, kept);
            }
        }
    }
    out.extend(by_id.into_values());
    out.sort();
    out
}

/// Make `mods_dir` hold exactly `wanted` (file name in the folder → source jar). The folder is derived and owned by the
/// launcher, so anything else is removed. Sources are COPIED, never referenced in place: the running game keeps every
/// loaded jar open, and if those were the player's files in `s1mp1e-mods/<mc>/`, updating, disabling or deleting a mod
/// in the launcher would fail until the game closed. A copy whose size and modification time already match its source
/// is kept, so an unchanged mod set costs no copying. Any file that cannot be removed or replaced (a game of this
/// version is still running from this folder) fails the launch with a message naming it, instead of starting with a
/// half-updated or doubled mod set.
fn sync_mods_dir(mods_dir: &std::path::Path, wanted: &BTreeMap<String, PathBuf>) -> Result<()> {
    std::fs::create_dir_all(mods_dir).with_context(|| format!("建立 {}", mods_dir.display()))?;
    let mut blocked: Vec<String> = Vec::new();
    let lower_wanted: std::collections::BTreeSet<String> = wanted.keys().map(|k| k.to_ascii_lowercase()).collect();
    if let Ok(rd) = std::fs::read_dir(mods_dir) {
        for e in rd.flatten() {
            let p = e.path();
            let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("").to_string();
            let lower = name.to_ascii_lowercase();
            if !(lower.ends_with(".jar") || lower.ends_with(".s1copy")) || lower_wanted.contains(&lower) {
                continue;
            }
            if std::fs::remove_file(&p).is_err() {
                blocked.push(name);
            }
        }
    }
    let same = |a: &std::path::Path, b: &std::path::Path| -> bool {
        match (std::fs::metadata(a), std::fs::metadata(b)) {
            (Ok(x), Ok(y)) => x.len() == y.len() && x.modified().ok() == y.modified().ok(),
            _ => false,
        }
    };
    for (name, src) in wanted {
        let dest = mods_dir.join(name);
        if same(src, &dest) {
            continue;
        }
        let tmp = mods_dir.join(format!("{name}.s1copy"));
        let copied = std::fs::copy(src, &tmp).and_then(|_| {
            // carry the source's modification time so the next launch recognises the copy as unchanged
            let modified = std::fs::metadata(src)?.modified()?;
            std::fs::OpenOptions::new().write(true).open(&tmp)?.set_modified(modified)?;
            std::fs::rename(&tmp, &dest)
        });
        if copied.is_err() {
            let _ = std::fs::remove_file(&tmp);
            blocked.push(name.clone());
        }
    }
    if blocked.is_empty() {
        Ok(())
    } else {
        Err(anyhow!(
            "無法更新 {} 裡的 {}：檔案正在使用中（同一個版本的遊戲是否還開著？）請關閉後再啟動",
            mods_dir.display(),
            blocked.join("、")
        ))
    }
}

/// Peek a mod jar's zip directory to tell which loader it targets, so a jar that
/// ended up in the wrong per-MC folder is not fed to the wrong loader. A per-MC
/// folder can mix loaders — e.g. a Forge build of Xaero's for 1.14.4 sitting next
/// to Fabric mods — and Fabric `addMods` choking on a Forge jar crashed the game.
/// Markers: Fabric → `fabric.mod.json`; Forge → `META-INF/mods.toml` or the legacy
/// `mcmod.info` (1.8.9/1.12.2); NeoForge → `META-INF/neoforge.mods.toml`. A jar with
/// no recognizable marker (a plain library) is accepted for either loader — we only
/// REJECT a jar that clearly belongs to the OTHER loader. Unreadable → accept (the
/// resolve/load step stays the final arbiter, exactly as before).
fn jar_matches_loader(path: &PathBuf, want_fabric: bool) -> bool {
    let Ok(f) = std::fs::File::open(path) else { return true; };
    let Ok(mut zip) = zip::ZipArchive::new(f) else { return true; };
    let mut has_fabric = false;
    let mut has_forge = false;
    for i in 0..zip.len() {
        if let Ok(e) = zip.by_index(i) {
            match e.name() {
                "fabric.mod.json" => has_fabric = true,
                "META-INF/mods.toml" | "mcmod.info" | "META-INF/neoforge.mods.toml" => {
                    has_forge = true;
                }
                _ => {}
            }
        }
    }
    if want_fabric {
        !(has_forge && !has_fabric)
    } else {
        !(has_fabric && !has_forge)
    }
}

/// Build the full java command line for `id`.
pub struct LaunchPlan {
    pub java_exe: PathBuf,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    /// 主類別名稱（args 裡它之前的是 JVM 參數）
    pub main_class: String,
    /// Settings → 使用高效能顯示卡: mark `java_exe` "high performance" in Windows' per-app graphics preference.
    pub prefer_discrete_gpu: bool,
    /// Settings → 遊戲優先順序: "normal" | "above_normal" | "high" (anything else = normal).
    pub priority: String,
}

/// Per-version instance directory. Each MC version launches with its OWN gameDir
/// (`.minecraft/instances/<mc>/`) so its `mods/` folder is isolated — a 1.21.1 mod
/// can no longer cross-load onto 26.2 (or vice-versa) and crash. Worlds, resource
/// packs and settings are SHARED with the main `.minecraft` via directory junctions,
/// so switching versions doesn't hide the user's saves.
fn instance_dir(root: &PathBuf, mc: &str) -> PathBuf {
    root.join("instances").join(mc)
}

fn setup_instance(root: &PathBuf, mc: &str) -> PathBuf {
    let inst = instance_dir(root, mc);
    let _ = std::fs::create_dir_all(inst.join("mods"));
    // Junction the shared game data to the main .minecraft (created once; junctions
    // need no admin rights, unlike symlinks).
    #[cfg(target_os = "windows")]
    for shared in ["saves", "resourcepacks", "shaderpacks"] {
        let link = inst.join(shared);
        let target = root.join(shared);
        let _ = std::fs::create_dir_all(&target);
        if !link.exists() {
            let _ = std::process::Command::new("cmd")
                .args([
                    "/c",
                    "mklink",
                    "/J",
                    &link.to_string_lossy(),
                    &target.to_string_lossy(),
                ])
                .output();
        }
    }
    // First launch of this version: start from the settings of the version played most recently (key bindings,
    // sensitivity, volume, language...) instead of Minecraft's defaults.
    // The UI turns the OPTIONS_SEEDED line into a launch note ("" = the main .minecraft/options.txt).
    if let Some(from) = crate::options_sync::seed_new_instance(root, mc) {
        println!("OPTIONS_SEEDED\t{mc}\t{from}");
    }
    inst
}

/// 26.x lets the player pick a graphics backend (`preferredGraphicsBackend` in options.txt); the liquid-glass shaders
/// assume OpenGL's bottom-left framebuffer origin, so on Vulkan the mod drops to flat grey panels. Pin the instance to
/// OpenGL: replace the line's value, or append the line when absent; every other line (and the file's line endings)
/// is kept, and nothing is written when it is already OpenGL. Never fatal.
fn force_opengl_backend(inst: &std::path::Path) {
    let path = inst.join("options.txt");
    let old = std::fs::read_to_string(&path).unwrap_or_default();
    if let Some(new) = with_opengl_backend(&old) {
        let tmp = path.with_extension("txt.s1tmp");
        if std::fs::write(&tmp, new).is_ok() && std::fs::rename(&tmp, &path).is_err() {
            let _ = std::fs::remove_file(&tmp);
        }
    }
}

/// The options text with `preferredGraphicsBackend:"opengl"`, or None when it already says so.
fn with_opengl_backend(old: &str) -> Option<String> {
    const KEY: &str = "preferredGraphicsBackend:";
    const WANT: &str = "preferredGraphicsBackend:\"opengl\"";
    let nl = if old.contains("\r\n") { "\r\n" } else { "\n" };
    let mut found = false;
    let mut changed = false;
    let mut lines: Vec<String> = Vec::new();
    for line in old.lines() {
        if line.starts_with(KEY) {
            found = true;
            if line.trim_end() != WANT {
                changed = true;
                lines.push(WANT.to_string());
                continue;
            }
        }
        lines.push(line.to_string());
    }
    if !found {
        lines.push(WANT.to_string());
        changed = true;
    }
    if !changed {
        return None;
    }
    let mut out = lines.join(nl);
    out.push_str(nl);
    Some(out)
}

#[cfg(test)]
mod opengl_backend_tests {
    use super::with_opengl_backend;

    #[test]
    fn replaces_vulkan_keeps_rest() {
        let old = "version:4671\r\npreferredGraphicsBackend:\"vulkan\"\r\nfov:0.0\r\n";
        assert_eq!(with_opengl_backend(old).unwrap(),
                   "version:4671\r\npreferredGraphicsBackend:\"opengl\"\r\nfov:0.0\r\n");
    }

    #[test]
    fn appends_when_missing_and_noop_when_set() {
        assert_eq!(with_opengl_backend("fov:0.0\n").unwrap(), "fov:0.0\npreferredGraphicsBackend:\"opengl\"\n");
        assert_eq!(with_opengl_backend("").unwrap(), "preferredGraphicsBackend:\"opengl\"\n");
        assert!(with_opengl_backend("a:1\npreferredGraphicsBackend:\"opengl\"\n").is_none());
    }
}

/// Major version of a Java install, from the `release` file next to its `bin/` (`JAVA_VERSION="1.8.0_51"` → 8,
/// `"21.0.7"` → 21). None when it cannot be read.
fn java_major_of(java_exe: &std::path::Path) -> Option<u32> {
    let home = java_exe.parent()?.parent()?;
    let text = std::fs::read_to_string(home.join("release")).ok()?;
    let line = text.lines().find(|l| l.starts_with("JAVA_VERSION="))?;
    let v = line.trim_start_matches("JAVA_VERSION=").trim_matches('"');
    let mut parts = v.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 { parts.next()?.parse().ok() } else { Some(first) }
}

/// Whether the player's custom Java can run a version that needs Java `required`. Prints `JAVA_OVERRIDE_SKIPPED\t…`
/// for the launcher UI when it is not used.
fn custom_java_fits(java_exe: &std::path::Path, required: u32) -> bool {
    if !java_exe.exists() {
        println!("JAVA_OVERRIDE_SKIPPED\t{}\tmissing\t{required}", java_exe.display());
        return false;
    }
    match java_major_of(java_exe) {
        Some(m) if m >= required => true,
        Some(m) => {
            println!("JAVA_OVERRIDE_SKIPPED\t{}\t{m}\t{required}", java_exe.display());
            false
        }
        None => true, // a custom JDK without a release file: trust the player's choice
    }
}

/// Split a JVM-arguments string like a shell would for plain tokens, but keep "quoted parts" together, so
/// `-Dpath="C:\My Folder\x"` stays one argument instead of breaking at the space.
pub(crate) fn split_jvm_args(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let (mut in_quotes, mut has) = (false, false);
    for ch in s.chars() {
        match ch {
            '"' => { in_quotes = !in_quotes; has = true; }
            c if c.is_whitespace() && !in_quotes => {
                if has { out.push(std::mem::take(&mut cur)); has = false; }
            }
            c => { cur.push(c); has = true; }
        }
    }
    if has { out.push(cur); }
    out
}

/// The heap to give the game: the saved setting, kept within [1 GB, physical RAM − 1 GB]. A config written on a
/// bigger PC (or edited by hand) asking for more than this machine has makes the JVM refuse to start or the system
/// swap to a crawl; 0 or a tiny value cannot run the game at all.
pub(crate) fn effective_ram_mb(saved: u32, physical_mb: Option<u64>) -> u32 {
    let mut ram = saved.max(1024);
    if let Some(phys) = physical_mb {
        let ceiling = phys.saturating_sub(1024).max(1024).min(u32::MAX as u64) as u32;
        ram = ram.min(ceiling);
    }
    ram
}

/// Total physical memory in MB (None when it cannot be read).
fn physical_ram_mb() -> Option<u64> {
    #[cfg(windows)]
    {
        #[repr(C)]
        struct MemoryStatusEx {
            length: u32,
            memory_load: u32,
            total_phys: u64,
            avail_phys: u64,
            total_page_file: u64,
            avail_page_file: u64,
            total_virtual: u64,
            avail_virtual: u64,
            avail_extended_virtual: u64,
        }
        #[link(name = "kernel32")]
        extern "system" {
            fn GlobalMemoryStatusEx(buffer: *mut MemoryStatusEx) -> i32;
        }
        let mut m = MemoryStatusEx {
            length: std::mem::size_of::<MemoryStatusEx>() as u32,
            memory_load: 0, total_phys: 0, avail_phys: 0, total_page_file: 0, avail_page_file: 0,
            total_virtual: 0, avail_virtual: 0, avail_extended_virtual: 0,
        };
        // SAFETY: `m` is a correctly sized, initialised MEMORYSTATUSEX with its length field set, as the API requires.
        let ok = unsafe { GlobalMemoryStatusEx(&mut m) } != 0;
        if ok && m.total_phys > 0 { Some(m.total_phys / (1024 * 1024)) } else { None }
    }
    #[cfg(not(windows))]
    {
        None
    }
}

pub fn plan_launch(root: &PathBuf, id: &str, auth: &AuthInfo, settings: &crate::config::Settings) -> Result<LaunchPlan> {
    let ram_mb = settings.ram_mb;
    let glass = settings.glass;
    let merged = resolve(root, id)?;
    let natives = extract_natives(root, &merged)?;
    // Per-version gameDir for mod isolation (assets/libraries/natives stay shared under root).
    let gamedir = setup_instance(root, &merged.base_id);
    // 26.x: liquid glass refracts only on the OpenGL backend (Vulkan falls back to flat grey panels)
    if glass && merged.base_id.starts_with("26.") {
        force_opengl_backend(&gamedir);
    }

    // classpath: all allowed library jars + the base client jar
    let mut cp: Vec<String> = Vec::new();
    for lib in &merged.libraries {
        if !Rule::allows(&lib.rules) { continue; }
        // skip pure-natives (legacy) libs from classpath
        if lib.natives.is_some() && lib.downloads.as_ref().map(|d| d.artifact.is_none()).unwrap_or(true) {
            continue;
        }
        if let Some(p) = lib_jar_path(root, lib) {
            if p.exists() { cp.push(p.to_string_lossy().into_owned()); }
        }
    }
    cp.push(version_jar(root, &merged.base_id).to_string_lossy().into_owned());
    let classpath = cp.join(";");

    // java exe from the auto-managed runtime, unless the user set S1MP1E_JAVA to
    // override — Mojang's Microsoft OpenJDK 21.0.7 has a known WEPollSelectorImpl
    // AF_UNIX bug that crashes MC 1.21+ on world load ("failed to create a child
    // event loop"). Temurin/Zulu 21 don't have it. The override lets the user
    // point at a working JDK without patching the launcher.
    // Ornithe 1.8.9 只能跑在 Java 25（Pylon 的 FFM、Forge 相容層、Argentum）：一律用啟動器管理的
    // java-runtime-epsilon，不套自訂 Java 路徑（玩家可能為了舊 Forge 1.8.9 設成 Java 8）。
    let java_exe = if crate::ornithe::is_ornithe_id(&merged.id) {
        runtime_dir(root, merged.java_component.as_deref().unwrap_or("java-runtime-epsilon")).join("bin").join("javaw.exe")
    } else if !settings.java_path.trim().is_empty()
        && custom_java_fits(std::path::Path::new(settings.java_path.trim()), merged.java_major)
    {
        // User-picked Java (Settings → 自訂 Java 路徑). Overrides the auto-selected runtime — but only when it is new
        // enough for this version: one custom Java 8 picked for Forge 1.12.2 must not make 26.x die with
        // UnsupportedClassVersionError. Too old (or unreadable) → the managed runtime, with a note for the UI.
        PathBuf::from(settings.java_path.trim())
    } else if let Ok(p) = std::env::var("S1MP1E_JAVA") {
        PathBuf::from(p)
    } else {
        match &merged.java_component {
            Some(c) => runtime_dir(root, c).join("bin").join("javaw.exe"),
            None => runtime_dir(root, "jre-legacy").join("bin").join("javaw.exe"),
        }
    };

    // placeholder table
    let mut vars: BTreeMap<&str, String> = BTreeMap::new();
    vars.insert("auth_player_name", auth.name.clone());
    vars.insert("version_name", merged.id.clone());
    vars.insert("game_directory", gamedir.to_string_lossy().into_owned());
    vars.insert("assets_root", assets_dir(root).to_string_lossy().into_owned());
    vars.insert("assets_index_name", merged.asset_index_id.clone());
    vars.insert("auth_uuid", auth.uuid.clone());
    vars.insert("auth_access_token", auth.token.clone());
    vars.insert("clientid", String::new());
    vars.insert("auth_xuid", String::new());
    vars.insert("user_type", auth.user_type.clone());
    vars.insert("version_type", "release".into());
    vars.insert("user_properties", "{}".into());
    vars.insert("natives_directory", natives.to_string_lossy().into_owned());
    vars.insert("launcher_name", "S1mp1e".into());
    vars.insert("launcher_version", env!("CARGO_PKG_VERSION").into());
    vars.insert("classpath", classpath.clone());
    vars.insert("library_directory", libraries_dir(root).to_string_lossy().into_owned());
    vars.insert("classpath_separator", ";".into());
    vars.insert("game_assets", assets_dir(root).join("virtual").join("legacy").to_string_lossy().into_owned());
    vars.insert("auth_session", "0".into());

    let subst = |s: &str| -> String {
        let mut out = s.to_string();
        for (k, v) in &vars {
            out = out.replace(&format!("${{{k}}}"), v);
        }
        out
    };

    let mut args: Vec<String> = Vec::new();
    let user_jvm = split_jvm_args(&settings.jvm_args);
    let ram_mb = effective_ram_mb(ram_mb, physical_ram_mb());
    // The player's own -Xmx / -Xms (JVM 參數) replace ours instead of fighting them: two -Xmx is confusing, and our
    // -Xms above their smaller -Xmx stops the JVM with "initial heap size larger than maximum".
    let user_xmx = user_jvm.iter().any(|a| a.starts_with("-Xmx"));
    let user_xms = user_jvm.iter().any(|a| a.starts_with("-Xms"));
    if !user_xmx {
        args.push(format!("-Xmx{ram_mb}M"));
    }
    if !user_xms && !user_xmx {
        args.push(format!("-Xms{}M", (ram_mb / 2).max(512)));
    }
    // User-supplied JVM args (Settings → JVM 參數): GC flags, -Dprops, etc. Whitespace
    // split, injected before the version's own jvm args and the main class.
    args.extend(user_jvm);
    // Performance pack JVM flags (perf.rs): only measured ones, and never on top of a collector
    // the player picked themselves. A custom Java path keeps its own defaults too.
    if settings.perf_pack && settings.java_path.trim().is_empty() && !crate::perf::user_sets_gc(&settings.jvm_args)
        && !crate::perf::load_state(root, &merged.base_id).disabled
    {
        for f in crate::perf::jvm_flags(merged.java_major, ram_mb) {
            args.push(f);
        }
    }
    // Short java.io.tmpdir — safety net for AF_UNIX socket path length (108-byte
    // limit) even though on some Windows builds AF_UNIX loopback fails regardless.
    #[cfg(target_os = "windows")]
    {
        let short_tmp = r"C:\Temp\s1mp1e-jvm";
        let _ = std::fs::create_dir_all(short_tmp);
        args.push(format!("-Djava.io.tmpdir={short_tmp}"));
    }

    // Liquid-glass client mod injection. When launching a Fabric profile and a glass
    // jar exists for this MC version, load it through `fabric.addMods` — this keeps it
    // OUT of the shared `.minecraft/mods` so it never cross-loads onto a version whose
    // mappings it wasn't built for. Store: `.minecraft/s1mp1e-mods/glass-<baseMc>.jar`.
    let is_fabric = merged.main_class.contains("KnotClient")
        || merged.main_class.contains("fabric");

    // The glass jar goes into the per-version instance's `mods/` folder (NATIVE load)
    // for BOTH Fabric and Forge; the instance is per-version so this never cross-loads
    // onto a version it wasn't built for. Forge coremods (1.8.9/1.12.2) also load from
    // <gameDir>/mods.
    //
    // NOTE (root cause of the old "1.21.1 no glass" bug): the glass shaders live in
    // `assets/minecraft/shaders/core/` and are registered into the ResourceManager by
    // Fabric API's `fabric-resource-loader-v0` — plain fabric-loader does NOT turn a
    // mod's assets into a resource pack on its own. So glass HARD-DEPENDS on Fabric API
    // being present in the launched mod set. Every version's `s1mp1e-mods/<mc>/` folder
    // ships a matching fabric-api-*.jar (loaded below via addMods); when 1.21.1's folder
    // was empty the reload came up "vanilla" only and every shader was "File not found".
    // If a Fabric glass version ever renders no glass again, check fabric-api is present.
    // Glass toggle: only inject the glass client mod when the user has it enabled
    // (Settings.glass). Off → launch a plain profile with no glass jar.
    // Reconcile the per-version instance mods/ folder BEFORE laying anything down. It
    // is fully derived and owned by S1mp1e (users manage their mods in s1mp1e-mods/<mc>/,
    // which the UI watches), so rebuild it from scratch each launch: wipe every jar,
    // then add exactly the desired set below. Fixes two allocation bugs:
    //   (a) toggling glass OFF still loaded a glass jar copied on a previous glass-ON
    //       launch — the `if glass` block only SKIPS, it never removed the old copy;
    //   (b) Forge user mods deleted/updated in s1mp1e-mods/ lingered here as stale
    //       duplicates that double-loaded alongside the new version.
    let mods_dir = gamedir.join("mods");
    // The desired contents of the instance mods/ folder: file name there → source jar.
    let mut wanted: BTreeMap<String, PathBuf> = BTreeMap::new();

    // Ornithe 1.8.9：玻璃用 Ornithe 版（glass-1.8.9-ornithe.jar），絕不能放 Forge 版的 glass-1.8.9.jar
    // （Forge 相容層會把它當 Forge coremod 載入）；另外放 Forge 相容層與 Pylon/OSL/Argentum。
    let ornithe = crate::ornithe::is_ornithe_id(&merged.id);
    if ornithe {
        if glass {
            if let Some(j) = pick_glass_jar(root, crate::ornithe::GLASS_KEY) {
                wanted.insert("glass-1.8.9-ornithe.jar".into(), j);
            }
        }
        if let Some(j) = pick_glass_jar(root, crate::ornithe::FORGECOMPAT_KEY) {
            wanted.insert("s1mp1e-forgecompat-1.8.9.jar".into(), j);
        }
        for j in crate::ornithe::stack_jars(root) {
            if let Some(name) = j.file_name() {
                wanted.insert(name.to_string_lossy().into_owned(), j.clone());
            }
        }
    } else if glass {
        if let Some(glass_jar) = pick_glass_jar(root, &merged.base_id) {
            wanted.insert(format!("glass-{}.jar", merged.base_id), glass_jar);
        }
    }
    // User's downloaded mods (s1mp1e-mods/<mc>/), filtered to jars that actually target
    // the loader we're launching — see jar_matches_loader: a per-MC folder can mix
    // loaders and feeding a Forge jar to Fabric crashed the game.
    // Ornithe：Forge 模組由相容層載入，Ornithe 模組由 Fabric Loader 載入，兩種都放 mods/，不篩
    let mut user_mods: Vec<PathBuf> = pick_user_mods(root, &merged.base_id)
        .into_iter()
        .filter(|p| ornithe || jar_matches_loader(p, is_fabric))
        .collect();
    // Performance pack (perf.rs): launcher-owned optimisation mods chosen for this launch by
    // `perf::ensure_pack` (already skips any mod the player installed or disabled themselves).
    if settings.perf_pack && is_fabric && !ornithe {
        user_mods.extend(crate::perf::active_jars(root, &merged.base_id));
    }
    if is_fabric {
        user_mods = dedupe_by_mod_id(user_mods);
    }
    for m in &user_mods {
        if let Some(name) = m.file_name() {
            // a launcher-owned jar (glass, Ornithe stack) keeps its slot if a user file has the same name
            wanted.entry(name.to_string_lossy().into_owned()).or_insert_with(|| m.clone());
        }
    }
    // Every loader reads the instance mods/ folder natively, so all of it — glass, the player's mods, the perf pack —
    // is laid down there as copies (see sync_mods_dir). Fabric no longer gets -Dfabric.addMods pointing into the
    // player's own folder, which made a running game lock those files.
    sync_mods_dir(&mods_dir, &wanted)?;

    if !merged.modern_jvm.is_empty() {
        for a in &merged.modern_jvm { args.push(subst(a)); }
    } else {
        // legacy: no jvm args in json — supply the essentials
        for a in &merged.extra_jvm { args.push(subst(a)); }
        args.push(format!("-Djava.library.path={}", natives.to_string_lossy()));
        args.push("-cp".into());
        args.push(classpath.clone());
    }
    args.push(merged.main_class.clone());

    if let Some(legacy) = &merged.legacy_args {
        for tok in legacy.split_whitespace() { args.push(subst(tok)); }
        for a in &merged.extra_game { args.push(subst(a)); }
    } else {
        for a in &merged.modern_game { args.push(subst(a)); }
    }

    // Window resolution (Settings → 遊戲解析度): MC honours --width/--height on every
    // version. 0 = leave the game's own default.
    if settings.res_width > 0 && settings.res_height > 0 {
        args.push("--width".into());
        args.push(settings.res_width.to_string());
        args.push("--height".into());
        args.push(settings.res_height.to_string());
    }

    Ok(LaunchPlan {
        java_exe,
        args,
        cwd: gamedir,
        main_class: merged.main_class.clone(),
        prefer_discrete_gpu: settings.discrete_gpu,
        priority: settings.process_priority.clone(),
    })
}

/// Windows priority class for Settings → 遊戲優先順序, given to the JVM as a creation flag.
#[cfg(target_os = "windows")]
fn priority_class(p: &str) -> u32 {
    match p {
        "high" => 0x0000_0080,         // HIGH_PRIORITY_CLASS
        "above_normal" => 0x0000_8000, // ABOVE_NORMAL_PRIORITY_CLASS
        _ => 0x0000_0020,              // NORMAL_PRIORITY_CLASS
    }
}

/// Windows' per-app graphics preference (Settings → System → Display → Graphics) is the value named after the exe in
/// HKCU\Software\Microsoft\DirectX\UserGpuPreferences, "GpuPreference=2;" meaning high performance. Windows does not
/// see Java as a game, so on a laptop with an integrated and a discrete GPU the game otherwise runs on the integrated
/// one. Written only when the exe has no entry yet — a choice the player made there wins. Returns whether it wrote.
#[cfg(target_os = "windows")]
fn prefer_high_performance_gpu(exe: &std::path::Path) -> Result<bool> {
    use windows::core::HSTRING;
    use windows::Win32::Foundation::ERROR_SUCCESS;
    use windows::Win32::System::Registry::{RegGetValueW, RegSetKeyValueW, HKEY_CURRENT_USER, REG_SZ, RRF_RT_REG_SZ};
    let key = HSTRING::from(r"Software\Microsoft\DirectX\UserGpuPreferences");
    let name = HSTRING::from(exe.as_os_str());
    let mut size: u32 = 0;
    let found = unsafe { RegGetValueW(HKEY_CURRENT_USER, &key, &name, RRF_RT_REG_SZ, None, None, Some(&mut size)) };
    if found == ERROR_SUCCESS {
        return Ok(false);
    }
    let data: Vec<u16> = "GpuPreference=2;".encode_utf16().chain(std::iter::once(0)).collect();
    let r = unsafe {
        RegSetKeyValueW(HKEY_CURRENT_USER, &key, &name, REG_SZ.0, Some(data.as_ptr().cast()), (data.len() * 2) as u32)
    };
    if r != ERROR_SUCCESS {
        return Err(anyhow!("registry write failed ({})", r.0));
    }
    Ok(true)
}

/// Spawn the JVM, streaming stdout+stderr lines via `on_line`, and return the
/// exit code once the process ends (blocks — caller runs it off-thread).
/// Run the game and wait for it. `on_line(line, is_stderr)` receives every output line, tagged by stream, so the caller
/// can keep the JVM's error stream separate (that is where "Unrecognized VM option", UnsupportedClassVersionError and
/// other start-up failures appear).
pub fn run_blocking(plan: LaunchPlan, on_line: impl Fn(String, bool) + Send + Sync + 'static) -> Result<i32> {
    if !plan.java_exe.exists() {
        return Err(anyhow!("java not installed: {}", plan.java_exe.display()));
    }
    // Echo the command line for diagnostics, but REDACT the MSA access token — the
    // value right after `--accessToken` is a live session credential and this line is
    // captured by the launcher's log console. Never print it verbatim.
    {
        let mut shown: Vec<String> = Vec::with_capacity(plan.args.len());
        let mut redact_next = false;
        for a in &plan.args {
            if redact_next {
                shown.push("<redacted>".into());
                redact_next = false;
            } else {
                shown.push(a.clone());
                if a == "--accessToken" {
                    redact_next = true;
                }
            }
        }
        on_line(format!("$ {} {}", plan.java_exe.display(), shown.join(" ")), false);
    }
    let mut cmd = Command::new(&plan.java_exe);
    cmd.args(&plan.args)
        .current_dir(&plan.cwd)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    // JDK 21's WEPollSelectorImpl builds its wakeup pipe over AF_UNIX using
    // the OS %TEMP% env var — NOT `-Djava.io.tmpdir`. On this box AF_UNIX
    // connect() fails at the default deep TEMP path (crash: "failed to create
    // a child event loop" on world load). Redirect TEMP/TMP to a short path
    // for the child JVM only, leaving the rest of the OS alone.
    #[cfg(target_os = "windows")]
    {
        let short_tmp = r"C:\Temp\s1mp1e-jvm";
        let _ = std::fs::create_dir_all(short_tmp);
        cmd.env("TEMP", short_tmp);
        cmd.env("TMP",  short_tmp);
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(priority_class(&plan.priority));
        if plan.prefer_discrete_gpu {
            match prefer_high_performance_gpu(&plan.java_exe) {
                Ok(true) => on_line(format!("[S1mp1e] {}: graphics preference set to high performance", plan.java_exe.display()), false),
                Ok(false) => {}
                Err(e) => on_line(format!("[S1mp1e] graphics preference not set: {e}"), false),
            }
        }
    }
    let mut child = cmd.spawn().context("spawn jvm")?;

    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let on1 = std::sync::Arc::new(on_line);
    let on2 = on1.clone();
    let t1 = std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) { on1(line, false); }
    });
    let t2 = std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) { on2(line, true); }
    });
    let status = child.wait()?;
    let _ = t1.join();
    let _ = t2.join();
    Ok(status.code().unwrap_or(-1))
}

#[cfg(test)]
mod resolve_diff_tests {
    use super::*;

    /// 只讀：比較每個已安裝 profile 在「子層取代父層同 group:artifact／s1mp1eExclude」規則前後的函式庫差異。
    /// `cargo test --release resolve_diff -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn resolve_diff() {
        let root = crate::paths::mc_root(None);
        let Ok(rd) = std::fs::read_dir(versions_dir(&root)) else { return };
        for e in rd.flatten() {
            let id = e.file_name().to_string_lossy().to_string();
            if !version_json(&root, &id).exists() { continue; }
            let Ok(new) = resolve(&root, &id) else { continue };
            // 舊演算法：不排除、不取代
            let mut chain: Vec<VersionJson> = Vec::new();
            let mut cur = id.clone();
            loop {
                let Ok(vj) = read_version_json(&root, &cur) else { break };
                let p = vj.inherits_from.clone();
                chain.push(vj);
                match p { Some(p) => cur = p, None => break }
            }
            let mut old: Vec<String> = Vec::new();
            let mut seen = std::collections::HashSet::new();
            for vj in &chain {
                for lib in &vj.libraries {
                    let parts: Vec<&str> = lib.name.split(':').collect();
                    let mut key = match parts.as_slice() {
                        [g, a, v, c, ..] => format!("{g}:{a}:{c}:{v}"),
                        [g, a, v, ..] => format!("{g}:{a}:{v}"),
                        _ => lib.name.clone(),
                    };
                    if lib.natives.is_some() { key.push_str("!natives"); }
                    if seen.insert(key) { old.push(lib.name.clone()); }
                }
            }
            let newn: std::collections::HashSet<String> = new.libraries.iter().map(|l| l.name.clone()).collect();
            let dropped: Vec<&String> = old.iter().filter(|n| !newn.contains(*n)).collect();
            println!("{id}: {} → {} libs{}", old.len(), new.libraries.len(),
                if dropped.is_empty() { String::new() } else { format!("; dropped {dropped:?}") });
        }
    }
}

#[cfg(test)]
mod mods_dir_tests {
    use super::*;
    use std::io::Write;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("s1mp1e-modsdir-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// A minimal Fabric mod jar with the given id and version.
    fn fabric_jar(path: &std::path::Path, id: &str, version: &str) {
        let f = std::fs::File::create(path).unwrap();
        let mut z = zip::ZipWriter::new(f);
        z.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
        write!(z, r#"{{"schemaVersion":1,"id":"{id}","version":"{version}"}}"#).unwrap();
        z.finish().unwrap();
    }

    #[test]
    fn version_order_reads_like_people_do() {
        use crate::default_mods::compare_versions as c;
        use std::cmp::Ordering::*;
        assert_eq!(c("0.16.10", "0.16.9"), Greater);
        assert_eq!(c("1.0.0-beta.10", "1.0.0-beta.9"), Greater);
        assert_eq!(c("1.0.0", "1.0.0-beta.1"), Greater);
        assert_eq!(c("0.165.0+26.3", "0.164.0+26.3"), Greater);
        assert_eq!(c("1.0", "1.0.0"), Equal);
        assert_eq!(c("mc26.3-0.9.2", "mc26.3-0.9.2"), Equal);
    }

    #[test]
    fn duplicate_ids_keep_the_newest_version() {
        let d = tmp("dedupe");
        let (old, new, other) = (d.join("fabric-api-0.164.jar"), d.join("fabric-api-0.165.jar"), d.join("sodium.jar"));
        fabric_jar(&old, "fabric-api", "0.164.0+26.3");
        fabric_jar(&new, "fabric-api", "0.165.0+26.3");
        fabric_jar(&other, "sodium", "0.9.2");
        let kept = dedupe_by_mod_id(vec![new.clone(), old.clone(), other.clone()]);
        assert!(kept.contains(&new) && kept.contains(&other) && !kept.contains(&old), "{kept:?}");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn sync_copies_only_changes_and_removes_extras() {
        let d = tmp("sync");
        let (src, inst) = (d.join("src"), d.join("instance-mods"));
        std::fs::create_dir_all(&src).unwrap();
        std::fs::create_dir_all(&inst).unwrap();
        let a = src.join("a.jar");
        std::fs::write(&a, b"aaaa").unwrap();
        std::fs::write(inst.join("stale.jar"), b"old").unwrap();
        std::fs::write(inst.join("notes.txt"), b"keep me").unwrap();
        let mut wanted = BTreeMap::new();
        wanted.insert("a.jar".to_string(), a.clone());

        sync_mods_dir(&inst, &wanted).unwrap();
        assert_eq!(std::fs::read(inst.join("a.jar")).unwrap(), b"aaaa");
        assert!(!inst.join("stale.jar").exists(), "a jar that is no longer wanted is removed");
        assert!(inst.join("notes.txt").exists(), "non-jar files are left alone");
        let m1 = std::fs::metadata(inst.join("a.jar")).unwrap().modified().unwrap();
        assert_eq!(m1, std::fs::metadata(&a).unwrap().modified().unwrap(), "copy carries the source mtime");

        // unchanged source: the copy is not rewritten (its mtime stays the source's)
        sync_mods_dir(&inst, &wanted).unwrap();
        assert_eq!(std::fs::metadata(inst.join("a.jar")).unwrap().modified().unwrap(), m1);

        // changed source: replaced
        std::thread::sleep(std::time::Duration::from_millis(20));
        std::fs::write(&a, b"bbbbbb").unwrap();
        sync_mods_dir(&inst, &wanted).unwrap();
        assert_eq!(std::fs::read(inst.join("a.jar")).unwrap(), b"bbbbbb");
        assert!(std::fs::read_dir(&inst).unwrap().flatten()
            .all(|e| !e.file_name().to_string_lossy().ends_with(".s1copy")), "no temp copies left");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn per_version_folder_only_and_any_case() {
        let root = tmp("pick");
        let mods = root.join("s1mp1e-mods");
        std::fs::create_dir_all(mods.join("26.3")).unwrap();
        std::fs::write(mods.join("stale-root.jar"), b"x").unwrap();          // root: never a mod folder
        std::fs::write(mods.join("26.3").join("Upper.JAR"), b"x").unwrap();
        std::fs::write(mods.join("26.3").join("off.jar.disabled"), b"x").unwrap();
        std::fs::write(mods.join("26.3").join("dl.jar.part"), b"x").unwrap();
        let picked = pick_user_mods(&root, "26.3");
        let names: Vec<String> = picked.iter().map(|p| p.file_name().unwrap().to_string_lossy().into_owned()).collect();
        assert_eq!(names, vec!["Upper.JAR".to_string()]);
        assert!(pick_user_mods(&root, "1.20.1").is_empty(), "an empty version folder does not fall back to the root");
        let _ = std::fs::remove_dir_all(&root);
    }
}

#[cfg(test)]
mod launch_arg_tests {
    use super::*;

    #[test]
    fn jvm_args_keep_quoted_parts_together() {
        assert_eq!(split_jvm_args(r#"-Xss2m  -Dpath="C:\My Folder\x" -XX:+UseG1GC"#),
                   vec!["-Xss2m", r"-Dpath=C:\My Folder\x", "-XX:+UseG1GC"]);
        assert!(split_jvm_args("   ").is_empty());
    }

    #[test]
    fn ram_is_clamped_to_this_machine() {
        // more than the PC has → physical − 1 GB
        assert_eq!(effective_ram_mb(65536, Some(16384)), 15360);
        // 0 / tiny → 1 GB floor
        assert_eq!(effective_ram_mb(0, Some(16384)), 1024);
        // a sane value passes through
        assert_eq!(effective_ram_mb(4096, Some(16384)), 4096);
        // tiny machine: never below the 1 GB floor
        assert_eq!(effective_ram_mb(4096, Some(1536)), 1024);
        // unknown physical memory: only the floor applies
        assert_eq!(effective_ram_mb(8192, None), 8192);
    }

    #[test]
    fn java_major_from_release_file() {
        let d = std::env::temp_dir().join(format!("s1mp1e-java-{}", std::process::id()));
        for (ver, want) in [("1.8.0_51", 8u32), ("21.0.7", 21), ("25", 25)] {
            let _ = std::fs::remove_dir_all(&d);
            std::fs::create_dir_all(d.join("bin")).unwrap();
            std::fs::write(d.join("bin").join("javaw.exe"), b"").unwrap();
            std::fs::write(d.join("release"), format!("IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"{ver}\"\n")).unwrap();
            assert_eq!(java_major_of(&d.join("bin").join("javaw.exe")), Some(want), "{ver}");
        }
        assert!(!custom_java_fits(&d.join("bin").join("javaw.exe"), 26), "Java 25 cannot run a version needing 26");
        let _ = std::fs::remove_dir_all(&d);
    }
}

#[cfg(all(test, target_os = "windows"))]
mod gpu_preference_tests {
    use super::prefer_high_performance_gpu;

    /// Writes the per-app graphics preference for a made-up exe path, checks it with `reg query`, then deletes it.
    /// Touches the user's registry, so it only runs on request:
    /// `cargo test --release gpu_preference -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn writes_once_and_respects_an_existing_entry() {
        let exe = std::env::temp_dir().join(format!("s1mp1e-gpu-test-{}", std::process::id())).join("javaw.exe");
        let key = r"HKCU\Software\Microsoft\DirectX\UserGpuPreferences";
        let name = exe.to_string_lossy().to_string();
        assert_eq!(prefer_high_performance_gpu(&exe).unwrap(), true);
        let q = std::process::Command::new("reg").args(["query", key, "/v", &name]).output().unwrap();
        let out = String::from_utf8_lossy(&q.stdout).to_string();
        println!("{out}");
        assert!(out.contains("GpuPreference=2;"));
        assert_eq!(prefer_high_performance_gpu(&exe).unwrap(), false);   // already there: left alone
        let d = std::process::Command::new("reg").args(["delete", key, "/v", &name, "/f"]).output().unwrap();
        assert!(d.status.success());
    }
}
