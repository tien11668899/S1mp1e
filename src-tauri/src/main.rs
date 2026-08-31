// S1mp1e launcher.
// L1: frameless acrylic glass window hosting the UI.
// L2: install pipeline (piston-meta + libraries + assets + Java runtime, vanilla & Fabric).
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod paths;
mod meta;
mod download;
mod install;
mod launch;
mod config;
mod auth;

use std::sync::Arc;
use tauri::{Emitter, Manager};

#[tauri::command]
fn app_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

#[derive(serde::Serialize)]
struct VersionListItem {
    id: String,
    kind: String,
    release_time: Option<String>,
}

/// Live version list from Mojang for the picker (releases first, newest first).
#[tauri::command]
async fn list_versions() -> Result<Vec<VersionListItem>, String> {
    let cl = download::client();
    let man: meta::VersionManifest = download::get_json(&cl, meta::VERSION_MANIFEST)
        .await
        .map_err(|e| e.to_string())?;
    let items = man
        .versions
        .into_iter()
        .map(|v| VersionListItem { id: v.id, kind: v.kind, release_time: v.release_time })
        .collect();
    Ok(items)
}

#[derive(serde::Serialize)]
struct InstalledItem {
    id: String,
    loader: String, // vanilla | fabric | forge | other
    has_jar: bool,
}

/// Scan `.minecraft/versions/` for installed profiles (a dir with `<id>.json`).
#[tauri::command]
fn list_installed(mc_path: Option<String>) -> Vec<InstalledItem> {
    let root = paths::mc_root(mc_path.as_deref());
    let dir = paths::versions_dir(&root);
    let mut out = Vec::new();
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for e in rd.flatten() {
            if !e.path().is_dir() { continue; }
            let id = e.file_name().to_string_lossy().to_string();
            if !paths::version_json(&root, &id).exists() { continue; }
            let low = id.to_lowercase();
            let loader = if low.contains("fabric") { "fabric" }
                else if low.contains("forge") || low.contains("neoforge") { "forge" }
                else if low.contains("quilt") { "quilt" }
                else if low.contains("optifine") { "other" }
                else { "vanilla" }.to_string();
            let has_jar = paths::version_jar(&root, &id).exists();
            out.push(InstalledItem { id, loader, has_jar });
        }
    }
    out.sort_by(|a, b| b.id.to_lowercase().cmp(&a.id.to_lowercase()));
    out
}

// ---- L5: persisted UI settings ----

/// True if this version id is already present locally (its version json exists) —
/// installed by S1mp1e or any other launcher sharing this `.minecraft`. Lets the
/// UI launch it directly instead of re-running the installer every time.
#[tauri::command]
fn is_installed(id: String, mc_path: Option<String>) -> bool {
    let root = paths::mc_root(mc_path.as_deref());
    paths::version_json(&root, &id).exists()
}

/// Newest already-installed Fabric profile (`fabric-loader-*-<mc>`) for a base MC
/// version, if any — lets PLAY launch an existing Fabric profile directly (fast,
/// glass injected) instead of re-running install_fabric or wrongly launching the
/// plain-vanilla base (which loads no mods → no liquid glass).
#[tauri::command]
fn find_fabric(mc: String, mc_path: Option<String>) -> Option<String> {
    let root = paths::mc_root(mc_path.as_deref());
    let suffix = format!("-{mc}");
    let mut hits: Vec<String> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(paths::versions_dir(&root)) {
        for e in rd.flatten() {
            let id = e.file_name().to_string_lossy().to_string();
            if id.to_lowercase().starts_with("fabric-loader")
                && id.ends_with(&suffix)
                && paths::version_json(&root, &id).exists()
            {
                hits.push(id);
            }
        }
    }
    hits.sort();
    hits.pop()
}

#[tauri::command]
fn get_settings() -> config::Settings {
    config::load().settings
}

