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
//! 1.8.9 / 1.12.2 store keys as LWJGL2 numbers instead. Seeding never crosses between the two families; an import does,
//! through [`converted_text`]: key bindings are translated ([`convert_key`]), the options both families store the same
//! way ([`CROSS`]) are copied, a few are rewritten (clouds, language code, smooth lighting), the rest stay as they were.
//! An import also carries the mod settings both versions have ([`MOD_CONFIGS`], merged key by key).
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

/// LWJGL2 key codes (1.12.2 and older) of the named keys (1.13+). Mouse buttons are not here: LWJGL2 stores them as
/// button − 100 (left −100, right −99, middle −98, `key.mouse.4` −97 …), `key.keyboard.unknown` is 0.
const LEGACY_KEYS: &[(&str, i32)] = &[
    ("escape", 1), ("1", 2), ("2", 3), ("3", 4), ("4", 5), ("5", 6), ("6", 7), ("7", 8), ("8", 9), ("9", 10), ("0", 11),
    ("minus", 12), ("equal", 13), ("backspace", 14), ("tab", 15), ("q", 16), ("w", 17), ("e", 18), ("r", 19), ("t", 20),
    ("y", 21), ("u", 22), ("i", 23), ("o", 24), ("p", 25), ("left.bracket", 26), ("right.bracket", 27), ("enter", 28),
    ("left.control", 29), ("a", 30), ("s", 31), ("d", 32), ("f", 33), ("g", 34), ("h", 35), ("j", 36), ("k", 37),
    ("l", 38), ("semicolon", 39), ("apostrophe", 40), ("grave.accent", 41), ("left.shift", 42), ("backslash", 43),
    ("z", 44), ("x", 45), ("c", 46), ("v", 47), ("b", 48), ("n", 49), ("m", 50), ("comma", 51), ("period", 52),
    ("slash", 53), ("right.shift", 54), ("keypad.multiply", 55), ("left.alt", 56), ("space", 57), ("caps.lock", 58),
    ("f1", 59), ("f2", 60), ("f3", 61), ("f4", 62), ("f5", 63), ("f6", 64), ("f7", 65), ("f8", 66), ("f9", 67),
    ("f10", 68), ("num.lock", 69), ("scroll.lock", 70), ("keypad.7", 71), ("keypad.8", 72), ("keypad.9", 73),
    ("keypad.subtract", 74), ("keypad.4", 75), ("keypad.5", 76), ("keypad.6", 77), ("keypad.add", 78), ("keypad.1", 79),
    ("keypad.2", 80), ("keypad.3", 81), ("keypad.0", 82), ("keypad.decimal", 83), ("f11", 87), ("f12", 88),
    ("f13", 100), ("f14", 101), ("f15", 102), ("keypad.equal", 141), ("keypad.enter", 156), ("right.control", 157),
    ("keypad.divide", 181), ("print.screen", 183), ("right.alt", 184), ("pause", 197), ("home", 199), ("up", 200),
    ("page.up", 201), ("left", 203), ("right", 205), ("end", 207), ("down", 208), ("page.down", 209), ("insert", 210),
    ("delete", 211), ("left.win", 219), ("right.win", 220), ("menu", 221),
];

/// A key-binding value in the other family's notation (`key.keyboard.g` ↔ `34`, `key.mouse.5` ↔ `-96`); None when the
/// key has no counterpart there (the target then keeps its own binding).
pub fn convert_key(value: &str, to: Family) -> Option<String> {
    let v = value.trim();
    match to {
        Family::Legacy => {
            if v == "key.keyboard.unknown" {
                return Some("0".into());
            }
            if let Some(b) = v.strip_prefix("key.mouse.") {
                let button = match b {
                    "left" => 0,
                    "right" => 1,
                    "middle" => 2,
                    n => n.parse::<i32>().ok().filter(|n| *n >= 4)? - 1,
                };
                return Some((button - 100).to_string());
            }
            let name = v.strip_prefix("key.keyboard.")?;
            LEGACY_KEYS.iter().find(|(n, _)| *n == name).map(|(_, c)| c.to_string())
        }
        Family::Named => {
            let code = v.parse::<i32>().ok()?;
            if code == 0 {
                return Some("key.keyboard.unknown".into());
            }
            if code < 0 {
                return Some(match code + 100 {
                    0 => "key.mouse.left".into(),
                    1 => "key.mouse.right".into(),
                    2 => "key.mouse.middle".into(),
                    b if b >= 3 => format!("key.mouse.{}", b + 1),
                    _ => return None,
                });
            }
            LEGACY_KEYS.iter().find(|(_, c)| *c == code).map(|(n, _)| format!("key.keyboard.{n}"))
        }
    }
}

