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
}

fn d_ram() -> u32 { 4096 }
fn d_after() -> String { "hide".into() }
fn d_name() -> String { "Player".into() }
fn d_accent() -> String { "#0a84ff".into() }
fn d_true() -> bool { true }
fn d_ver() -> String { "26.2".into() }
fn d_loader() -> String { "fabric".into() }
fn d_theme() -> String { "auto".into() }

impl Default for Settings {
    fn default() -> Self {
        Settings {
            ram_mb: d_ram(), after_launch: d_after(), mc_path: String::new(),
            offline_name: d_name(), accent: d_accent(), glass: d_true(),
            reduce_transparency: false, version: d_ver(), loader: d_loader(),
            theme: d_theme(),
            jvm_args: String::new(), res_width: 0, res_height: 0, java_path: String::new(),
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
}

pub fn config_path() -> PathBuf {
    let base = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
    base.join("S1mp1e").join("config.json")
}

pub fn load() -> Config {
    let p = config_path();
    let Ok(s) = std::fs::read_to_string(&p) else {
        return Config::default();
    };
    match serde_json::from_str(&s) {
        Ok(c) => c,
        Err(_) => {
            // Don't silently discard a corrupt config (that would wipe the account +
            // settings) — keep a copy for recovery, then start fresh.
            let _ = std::fs::rename(&p, p.with_extension("json.corrupt"));
            Config::default()
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
    let tmp = p.with_extension("json.tmp");
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
