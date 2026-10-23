//! Per-version play time in `%APPDATA%\S1mp1e\playtime.json` (`{ "<mc>": { "seconds", "last", "sessions" } }`), the
//! file the launcher UI shows on the Play page (PlayStats.cs reads it). Recorded here, by the process that waits for
//! the game, so a session counts even when the launcher window was closed meanwhile ("關閉啟動器" after launch).

use serde_json::{json, Map, Value};
use std::path::PathBuf;

fn file_path() -> Option<PathBuf> {
    if let Ok(p) = std::env::var("S1MP1E_PLAYTIME_FILE") {
        if !p.is_empty() {
            return Some(PathBuf::from(p));
        }
    }
    std::env::var_os("APPDATA").map(|a| PathBuf::from(a).join("S1mp1e").join("playtime.json"))
}

/// Add one session of `seconds` to `mc`. Sessions under 5 s (a crash at start-up) are not counted. A file that
/// exists but cannot be read right now is left alone (this session is dropped rather than every earlier one); one
/// that does not parse is kept aside as `playtime.json.corrupt` and a fresh file starts.
pub fn record(mc: &str, seconds: u64) {
    if seconds < 5 || mc.is_empty() {
        return;
    }
    let Some(path) = file_path() else { return };
    record_at(&path, mc, seconds, crate::config::now_secs() as i64);
}

pub(crate) fn record_at(path: &std::path::Path, mc: &str, seconds: u64, now: i64) {
    let mut all: Map<String, Value> = match std::fs::read_to_string(path) {
        Ok(text) => match serde_json::from_str::<Value>(&text) {
            Ok(Value::Object(m)) => m,
            _ => {
                let _ = std::fs::copy(path, path.with_extension("json.corrupt"));
                Map::new()
            }
        },
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Map::new(),
        Err(_) => return,
    };
    let entry = all.entry(mc.to_string()).or_insert_with(|| json!({}));
    if !entry.is_object() {
        *entry = json!({});
    }
    let e = entry.as_object_mut().expect("object");
    let secs = e.get("seconds").and_then(Value::as_i64).unwrap_or(0) + seconds as i64;
    let sessions = e.get("sessions").and_then(Value::as_i64).unwrap_or(0) + 1;
    e.insert("seconds".into(), json!(secs));
    e.insert("sessions".into(), json!(sessions));
    e.insert("last".into(), json!(now));
    if let Some(dir) = path.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let tmp = path.with_extension(format!("json.{}.tmp", std::process::id()));
    let ok = serde_json::to_string_pretty(&Value::Object(all))
        .ok()
        .map(|s| std::fs::write(&tmp, s).is_ok() && std::fs::rename(&tmp, path).is_ok())
        .unwrap_or(false);
    if !ok {
        let _ = std::fs::remove_file(&tmp);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adds_sessions_and_keeps_other_versions() {
        let dir = std::env::temp_dir().join(format!("s1mp1e-playtime-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("playtime.json");
        std::fs::write(&p, r#"{ "1.8.9": { "seconds": 100, "last": 1, "sessions": 2 } }"#).unwrap();
        record_at(&p, "26.2", 60, 1000);
        record_at(&p, "26.2", 30, 2000);
        record_at(&p, "26.2", 3, 3000);   // too short: ignored by record(), but record_at counts what it is given
        let v: Value = serde_json::from_str(&std::fs::read_to_string(&p).unwrap()).unwrap();
        assert_eq!(v["1.8.9"]["seconds"], 100);
        assert_eq!(v["26.2"]["seconds"], 93);
        assert_eq!(v["26.2"]["sessions"], 3);
        assert_eq!(v["26.2"]["last"], 3000);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn corrupt_file_is_kept_aside() {
        let dir = std::env::temp_dir().join(format!("s1mp1e-playtime-c-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("playtime.json");
        std::fs::write(&p, "{ not json").unwrap();
        record_at(&p, "26.2", 60, 1000);
        assert!(dir.join("playtime.json.corrupt").exists());
        let v: Value = serde_json::from_str(&std::fs::read_to_string(&p).unwrap()).unwrap();
        assert_eq!(v["26.2"]["seconds"], 60);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