/// Options both families store with the same meaning (written the same way unless [`cross_value`] rewrites them).
/// Graphics quality is not among them: 1.8.9's `fancyGraphics`, 1.21's `graphicsMode` and 26.x's presets differ.
const CROSS: &[&str] = &[
    "mouseSensitivity", "invertYMouse", "fov", "gamma", "renderDistance", "guiScale", "particles", "bobView", "maxFps",
    "fullscreen", "enableVsync", "entityShadows", "mipmapLevels", "chatOpacity", "chatScale", "chatWidth",
    "chatHeightFocused", "chatHeightUnfocused", "chatColors", "chatLinks", "chatLinksPrompt", "chatVisibility",
    "advancedItemTooltips", "pauseOnLostFocus", "reducedDebugInfo", "forceUnicodeFont", "touchscreen",
    "useNativeTransport", "realmsNotifications", "hideServerAddress", "renderClouds", "lang", "ao",
];

/// `key`'s value from the other family written for `to`; None = not carried across families.
fn cross_value(key: &str, value: &str, to: Family) -> Option<String> {
    if key.starts_with("key_") {
        return convert_key(value, to);
    }
    if key.starts_with("soundCategory_") || key.starts_with("modelPart_") {
        return Some(value.to_string());
    }
    if !CROSS.contains(&key) {
        return None;
    }
    let v = value.trim();
    Some(match (key, to) {
        // 1.13+ quotes the clouds mode ("true" / "fast" / "false"), 1.12.2 and older do not
        ("renderClouds", Family::Legacy) => v.trim_matches('"').to_string(),
        ("renderClouds", Family::Named) => format!("\"{}\"", v.trim_matches('"')),
        // language codes: zh_tw (1.11+) / zh_TW (older)
        ("lang", Family::Legacy) => match v.split_once('_') {
            Some((a, b)) => format!("{}_{}", a.to_lowercase(), b.to_uppercase()),
            None => v.to_string(),
        },
        ("lang", Family::Named) => v.to_lowercase(),
        // smooth lighting: on/off (1.13+) vs 0 off, 1 minimum, 2 maximum
        ("ao", Family::Legacy) => (if v == "false" { "0" } else { "2" }).to_string(),
        ("ao", Family::Named) => (if v == "0" { "false" } else { "true" }).to_string(),
        _ => v.to_string(),
    })
}

/// `target` (a file of family `to`) with the settings of `source` (the other family) that carry across written over the
/// lines the target already has; nothing is appended. Returns the new text and how many settings changed value.
pub fn converted_text(target: &str, source: &str, to: Family) -> (String, usize) {
    let nl = newline_of(target);
    let src: std::collections::HashMap<&str, &str> =
        source.lines().filter_map(|l| l.split_once(':')).map(|(k, v)| (k.trim(), v)).collect();
    let mut changed = 0;
    let mut out: Vec<String> = Vec::new();
    for line in target.lines() {
        let new = line
            .split_once(':')
            .and_then(|(k, old)| Some((k, old, cross_value(k.trim(), src.get(k.trim())?, to)?)))
            .filter(|(_, old, v)| old.trim() != v.as_str())
            .map(|(k, _, v)| format!("{k}:{v}"));
        match new {
            Some(l) => {
                changed += 1;
                out.push(l);
            }
            None => out.push(line.to_string()),
        }
    }
    let mut s = out.join(nl);
    s.push_str(nl);
    (s, changed)
}

/// Mod settings an import carries when both versions have the file: the same mod's options, merged key by key (keys
/// one version's build of the mod lacks are left alone). Machine-specific files (Sodium's fingerprint) are not here.
pub const MOD_CONFIGS: &[&str] =
    &["sodium-options.json", "sodium-extra-options.json", "entityculling.json", "immediatelyfast.json", "continuity.json"];

