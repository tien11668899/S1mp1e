//! Carry the player's game settings (`options.txt`) between versions.
//!
//! Every MC version launches in its own instance folder (`instances/<mc>/`, see `launch::setup_instance`), so each one
//! used to start from Minecraft's defaults: key bindings, mouse sensitivity, volume, GUI scale, language — and the
//! accessibility first-run screen — all over again. Two entry points share the rules here:
//!
//! - [`seed_new_instance`]: the first launch of a version (no `options.txt` yet) starts from the settings of the version
//!   played most recently.
//! - [`import`]: the launcher's 「從其他版本匯入設定」 copies one version's settings over another's existing file.
//!
//! What carries: every line except the version-specific ones in [`DENY`]. Since 1.13 key bindings are stored by name
//! (`key_key.jump:key.keyboard.space`), the same in 1.21.1 and 26.x; options a version does not know are ignored by it
//! and the ones it lacks keep their defaults, so a whole-file copy minus the deny-list is safe within that family.
//! 1.8.9 / 1.12.2 store keys as LWJGL2 numbers instead, so files never cross between the two families.
//!
//! The data version (`version:` line): a seeded file keeps the SOURCE's, so a newer game upgrades an older file with
//! its own data fixers and an older game leaves a newer file's values as they are; a merge keeps the TARGET's.

use std::path::{Path, PathBuf};

/// Options that never carry: the resource-pack list (per version; the launcher manages it), 26.x's graphics backend
/// (forced to OpenGL at launch) and graphics preset vs 1.21's graphics mode (different meanings across versions),
/// window sizes, and the data version (handled separately).
const DENY: &[&str] = &[
    "version",
    "resourcePacks",
    "incompatibleResourcePacks",
    "preferredGraphicsBackend",
    "graphicsPreset",
    "graphicsMode",
    "fullscreenResolution",
    "overrideWidth",
    "overrideHeight",
    "glDebugVerbosity",
];

/// How a file stores its key bindings: by name (1.13+, 26.x) or as LWJGL2 key numbers (1.12.2 and older).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Family {
    Named,
    Legacy,
}

/// The family a version id launches with: `1.x` below 1.13 is legacy, everything else (1.13+, 26.x) is named.
pub fn family_of_version(mc: &str) -> Family {
    let mut parts = mc.split('.');
    if parts.next() == Some("1") {
        if let Some(minor) = parts.next().and_then(|m| m.parse::<u32>().ok()) {
            if minor < 13 {
                return Family::Legacy;
            }
        }
    }
    Family::Named
}

/// The family a settings file is written in, from its key-binding values; None when it has no key bindings.
pub fn family_of_text(text: &str) -> Option<Family> {
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("key_") {
            if let Some((_, value)) = rest.split_once(':') {
                let v = value.trim();
                if v.starts_with("key.") {
                    return Some(Family::Named);
                }
                if v.parse::<i64>().is_ok() {
                    return Some(Family::Legacy);
                }
            }
        }
    }
    None
}

fn key_of(line: &str) -> Option<&str> {
    let (k, _) = line.split_once(':')?;
    let k = k.trim();
    (!k.is_empty()).then_some(k)
}

fn portable(key: &str) -> bool {
    !DENY.contains(&key)
}

fn newline_of(text: &str) -> &'static str {
    if text.contains("\r\n") { "\r\n" } else { "\n" }
}

/// The settings to write for a version that has none yet: the source's data version first, then every portable line.
pub fn seeded_text(source: &str) -> String {
    let nl = newline_of(source);
    let mut out: Vec<&str> = Vec::new();
    if let Some(v) = source.lines().find(|l| key_of(l) == Some("version")) {
        out.push(v);
    }
    out.extend(source.lines().filter(|l| key_of(l).is_some_and(portable)));
    let mut s = out.join(nl);
    s.push_str(nl);
    s
}

