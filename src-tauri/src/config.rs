//! Launcher config + account persistence under %APPDATA%\S1mp1e\config.json.
//! Holds the user's Azure client_id (bring-your-own) and the signed-in account
//! (Minecraft token + the MSA refresh token used to silently re-auth).

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Serialize, Deserialize, Default, Clone)]
pub struct Account {
    pub uuid: String,
    pub name: String,
    pub mc_token: String,
    pub mc_expires_at: u64,       // unix secs
    pub msa_refresh: String,      // long-lived; used to refresh silently
}

/// UI-driven launcher preferences, persisted so the app reopens exactly as left.
#[derive(Serialize, Deserialize, Clone)]
pub struct Settings {
    #[serde(default = "d_ram")]     pub ram_mb: u32,
    #[serde(default = "d_after")]   pub after_launch: String, // hide | keep | close
    #[serde(default)]               pub mc_path: String,      // "" = %APPDATA%\.minecraft
    #[serde(default = "d_name")]    pub offline_name: String,
    #[serde(default = "d_accent")]  pub accent: String,       // hex, e.g. #0a84ff
    #[serde(default = "d_true")]    pub glass: bool,
    #[serde(default)]               pub reduce_transparency: bool,
    #[serde(default = "d_ver")]     pub version: String,      // last picked MC version
    #[serde(default = "d_loader")]  pub loader: String,       // fabric | forge
    #[serde(default = "d_theme")]   pub theme: String,        // auto | light | dark
    #[serde(default)]               pub jvm_args: String,     // extra JVM flags, whitespace-split
    #[serde(default)]               pub res_width: u32,       // 0 = MC default
    #[serde(default)]               pub res_height: u32,      // 0 = MC default
    #[serde(default)]               pub java_path: String,    // "" = auto-select the runtime
    #[serde(default = "d_true")]    pub default_mods: bool,   // Fabric: install the default mod set
    #[serde(default = "d_true")]    pub perf_pack: bool,      // load the measured performance pack (perf.rs)
    #[serde(default = "d_true")]    pub discrete_gpu: bool,   // prefer the high-performance GPU for the game (launch.rs)
    #[serde(default = "d_priority")] pub process_priority: String, // game process priority: normal | above_normal | high
    // UI-only fields (the launcher reads/writes them; the CLI just round-trips them so a
    // token-refresh save doesn't drop the value).
    #[serde(default)]               pub menu_key: i32,        // in-game config-GUI open key (UI-owned)
    #[serde(default)]               pub update_prerelease: bool, // mod updates include beta/alpha
    #[serde(default)]               pub cs2_knives: bool,     // show the CS2 knife-pack row on the Play page (UI-owned)
    #[serde(default = "d_true")]    pub discord_presence: bool, // show the game on the Discord profile (UI-owned)
    /// Settings this struct does not know yet (added by a newer launcher UI): kept and written back unchanged.
    #[serde(flatten)]
    pub extra: serde_json::Map<String, serde_json::Value>,
}

fn d_ram() -> u32 { 4096 }
fn d_after() -> String { "hide".into() }
fn d_name() -> String { "Player".into() }
fn d_accent() -> String { "#0a84ff".into() }
fn d_true() -> bool { true }
fn d_ver() -> String { "26.2".into() }
fn d_loader() -> String { "fabric".into() }
fn d_theme() -> String { "auto".into() }
fn d_priority() -> String { "above_normal".into() }

impl Default for Settings {
    fn default() -> Self {
        Settings {
            ram_mb: d_ram(), after_launch: d_after(), mc_path: String::new(),
            offline_name: d_name(), accent: d_accent(), glass: d_true(),
            reduce_transparency: false, version: d_ver(), loader: d_loader(),
            theme: d_theme(),
            jvm_args: String::new(), res_width: 0, res_height: 0, java_path: String::new(),
            default_mods: d_true(), perf_pack: d_true(), discrete_gpu: d_true(), process_priority: d_priority(),
            menu_key: 0, update_prerelease: false, cs2_knives: false, discord_presence: d_true(),
            extra: serde_json::Map::new(),
        }
    }
}

#[derive(Serialize, Deserialize, Default, Clone)]
pub struct Config {
    #[serde(default)]
    pub client_id: String,
    /// The currently-active account (kept for backwards compat + UI convenience).
    /// Always mirrors one of `accounts` by uuid.
    #[serde(default)]
    pub account: Option<Account>,
    /// Every account the user has ever signed into. Login appends here (deduped
    /// by uuid), the UI picks one as active by writing back `account`.
    #[serde(default)]
    pub accounts: Vec<Account>,
    #[serde(default)]
    pub settings: Settings,
    /// Every top-level key this struct does not know (the launcher UI's `downloaded_mods`, `downloaded_mod_files`,
    /// anything added later). Kept and written back, so a login or token refresh here never deletes the UI's data —
    /// losing `downloaded_mod_files` made a later mod update unable to find the old jar, and both versions loaded.
    #[serde(flatten)]
    pub extra: serde_json::Map<String, serde_json::Value>,
}

