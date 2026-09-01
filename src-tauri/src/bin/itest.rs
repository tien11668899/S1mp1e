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

use s1mp1e::{auth, config, download, install, launch, meta, paths};
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
        "list-versions" => cmd_list_versions().await,
        "whoami" => {
            // Diagnostic: what identity would `play` launch with?
            let ai = resolve_auth("Player").await;
            println!("user_type={} name={} uuid={}", ai.user_type, ai.name, ai.uuid);
            if ai.user_type == "msa" { 0 } else { 1 }
        }
        _ => {
            eprintln!("usage: itest <login|play|install|list-versions|whoami> ...");
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
        match auth::refresh(&cfg.client_id, &acc.msa_refresh).await {
            Ok(fresh) => {
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
            Err(e) => {
                // The saved MSA session is dead (refresh token expired/revoked, or a
                // transient network failure). DON'T silently launch offline while the
                // UI still shows "signed in" — emit a marker the launcher parses so it
                // can prompt a re-login, then fall back to offline so the game can still
                // open (single-player / cracked servers) instead of hard-failing.
                println!("AUTH_EXPIRED\t{}", acc.name);
                eprintln!("MSA session refresh failed for {}: {e:#}", acc.name);
            }
        }
    }
    launch::AuthInfo::offline(offline_name)
}

/// The installed profile id to launch for (mc, loader): newest Fabric/Forge profile
/// for that base MC, else the bare vanilla id.
fn resolve_version_id(root: &std::path::PathBuf, mc: &str, loader: &str) -> Option<String> {
    let suffix = format!("-{mc}");            // fabric-loader-…-<mc>
    let forge_prefix = format!("{mc}-forge");
    let mut fabric: Vec<String> = Vec::new();
    let mut forge: Vec<String> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(paths::versions_dir(root)) {
        for e in rd.flatten() {
            let id = e.file_name().to_string_lossy().to_string();
            if !paths::version_json(root, &id).exists() {
                continue;
            }
            let low = id.to_lowercase();
            if low.starts_with("fabric-loader") && id.ends_with(&suffix) {
                fabric.push(id);
            } else if id.starts_with(&forge_prefix) {
                forge.push(id);
            }
        }
    }
    fabric.sort();
    forge.sort();
    let pick = match loader.to_lowercase().as_str() {
        "forge" => forge.pop(),
        _ => fabric.pop(),
    };
    if let Some(f) = pick {
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
        None => {
            // Not installed yet → install the requested loader on demand so PLAY always
            // launches a modded (glass) profile instead of failing. Previously only
            // Fabric auto-installed, so Forge versions had to be pre-installed.
            let res = match loader.to_lowercase().as_str() {
                "forge" => install::install_forge(root.clone(), mc.clone(), silent_emit()).await,
                _ => install::install_fabric(root.clone(), mc.clone(), silent_emit()).await,
            };
            match res {
                Ok(id) => id,
                Err(e) => {
                    eprintln!("安裝 {loader} 失敗：{e:#}");
                    return 1;
                }
            }
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
    let settings = config::load().settings;
    // Fresh machine: the glass jar was never built locally → fetch it from the repo so
    // the friend who just downloaded the launcher still gets liquid glass.
    if settings.glass {
        let _ = install::ensure_glass(&root, &mc, &silent_emit()).await;
    }
    let plan = match launch::plan_launch(&root, &id, &auth_info, &settings) {
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

/// itest list-versions — print the Mojang version manifest, one per line as
/// `<id>\t<kind>\t<release_time>` (kind = release|snapshot|old_beta|…). The UI parses
/// this to populate the version picker instead of hardcoding a fixed list.
async fn cmd_list_versions() -> i32 {
    let cl = download::client();
    match download::get_json::<meta::VersionManifest>(&cl, meta::VERSION_MANIFEST).await {
        Ok(man) => {
            let out = std::io::stdout();
            let mut o = out.lock();
            for v in man.versions {
                let _ = writeln!(o, "{}\t{}\t{}", v.id, v.kind, v.release_time.unwrap_or_default());
            }
            let _ = o.flush();
            0
        }
        Err(e) => {
            eprintln!("取得版本清單失敗：{e:#}");
            1
        }
    }
}

/// itest install <fabric|forge|vanilla> <mc> [mcPath]
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
        "vanilla" => install::install_version(root, mc.clone(), silent_emit()).await.map(|_| mc.clone()),
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