#[tauri::command]
fn set_settings(settings: config::Settings) -> Result<(), String> {
    let mut c = config::load();
    c.settings = settings;
    config::save(&c).map_err(|e| e.to_string())
}

fn emitter(app: tauri::AppHandle) -> install::Emit {
    Arc::new(move |p: install::Progress| {
        let _ = app.emit("install:progress", p);
    })
}

/// Install a vanilla version. Progress streams on the `install:progress` event.
#[tauri::command]
async fn install_version(
    app: tauri::AppHandle,
    id: String,
    mc_path: Option<String>,
) -> Result<(), String> {
    let root = paths::mc_root(mc_path.as_deref());
    let emit = emitter(app);
    install::install_version(root, id, emit)
        .await
        .map_err(|e| format!("{e:#}"))
}

/// Newest already-installed Forge profile (`<mc>-forge…`) for a base MC version.
#[tauri::command]
fn find_forge(mc: String, mc_path: Option<String>) -> Option<String> {
    let root = paths::mc_root(mc_path.as_deref());
    let prefix = format!("{mc}-forge");
    let mut hits: Vec<String> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(paths::versions_dir(&root)) {
        for e in rd.flatten() {
            let id = e.file_name().to_string_lossy().to_string();
            if id.starts_with(&prefix) && paths::version_json(&root, &id).exists() {
                hits.push(id);
            }
        }
    }
    hits.sort();
    hits.pop()
}

/// Install legacy Forge (1.8.x–1.12.2) for a base MC version; returns the new id.
#[tauri::command]
async fn install_forge(
    app: tauri::AppHandle,
    mc: String,
    mc_path: Option<String>,
) -> Result<String, String> {
    let root = paths::mc_root(mc_path.as_deref());
    let emit = emitter(app);
    install::install_forge(root, mc, emit).await.map_err(|e| format!("{e:#}"))
}

/// Install Fabric on a base MC version; returns the new `fabric-loader-…` id.
#[tauri::command]
async fn install_fabric(
    app: tauri::AppHandle,
    mc: String,
    mc_path: Option<String>,
) -> Result<String, String> {
    let root = paths::mc_root(mc_path.as_deref());
    let emit = emitter(app);
    install::install_fabric(root, mc, emit)
        .await
        .map_err(|e| format!("{e:#}"))
}

// ---- L4: config + Microsoft sign-in ----

#[derive(serde::Serialize)]
struct ConfigView {
    client_id: String,
    account_name: Option<String>,
    account_uuid: Option<String>,
}

#[tauri::command]
fn get_config() -> ConfigView {
    let c = config::load();
    ConfigView {
        client_id: c.client_id,
        account_name: c.account.as_ref().map(|a| a.name.clone()),
        account_uuid: c.account.as_ref().map(|a| a.uuid.clone()),
    }
}

#[tauri::command]
fn set_client_id(client_id: String) -> Result<(), String> {
    let mut c = config::load();
    c.client_id = client_id.trim().to_string();
    config::save(&c).map_err(|e| e.to_string())
}

#[tauri::command]
fn logout() -> Result<(), String> {
    let mut c = config::load();
    c.account = None;
    config::save(&c).map_err(|e| e.to_string())
}

/// Interactive Microsoft sign-in. Emits `auth:code` { user_code,
/// verification_uri, expires_in } for the UI, then resolves to the profile.
#[tauri::command]
async fn msa_login(app: tauri::AppHandle) -> Result<ConfigView, String> {
    let cfg = config::load();
    let app2 = app.clone();
    let account = auth::sign_in(&cfg.client_id, move |dc| {
        let _ = app2.emit("auth:code", serde_json::json!({
            "user_code": dc.user_code,
            "verification_uri": dc.verification_uri,
            "expires_in": dc.expires_in,
        }));
    })
    .await
    .map_err(|e| format!("{e:#}"))?;

    let mut c = config::load();
    let view = ConfigView {
        client_id: c.client_id.clone(),
        account_name: Some(account.name.clone()),
        account_uuid: Some(account.uuid.clone()),
    };
    c.account = Some(account);
    config::save(&c).map_err(|e| e.to_string())?;
    Ok(view)
}