pub fn config_path() -> PathBuf {
    let base = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
    base.join("S1mp1e").join("config.json")
}

/// Settings for reading only (launching, planning): the saved config, or defaults when it is missing or cannot be
/// read. Never use the result to save — see [`load_for_update`].
pub fn load() -> Config {
    load_for_update().unwrap_or_default()
}

/// The saved config for a read-modify-write (login, token refresh). A missing file is a fresh install (defaults); a
/// file that exists but cannot be read right now (antivirus, the indexer, the launcher UI saving at the same instant)
/// is retried and then reported as an error — the caller must not save, or every account and setting would be
/// replaced by defaults. Unparseable JSON is moved aside under a timestamped name (never overwriting an earlier copy)
/// and treated as a fresh install, as before.
pub fn load_for_update() -> std::io::Result<Config> {
    let p = config_path();
    let mut last: Option<std::io::Error> = None;
    let mut text: Option<String> = None;
    for attempt in 0..6u32 {
        match std::fs::read_to_string(&p) {
            Ok(s) => { text = Some(s); break; }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Config::default()),
            Err(e) => {
                last = Some(e);
                std::thread::sleep(std::time::Duration::from_millis(50 * u64::from(attempt + 1)));
            }
        }
    }
    let Some(s) = text else {
        return Err(last.unwrap_or_else(|| std::io::Error::new(std::io::ErrorKind::Other, "config unreadable")));
    };
    match serde_json::from_str(&s) {
        Ok(c) => Ok(c),
        Err(_) => {
            // Don't silently discard a corrupt config (that would wipe the account +
            // settings) — keep a copy for recovery, then start fresh.
            let _ = std::fs::rename(&p, p.with_extension(format!("json.corrupt-{}", now_secs())));
            Ok(Config::default())
        }
    }
}

pub fn save(cfg: &Config) -> std::io::Result<()> {
    let p = config_path();
    if let Some(parent) = p.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let json = serde_json::to_string_pretty(cfg)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))?;
    // Atomic write: serialise to a temp file then rename over the real one, so a crash
    // mid-write can't leave a truncated config.json (which load() would then reset).
    // Per-process temp name: the launcher UI saves the same file through its own temp, and a shared name let one
    // writer rename the other's half-written file into place.
    let tmp = p.with_extension(format!("json.{}.tmp", std::process::id()));
    std::fs::write(&tmp, &json)?;
    // The rename is normally atomic, but on Windows it can transiently fail with
    // ERROR_ACCESS_DENIED (os error 5) or a sharing violation when another process
    // holds the destination open for even a moment — the launcher UI reloading
    // config.json, an AV scanner, or the search indexer. Retry a few times, then
    // fall back to an in-place write so a signed-in account is never lost to a
    // lost race (the whole point of saving it).
    let mut last_err = None;
    for attempt in 0..5u32 {
        match std::fs::rename(&tmp, &p) {
            Ok(()) => return Ok(()),
            Err(e) => {
                last_err = Some(e);
                std::thread::sleep(std::time::Duration::from_millis(40 * u64::from(attempt + 1)));
            }
        }
    }
    // Last resort: write config.json directly (non-atomic, but far better than
    // dropping the account). Clean up the temp either way.
    let direct = std::fs::write(&p, &json);
    let _ = std::fs::remove_file(&tmp);
    match direct {
        Ok(()) => Ok(()),
        Err(_) => Err(last_err.unwrap_or_else(|| {
            std::io::Error::new(std::io::ErrorKind::Other, "config save failed")
        })),
    }
}

pub fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod roundtrip_tests {
    use super::*;

    #[test]
    fn unknown_fields_survive_a_read_modify_write() {
        let json = r#"{
            "client_id": "x",
            "account": null,
            "accounts": [],
            "settings": { "ram_mb": 6144, "some_future_setting": [1, 2] },
            "downloaded_mods": { "AANobbMI": ["26.3"] },
            "downloaded_mod_files": { "AANobbMI@26.3": "sodium-fabric-0.9.2+mc26.3.jar" }
        }"#;
        let mut c: Config = serde_json::from_str(json).unwrap();
        c.client_id = "y".into(); // what a login does
        let out: serde_json::Value = serde_json::from_str(&serde_json::to_string(&c).unwrap()).unwrap();
        assert_eq!(out["downloaded_mods"]["AANobbMI"][0], "26.3");
        assert_eq!(out["downloaded_mod_files"]["AANobbMI@26.3"], "sodium-fabric-0.9.2+mc26.3.jar");
        assert_eq!(out["settings"]["some_future_setting"][1], 2);
        assert_eq!(out["settings"]["ram_mb"], 6144);
        assert_eq!(out["client_id"], "y");
    }
}