/// `target` with every portable setting of `source` written over it: lines the target already has are replaced in
/// place, the rest appended (a version ignores options it does not know); the target's version-specific lines (and its
/// data version) are kept. Returns the new text and how many of the target's settings changed value.
pub fn merged_text(target: &str, source: &str) -> (String, usize) {
    let nl = newline_of(target);
    let src: Vec<(&str, &str)> =
        source.lines().filter_map(|l| key_of(l).filter(|k| portable(k)).map(|k| (k, l))).collect();
    let mut used = vec![false; src.len()];
    let mut changed = 0;
    let mut out: Vec<&str> = Vec::new();
    for line in target.lines() {
        match key_of(line).and_then(|k| src.iter().position(|(sk, _)| *sk == k)) {
            Some(i) => {
                if src[i].1 != line {
                    changed += 1;
                }
                out.push(src[i].1);
                used[i] = true;
            }
            None => out.push(line),
        }
    }
    for (i, (_, l)) in src.iter().enumerate() {
        if !used[i] {
            out.push(l);
        }
    }
    let mut s = out.join(nl);
    s.push_str(nl);
    (s, changed)
}

fn options_path(root: &Path, mc: &str) -> PathBuf {
    root.join("instances").join(mc).join("options.txt")
}

/// Other versions' settings files, most recently written first: `(version, path)`.
pub fn sources(root: &Path, exclude: &str) -> Vec<(String, PathBuf)> {
    let mut found: Vec<(std::time::SystemTime, String, PathBuf)> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(root.join("instances")) {
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            if name == exclude {
                continue;
            }
            let p = e.path().join("options.txt");
            if let Ok(t) = std::fs::metadata(&p).and_then(|m| m.modified()) {
                found.push((t, name, p));
            }
        }
    }
    found.sort_by(|a, b| b.0.cmp(&a.0));
    found.into_iter().map(|(_, n, p)| (n, p)).collect()
}

/// Write `text` to `path` through a temp file, so a crash mid-write never leaves a truncated settings file.
fn write_atomic(path: &Path, text: &str) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let tmp = path.with_extension("txt.s1tmp");
    std::fs::write(&tmp, text)?;
    std::fs::rename(&tmp, path).inspect_err(|_| {
        let _ = std::fs::remove_file(&tmp);
    })
}

/// First launch of `mc` (its instance has no `options.txt`): start from the most recently played version whose file
/// is in the same family, or from a main `.minecraft/options.txt` (the official launcher's) when no version has one.
/// Returns the version the settings came from ("" for the main file), or None when nothing was seeded. Never fatal.
pub fn seed_new_instance(root: &Path, mc: &str) -> Option<String> {
    let dst = options_path(root, mc);
    if dst.exists() {
        return None;
    }
    let want = family_of_version(mc);
    let candidates = sources(root, mc)
        .into_iter()
        .chain(std::iter::once((String::new(), root.join("options.txt"))));
    for (from, path) in candidates {
        let Ok(text) = std::fs::read_to_string(&path) else { continue };
        if family_of_text(&text) != Some(want) {
            continue;
        }
        if write_atomic(&dst, &seeded_text(&text)).is_ok() {
            return Some(from);
        }
        return None;
    }
    None
}

/// Copy `from`'s settings over `to`'s (creating `to`'s file when it has none); `to`'s previous file is kept as
/// `options.txt.bak`. Returns how many settings changed (every one for a version that had none); errors are user-facing
/// (Traditional Chinese).
pub fn import(root: &Path, from: &str, to: &str) -> Result<usize, String> {
    if from == to {
        return Err("來源和目標是同一個版本".into());
    }
    let src_path = options_path(root, from);
    let source = std::fs::read_to_string(&src_path).map_err(|_| format!("{from} 還沒有遊戲設定（先開過一次遊戲）"))?;
    if family_of_text(&source).is_some_and(|f| f != family_of_version(to)) {
        return Err(format!("{from} 和 {to} 的按鍵格式不同（1.12.2 以前與 1.13 以後），無法互相匯入"));
    }
    let dst = options_path(root, to);
    let (text, n) = match std::fs::read_to_string(&dst) {
        Ok(target) => {
            std::fs::write(dst.with_extension("txt.bak"), &target).map_err(|e| format!("無法備份 {to} 的設定：{e}"))?;
            merged_text(&target, &source)
        }
        Err(_) => {
            let s = seeded_text(&source);
            let n = s.lines().filter(|l| key_of(l).is_some_and(portable)).count();
            (s, n)
        }
    };
    write_atomic(&dst, &text).map_err(|e| format!("無法寫入 {to} 的設定：{e}"))?;
    Ok(n)
}

#[cfg(test)]
mod tests {
    use super::*;