/// Resolve the identity for launch: refresh/reuse the stored MSA account, else
/// fall back to the given offline username.
async fn resolve_auth(offline_name: &str) -> launch::AuthInfo {
    let cfg = config::load();
    if let Some(acc) = cfg.account {
        // still valid?
        if acc.mc_expires_at > config::now_secs() + 60 {
            return launch::AuthInfo {
                name: acc.name, uuid: acc.uuid, token: acc.mc_token, user_type: "msa".into(),
            };
        }
        // silent refresh
        if let Ok(fresh) = auth::refresh(&cfg.client_id, &acc.msa_refresh).await {
            let mut c = config::load();
            let ai = launch::AuthInfo {
                name: fresh.name.clone(), uuid: fresh.uuid.clone(),
                token: fresh.mc_token.clone(), user_type: "msa".into(),
            };
            c.account = Some(fresh);
            let _ = config::save(&c);
            return ai;
        }
    }
    launch::AuthInfo::offline(offline_name)
}

/// Launch a resolved version. Uses the signed-in Microsoft account when present
/// (refreshing silently), otherwise offline mode. Streams `launch:log` lines and
/// fires `launch:exit` { code } when the JVM ends.
#[tauri::command]
async fn launch_version(
    app: tauri::AppHandle,
    id: String,
    username: Option<String>,
    ram_mb: Option<u32>,
    mc_path: Option<String>,
) -> Result<(), String> {
    let root = paths::mc_root(mc_path.as_deref());
    let user = username.unwrap_or_else(|| "Player".into());
    let ram = ram_mb.unwrap_or(4096);
    // Ensure the exact Java runtime this version wants is present before we plan
    // the launch. Reads the LOCAL version json (no Mojang version manifest), so it
    // works for versions S1mp1e never installed — 26.x, Forge, Feather's install —
    // and installs the runtime only when missing (idempotent, no re-download).
    let emit = emitter(app.clone());
    install::ensure_java(root.clone(), id.clone(), emit.clone())
        .await
        .map_err(|e| format!("Java 準備失敗：{e:#}"))?;
    // Complete any missing libraries (e.g. a Forge profile's launchwrapper that
    // another launcher declared but never downloaded). No-op when all present.
    install::ensure_libraries(root.clone(), id.clone(), emit)
        .await
        .map_err(|e| format!("函式庫準備失敗：{e:#}"))?;
    let auth_info = resolve_auth(&user).await;
    let glass = config::load().settings.glass;
    let plan = launch::plan_launch(&root, &id, &auth_info, ram, glass).map_err(|e| format!("{e:#}"))?;

    std::thread::spawn(move || {
        let app2 = app.clone();
        let code = launch::run_blocking(plan, move |line| {
            let _ = app2.emit("launch:log", line);
        })
        .unwrap_or(-1);
        let _ = app.emit("launch:exit", code);
    });
    Ok(())
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            app_version,
            list_versions,
            list_installed,
            is_installed,
            find_fabric,
            find_forge,
            install_forge,
            install_version,
            install_fabric,
            launch_version,
            get_config,
            set_client_id,
            logout,
            msa_login,
            get_settings,
            set_settings
        ])
        .setup(|app| {
            let window = app.get_webview_window("main").expect("main window");
            // Windows acrylic behind the transparent webview = real glass.
            #[cfg(target_os = "windows")]
            let _ = window_vibrancy::apply_acrylic(&window, Some((10, 12, 18, 160)));
            let _ = window.set_shadow(true);
            // Taskbar / Alt-Tab icon: the liquid-glass "S" (raw RGBA baked in).
            const ICON_RGBA: &[u8] = include_bytes!("../icons/icon_256.rgba");
            let _ = window.set_icon(tauri::image::Image::new(ICON_RGBA, 256, 256));
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running S1mp1e");
}
