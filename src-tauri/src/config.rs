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
}

fn d_ram() -> u32 { 4096 }
fn d_after() -> String { "hide".into() }
fn d_name() -> String { "Player".into() }
fn d_accent() -> String { "#0a84ff".into() }
fn d_true() -> bool { true }
fn d_ver() -> String { "26.2".into() }
fn d_loader() -> String { "fabric".into() }

impl Default for Settings {
    fn default() -> Self {
        Settings {
            ram_mb: d_ram(), after_launch: d_after(), mc_path: String::new(),
            offline_name: d_name(), accent: d_accent(), glass: d_true(),
            reduce_transparency: false, version: d_ver(), loader: d_loader(),
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
    std::fs::read_to_string(&p)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save(cfg: &Config) -> std::io::Result<()> {
    let p = config_path();
    if let Some(parent) = p.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&p, serde_json::to_string_pretty(cfg).unwrap())
}

pub fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}
