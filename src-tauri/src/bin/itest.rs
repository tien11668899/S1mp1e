//! `itest` — the CLI the Avalonia launcher UI spawns for real work. Subcommands:
//!
//!   itest login   <clientId>                    Microsoft device-code sign-in.
//!   itest play    <mc> <loader> <mcPath> <name> Launch (online if signed in).
//!   itest install fabric <mc> [mcPath]          Install a Fabric profile.
//!
//! `login` prints `CODE <user_code>\t<verification_uri>` the instant it has a device
//! code (the UI shows/opens it), then `DONE <name>\t<uuid>` and SAVES the account to
//! %APPDATA%/S1mp1e/config.json — the same file the UI reads. `play` then resolves
//! that saved account (refreshing silently) so Minecraft launches with a genuine MSA
//! session (user_type "msa") that online servers accept — fixing the "shell account"
//! where launch always used the offline placeholder.

use s1mp1e::{auth, config, install, launch, paths};
use std::io::Write;
use std::sync::Arc;

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let sub = args.get(1).map(String::as_str).unwrap_or("");
    let rest = &args[args.len().min(2)..];
    let code = match sub {
        "login" => cmd_login(rest.first().cloned().unwrap_or_default()).await,
        "play" => cmd_play(rest).await,
        "install" => cmd_install(rest).await,
        _ => {
            eprintln!("usage: itest <login|play|install> ...");
            2
        }
    };
    std::process::exit(code);
}

/// Microsoft device-code sign-in. Streams CODE/DONE lines the UI parses.
async fn cmd_login(client_id: String) -> i32 {
    let out = std::io::stdout();
    match auth::sign_in(&client_id, |dc| {
        // Emit as soon as we have a code so the UI can show it + open the page.
        let mut o = std::io::stdout();
        let _ = writeln!(o, "CODE {}\t{}", dc.user_code, dc.verification_uri);
        let _ = o.flush();
    })
    .await
    {
        Ok(account) => {
            let mut o = out.lock();
            let _ = writeln!(o, "DONE {}\t{}", account.name, account.uuid);
            let _ = o.flush();
            let mut c = config::load();
            c.account = Some(account);
            if let Err(e) = config::save(&c) {
                eprintln!("儲存帳號失敗：{e}");
                return 1;
            }
            0
        }
        Err(e) => {
            eprintln!("{e:#}");
            1
        }
    }
}

/// Resolve the identity for launch: reuse/refresh the signed-in MSA account, else
/// offline. Mirrors the Tauri app's resolve_auth so CLI + GUI behave identically.
async fn resolve_auth(offline_name: &str) -> launch::AuthInfo {
    let cfg = config::load();
    if let Some(acc) = cfg.account {
        if acc.mc_expires_at > config::now_secs() + 60 {
            return launch::AuthInfo {
                name: acc.name,
                uuid: acc.uuid,
                token: acc.mc_token,
                user_type: "msa".into(),
            };
        }
        if let Ok(fresh) = auth::refresh(&cfg.client_id, &acc.msa_refresh).await {
            let ai = launch::AuthInfo {
                name: fresh.name.clone(),
                uuid: fresh.uuid.clone(),
                token: fresh.mc_token.clone(),
                user_type: "msa".into(),
            };
            let mut c = config::load();
            c.account = Some(fresh);
            let _ = config::save(&c);
            return ai;
        }
    }
    launch::AuthInfo::offline(offline_name)
}