/// Write `src`'s values over `dst` wherever both have the key with the same kind of value; objects merge recursively.
/// Returns how many values changed.
fn merge_json(dst: &mut serde_json::Value, src: &serde_json::Value) -> usize {
    use serde_json::Value;
    match (dst, src) {
        (Value::Object(d), Value::Object(s)) => {
            d.iter_mut().map(|(k, dv)| s.get(k).map_or(0, |sv| merge_json(dv, sv))).sum()
        }
        (d, s) => {
            let same_kind = matches!(
                (&*d, s),
                (Value::Bool(_), Value::Bool(_))
                    | (Value::Number(_), Value::Number(_))
                    | (Value::String(_), Value::String(_))
                    | (Value::Array(_), Value::Array(_))
            );
            if same_kind && *d != *s {
                *d = s.clone();
                1
            } else {
                0
            }
        }
    }
}

/// Merge `from`'s [`MOD_CONFIGS`] into `to`'s; a changed file is kept as `<name>.bak` first. Unreadable files are
/// skipped. Returns how many values changed.
pub fn import_mod_configs(root: &Path, from: &str, to: &str) -> Result<usize, String> {
    let mut total = 0;
    for name in MOD_CONFIGS {
        let src_path = root.join("instances").join(from).join("config").join(name);
        let dst_path = root.join("instances").join(to).join("config").join(name);
        let (Ok(src_text), Ok(dst_text)) = (std::fs::read_to_string(&src_path), std::fs::read_to_string(&dst_path)) else {
            continue;
        };
        let (Ok(src), Ok(mut dst)) =
            (serde_json::from_str::<serde_json::Value>(&src_text), serde_json::from_str::<serde_json::Value>(&dst_text))
        else {
            continue;
        };
        let n = merge_json(&mut dst, &src);
        if n == 0 {
            continue;
        }
        let mut bak = dst_path.clone().into_os_string();
        bak.push(".bak");
        std::fs::write(&bak, &dst_text).map_err(|e| format!("無法備份 {to} 的 {name}：{e}"))?;
        let text = serde_json::to_string_pretty(&dst).map_err(|e| e.to_string())?;
        write_atomic(&dst_path, &text).map_err(|e| format!("無法寫入 {to} 的 {name}：{e}"))?;
        total += n;
    }
    Ok(total)
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

/// Copy `from`'s settings over `to`'s (creating `to`'s file when it has none, within one family); `to`'s previous file
/// is kept as `options.txt.bak`. Between the two families (1.12.2 and older ↔ 1.13+) the settings are converted
/// ([`converted_text`]), which needs `to` to have a file already. Then the mod settings both have are merged
/// ([`import_mod_configs`]). Returns (game settings changed, mod settings changed); errors are user-facing
/// (Traditional Chinese).
pub fn import(root: &Path, from: &str, to: &str) -> Result<(usize, usize), String> {
    if from == to {
        return Err("來源和目標是同一個版本".into());
    }
    let src_path = options_path(root, from);
    let source = std::fs::read_to_string(&src_path).map_err(|_| format!("{from} 還沒有遊戲設定（先開過一次遊戲）"))?;
    let src_family = family_of_text(&source).unwrap_or_else(|| family_of_version(from));
    let dst = options_path(root, to);
    let (text, n) = match std::fs::read_to_string(&dst) {
        Ok(target) => {
            let to_family = family_of_text(&target).unwrap_or_else(|| family_of_version(to));
            std::fs::write(dst.with_extension("txt.bak"), &target).map_err(|e| format!("無法備份 {to} 的設定：{e}"))?;
            if to_family == src_family { merged_text(&target, &source) } else { converted_text(&target, &source, to_family) }
        }
        Err(_) if family_of_version(to) != src_family => {
            return Err(format!("{to} 還沒有遊戲設定，先開過一次 {to} 再匯入（{from} 和 {to} 的設定格式不同，需要 {to} 自己的設定檔來轉換）"));
        }
        Err(_) => {
            let s = seeded_text(&source);
            let n = s.lines().filter(|l| key_of(l).is_some_and(portable)).count();
            (s, n)
        }
    };
    write_atomic(&dst, &text).map_err(|e| format!("無法寫入 {to} 的設定：{e}"))?;
    let mods = import_mod_configs(root, from, to)?;
    Ok((n, mods))
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

        // across families an import needs the target's own file; within a family it merges
        assert!(import(&root, "26.3", "1.7.10").is_err());
        std::fs::write(root.join("instances/1.21.1/options.txt"), "version:3955\nlang:en_us\n").unwrap();
        assert_eq!(import(&root, "26.3", "1.21.1"), Ok((1, 0)));   // lang
        let t = std::fs::read_to_string(root.join("instances/1.21.1/options.txt")).unwrap();
        assert!(t.starts_with("version:3955\nlang:zh_tw\n"));
        let bak = std::fs::read_to_string(root.join("instances/1.21.1/options.txt.bak")).unwrap();
        assert_eq!(bak, "version:3955\nlang:en_us\n");

        // across families: 1.12.2 (seeded from 1.8.9 above) gets 26.3's settings converted
        assert_eq!(import(&root, "26.3", "1.12.2"), Ok((0, 0)));   // jump is space (57) in both
        std::fs::write(root.join("instances/1.12.2/options.txt"), "key_key.jump:44\nlang:en_US\n").unwrap();
        assert_eq!(import(&root, "26.3", "1.12.2"), Ok((2, 0)));
        assert_eq!(std::fs::read_to_string(root.join("instances/1.12.2/options.txt")).unwrap(), "key_key.jump:57\nlang:zh_TW\n");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn keys_convert_both_ways() {
        for (named, legacy) in [
            ("key.keyboard.g", "34"), ("key.keyboard.left.shift", "42"), ("key.keyboard.f5", "63"), ("key.keyboard.1", "2"),
            ("key.mouse.left", "-100"), ("key.mouse.right", "-99"), ("key.mouse.middle", "-98"), ("key.mouse.4", "-97"),
            ("key.mouse.5", "-96"), ("key.keyboard.unknown", "0"),
        ] {
            assert_eq!(convert_key(named, Family::Legacy).as_deref(), Some(legacy), "{named}");
            assert_eq!(convert_key(legacy, Family::Named).as_deref(), Some(named), "{legacy}");
        }
        assert_eq!(convert_key("key.keyboard.world.1", Family::Legacy), None);
        assert_eq!(convert_key("999", Family::Named), None);
    }

    #[test]
    fn converting_between_families() {
        let named = "version:5023\nmouseSensitivity:0.21\nrenderClouds:\"false\"\nlang:zh_tw\nao:true\nsimulationDistance:6\n\
                     soundCategory_music:0.0\nkey_key.drop:key.keyboard.g\nkey_key.hotbar.1:key.mouse.5\nkey_key.swapOffhand:key.keyboard.h\n";
        let legacy = "mouseSensitivity:0.5\r\nrenderClouds:true\r\nlang:en_US\r\nao:1\r\nsoundCategory_music:1.0\r\n\
                      key_key.drop:16\r\nkey_key.hotbar.1:2\r\nfancyGraphics:true\r\n";
        let (s, n) = converted_text(legacy, named, Family::Legacy);
        assert_eq!(n, 7);
        assert_eq!(s, "mouseSensitivity:0.21\r\nrenderClouds:false\r\nlang:zh_TW\r\nao:2\r\nsoundCategory_music:0.0\r\n\
                       key_key.drop:34\r\nkey_key.hotbar.1:-96\r\nfancyGraphics:true\r\n");
        // and back: the named file's own options (simulationDistance, swapOffhand) stay
        let (back, n) = converted_text(named, &s, Family::Named);
        assert_eq!(n, 0);
        assert_eq!(back, named);
    }

    #[test]
    fn mod_configs_merge_shared_keys() {
        let root = std::env::temp_dir().join(format!("s1mp1e-modcfg-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        for v in ["a", "b"] {
            std::fs::create_dir_all(root.join(format!("instances/{v}/config"))).unwrap();
        }
        std::fs::write(root.join("instances/a/config/entityculling.json"), r#"{"solidLeaves":true,"onlyInA":1,"list":["x"]}"#).unwrap();
        std::fs::write(root.join("instances/b/config/entityculling.json"), r#"{"solidLeaves":false,"list":[],"onlyInB":"k"}"#).unwrap();
        std::fs::write(root.join("instances/a/config/sodium-fingerprint.json"), r#"{"v":1}"#).unwrap();
        std::fs::write(root.join("instances/b/config/sodium-fingerprint.json"), r#"{"v":2}"#).unwrap();
        assert_eq!(import_mod_configs(&root, "a", "b"), Ok(2));
        let b: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(root.join("instances/b/config/entityculling.json")).unwrap()).unwrap();
        assert_eq!(b, serde_json::json!({"solidLeaves": true, "list": ["x"], "onlyInB": "k"}));
        assert!(root.join("instances/b/config/entityculling.json.bak").exists());
        assert_eq!(std::fs::read_to_string(root.join("instances/b/config/sodium-fingerprint.json")).unwrap(), r#"{"v":2}"#);
        let _ = std::fs::remove_dir_all(&root);
    }
}
