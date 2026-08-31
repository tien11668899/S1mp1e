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
    // exactly one of these describes the game args
    modern_jvm: Vec<String>,
    modern_game: Vec<String>,
    legacy_args: Option<String>,
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
    let java_component = base.java_version.as_ref().map(|j| j.component.clone());

    // libraries: child chain first, dedupe keeping the first of each. The key MUST
    // include the classifier: modern (1.19+) natives ship as a sibling artifact
    // `group:artifact:version:natives-windows` alongside the plain
    // `group:artifact:version`. Keying on group:artifact alone collides them, so the
    // natives entry gets dropped, `extract_natives` never sees lwjgl.dll, and the
    // game dies with "Failed to locate library: lwjgl.dll". Key on
    // group:artifact[:classifier] (version excluded so child overrides parent).
    let mut libraries: Vec<Library> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for vj in &chain {
        for lib in &vj.libraries {
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
    let legacy_args = if has_modern { None } else {
        chain.iter().find_map(|v| v.minecraft_arguments.clone())
    };

    Ok(Merged {
        id: child.id.clone(), base_id, main_class, libraries,
        asset_index_id, java_component, modern_jvm, modern_game, legacy_args,
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

/// Every `.jar` under `.minecraft/s1mp1e-mods/` MINUS the glass-*.jar files
/// (those are picked separately, per-MC). Also picks jars from the version-
/// scoped subfolder `s1mp1e-mods/<mc>/`, which is where "下載到所有版本" writes
/// — Fabric would reject two versions of the same mod on the classpath, so
/// per-MC subfolders keep the copies apart and only the current MC's copy is
/// loaded at launch time.
fn pick_user_mods(root: &PathBuf, mc: &str) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut collect = |dir: PathBuf| {
        if let Ok(rd) = std::fs::read_dir(&dir) {
            for e in rd.flatten() {
                let p = e.path();
                let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
                if name.ends_with(".jar") && !name.starts_with("glass-") {
                    out.push(p);
                }
            }
        }
    };
    // Prefer the per-MC subfolder. If it exists AND has jars, use ONLY those —
    // the root folder is stale test files or 1.21.1 downloads that would break
    // a 1.20.1 launch with version-mismatched fabric-api etc. Falling back to
    // root only when the per-MC folder is empty keeps single-version installs
    // working without the user reorganising anything.
    let per_mc = root.join("s1mp1e-mods").join(mc);
    let per_mc_has_jars = std::fs::read_dir(&per_mc)
        .map(|rd| rd.flatten().any(|e| {
            let name = e.file_name();
            let s = name.to_string_lossy();
            s.ends_with(".jar") && !s.starts_with("glass-")
        }))
        .unwrap_or(false);
    if per_mc_has_jars {
        collect(per_mc);
    } else {
        collect(root.join("s1mp1e-mods"));
    }
    out.sort();
    out
}

/// Build the full java command line for `id`.
pub struct LaunchPlan {
    pub java_exe: PathBuf,
    pub args: Vec<String>,
    pub cwd: PathBuf,
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
    // Share the settings file on first run.
    let opt = inst.join("options.txt");
    if !opt.exists() && root.join("options.txt").exists() {
        let _ = std::fs::copy(root.join("options.txt"), &opt);
    }
    inst
}

pub fn plan_launch(root: &PathBuf, id: &str, auth: &AuthInfo, ram_mb: u32) -> Result<LaunchPlan> {
    let merged = resolve(root, id)?;
    let natives = extract_natives(root, &merged)?;
    // Per-version gameDir for mod isolation (assets/libraries/natives stay shared under root).
    let gamedir = setup_instance(root, &merged.base_id);

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
    let java_exe = if let Ok(p) = std::env::var("S1MP1E_JAVA") {
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
    args.push(format!("-Xmx{ram_mb}M"));
    args.push(format!("-Xms{}M", (ram_mb / 2).max(512)));
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
    if is_fabric {
        // Combine glass-<mc>.jar (version-specific) with any user-downloaded
        // Modrinth mods into ONE -Dfabric.addMods argument, `;`-separated so
        // Fabric loads them all onto the classpath.
        let mut jars: Vec<PathBuf> = Vec::new();
        if let Some(glass) = pick_glass_jar(root, &merged.base_id) { jars.push(glass); }
        jars.extend(pick_user_mods(root, &merged.base_id));
        if !jars.is_empty() {
            let joined = jars.iter()
                .map(|p| p.to_string_lossy().into_owned())
                .collect::<Vec<_>>()
                .join(";");
            args.push(format!("-Dfabric.addMods={joined}"));
        }
    }

    if !merged.modern_jvm.is_empty() {
        for a in &merged.modern_jvm { args.push(subst(a)); }
    } else {
        // legacy: no jvm args in json — supply the essentials
        args.push(format!("-Djava.library.path={}", natives.to_string_lossy()));
        args.push("-cp".into());
        args.push(classpath.clone());
    }
    args.push(merged.main_class.clone());

    if let Some(legacy) = &merged.legacy_args {
        for tok in legacy.split_whitespace() { args.push(subst(tok)); }
    } else {
        for a in &merged.modern_game { args.push(subst(a)); }
    }

    Ok(LaunchPlan { java_exe, args, cwd: gamedir })
}

/// Spawn the JVM, streaming stdout+stderr lines via `on_line`, and return the
/// exit code once the process ends (blocks — caller runs it off-thread).
pub fn run_blocking(plan: LaunchPlan, on_line: impl Fn(String) + Send + Sync + 'static) -> Result<i32> {
    if !plan.java_exe.exists() {
        return Err(anyhow!("java not installed: {}", plan.java_exe.display()));
    }
    on_line(format!("$ {} {}", plan.java_exe.display(), plan.args.join(" ")));
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
    }
    let mut child = cmd.spawn().context("spawn jvm")?;

    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let on1 = std::sync::Arc::new(on_line);
    let on2 = on1.clone();
    let t1 = std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) { on1(line); }
    });
    let t2 = std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) { on2(line); }
    });
    let status = child.wait()?;
    let _ = t1.join();
    let _ = t2.join();
    Ok(status.code().unwrap_or(-1))
}