/// The installed profile id to launch for (mc, loader): newest Fabric/Forge profile
/// for that base MC, else the bare vanilla id.
fn resolve_version_id(root: &std::path::PathBuf, mc: &str, loader: &str) -> Option<String> {
    let want_fabric = loader.eq_ignore_ascii_case("fabric");
    let fabric_suffix = format!("-{mc}");
    let forge_prefix = format!("{mc}-forge");
    let mut fabric: Vec<String> = Vec::new();
    let mut forge: Vec<String> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(paths::versions_dir(root)) {
        for e in rd.flatten() {
            let id = e.file_name().to_string_lossy().to_string();
            if !paths::version_json(root, &id).exists() {
                continue;
            }
            if id.to_lowercase().starts_with("fabric-loader") && id.ends_with(&fabric_suffix) {
                fabric.push(id);
            } else if id.starts_with(&forge_prefix) {
                forge.push(id);
            }
        }
    }
    fabric.sort();
    forge.sort();
    if want_fabric {
        if let Some(f) = fabric.pop() {
            return Some(f);
        }
    } else if let Some(f) = forge.pop() {
        return Some(f);
    }
    // Fall back to a vanilla profile of that id if it exists.
    if paths::version_json(root, mc).exists() {
        Some(mc.to_string())
    } else {
        None
    }
}

fn silent_emit() -> install::Emit {
    // The UI just streams our stdout; surface install steps as plain lines.
    Arc::new(|p: install::Progress| {
        let mut o = std::io::stdout();
        let _ = writeln!(o, "[install] {}", serde_json::to_string(&p).unwrap_or_default());
        let _ = o.flush();
    })
}

/// itest play <mc> <loader> <mcPath> <name>
async fn cmd_play(a: &[String]) -> i32 {
    let mc = a.first().cloned().unwrap_or_default();
    let loader = a.get(1).cloned().unwrap_or_else(|| "fabric".into());
    let mc_path = a.get(2).filter(|s| !s.is_empty()).cloned();
    let name = a.get(3).cloned().unwrap_or_else(|| "Player".into());
    if mc.is_empty() {
        eprintln!("play 需要 <mc>");
        return 2;
    }
    let root = paths::mc_root(mc_path.as_deref());

    // Find the installed Fabric/Forge profile; if fabric is wanted but missing,
    // install it on demand so PLAY always launches a modded (glass) profile.
    let id = match resolve_version_id(&root, &mc, &loader) {
        Some(id) => id,
        None if loader.eq_ignore_ascii_case("fabric") => {
            match install::install_fabric(root.clone(), mc.clone(), silent_emit()).await {
                Ok(id) => id,
                Err(e) => {
                    eprintln!("安裝 Fabric 失敗：{e:#}");
                    return 1;
                }
            }
        }
        None => {
            eprintln!("找不到已安裝的 {mc} 設定檔");
            return 1;
        }
    };

    if let Err(e) = install::ensure_java(root.clone(), id.clone(), silent_emit()).await {
        eprintln!("Java 準備失敗：{e:#}");
        return 1;
    }
    if let Err(e) = install::ensure_libraries(root.clone(), id.clone(), silent_emit()).await {
        eprintln!("函式庫準備失敗：{e:#}");
        return 1;
    }

    let auth_info = resolve_auth(&name).await;
    let ram = config::load().settings.ram_mb;
    let plan = match launch::plan_launch(&root, &id, &auth_info, ram) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("啟動規劃失敗：{e:#}");
            return 1;
        }
    };
    launch::run_blocking(plan, |line| {
        let mut o = std::io::stdout();
        let _ = writeln!(o, "{line}");
        let _ = o.flush();
    })
    .unwrap_or(-1)
}

/// itest install fabric <mc> [mcPath]
async fn cmd_install(a: &[String]) -> i32 {
    let kind = a.first().map(String::as_str).unwrap_or("");
    let mc = a.get(1).cloned().unwrap_or_default();
    let mc_path = a.get(2).filter(|s| !s.is_empty()).cloned();
    if mc.is_empty() {
        eprintln!("install 需要 <mc>");
        return 2;
    }
    let root = paths::mc_root(mc_path.as_deref());
    let res = match kind {
        "fabric" => install::install_fabric(root, mc, silent_emit()).await.map(|id| id),
        "forge" => install::install_forge(root, mc, silent_emit()).await,
        other => {
            eprintln!("未知的 install 類型：{other}");
            return 2;
        }
    };
    match res {
        Ok(id) => {
            println!("DONE {id}");
            0
        }
        Err(e) => {
            eprintln!("{e:#}");
            1
        }
    }
}