    const V263: &str = "version:5023\nresourcePacks:[\"vanilla\"]\npreferredGraphicsBackend:\"opengl\"\ngraphicsPreset:\"fancy\"\nmouseSensitivity:0.21\nlang:zh_tw\nonboardAccessibility:false\nkey_key.jump:key.keyboard.space\nkey_key.sprint:key.keyboard.left.control\nsoundCategory_master:0.5\n";

    #[test]
    fn families() {
        assert_eq!(family_of_version("1.8.9"), Family::Legacy);
        assert_eq!(family_of_version("1.12.2"), Family::Legacy);
        assert_eq!(family_of_version("1.13.2"), Family::Named);
        assert_eq!(family_of_version("1.21.1"), Family::Named);
        assert_eq!(family_of_version("26.3"), Family::Named);
        assert_eq!(family_of_text(V263), Some(Family::Named));
        assert_eq!(family_of_text("version:1343\nkey_key.jump:57\n"), Some(Family::Legacy));
        assert_eq!(family_of_text("lang:en_us\n"), None);
    }

    #[test]
    fn seeding_keeps_portable_lines_and_source_version() {
        let s = seeded_text(V263);
        assert!(s.starts_with("version:5023\n"));
        assert!(s.contains("key_key.sprint:key.keyboard.left.control\n"));
        assert!(s.contains("mouseSensitivity:0.21\n"));
        assert!(s.contains("onboardAccessibility:false\n"));
        assert!(!s.contains("resourcePacks"));
        assert!(!s.contains("preferredGraphicsBackend"));
        assert!(!s.contains("graphicsPreset"));
    }

    #[test]
    fn merging_replaces_in_place_and_keeps_target_specifics() {
        let target = "version:3955\r\ngraphicsMode:1\r\nmouseSensitivity:0.5\r\nresourcePacks:[\"vanilla\",\"fabric\"]\r\nkey_key.jump:key.keyboard.j\r\n";
        let (s, n) = merged_text(target, V263);
        assert_eq!(n, 2);   // mouseSensitivity and the jump key differed; the rest is new to the target
        let lines: Vec<&str> = s.split("\r\n").collect();
        assert_eq!(lines[0], "version:3955");
        assert_eq!(lines[1], "graphicsMode:1");
        assert_eq!(lines[2], "mouseSensitivity:0.21");
        assert_eq!(lines[3], "resourcePacks:[\"vanilla\",\"fabric\"]");
        assert_eq!(lines[4], "key_key.jump:key.keyboard.space");
        assert!(s.contains("lang:zh_tw\r\n"));
        assert!(s.contains("key_key.sprint:key.keyboard.left.control\r\n"));
        assert!(!s.contains("graphicsPreset"));
    }

    #[test]
    fn seed_and_import_on_disk() {
        let root = std::env::temp_dir().join(format!("s1mp1e-optsync-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("instances/26.3")).unwrap();
        std::fs::create_dir_all(root.join("instances/1.8.9")).unwrap();
        std::fs::write(root.join("instances/26.3/options.txt"), V263).unwrap();
        std::fs::write(root.join("instances/1.8.9/options.txt"), "key_key.jump:57\n").unwrap();

        // a new named-family version starts from 26.3; a legacy one finds 1.8.9's file only
        assert_eq!(seed_new_instance(&root, "1.21.1").as_deref(), Some("26.3"));
        assert!(std::fs::read_to_string(root.join("instances/1.21.1/options.txt")).unwrap().contains("lang:zh_tw"));
        assert_eq!(seed_new_instance(&root, "1.21.1"), None);   // already has one
        assert_eq!(seed_new_instance(&root, "1.12.2").as_deref(), Some("1.8.9"));

        // import across families is refused; within a family it merges
        assert!(import(&root, "26.3", "1.12.2").is_err());
        std::fs::write(root.join("instances/1.21.1/options.txt"), "version:3955\nlang:en_us\n").unwrap();
        assert_eq!(import(&root, "26.3", "1.21.1"), Ok(1));   // lang
        let t = std::fs::read_to_string(root.join("instances/1.21.1/options.txt")).unwrap();
        assert!(t.starts_with("version:3955\nlang:zh_tw\n"));
        let bak = std::fs::read_to_string(root.join("instances/1.21.1/options.txt.bak")).unwrap();
        assert_eq!(bak, "version:3955\nlang:en_us\n");
        let _ = std::fs::remove_dir_all(&root);
    }
}
