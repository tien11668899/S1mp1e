//! `itest` — the CLI the Avalonia launcher UI spawns for real work. Subcommands:
//!
//!   itest login   <clientId>                    Microsoft device-code sign-in.
//!   itest play    <mc> <loader> <mcPath> <name> Launch (online if signed in).
//!   itest install fabric <mc> [mcPath]          Install a Fabric profile.
//!   itest default-mods <mc> [mcPath]            Install the default Fabric mods for <mc>.
//!   itest perf <mc> [mcPath]                    Prepare the performance pack and print what a Fabric launch would use.
//!   itest plan <mc> <loader> [mcPath]           Print the launch command (offline identity) as JSON, don't start.
//!   itest forge-deps <mc> [mcPath]              Fetch the libraries the Forge mods for <mc> need.
//!   itest options-import <mcPath> <from> <to>   Copy one version's game settings over another's (prints OK <n> <mods> / ERR <msg>).
//!   itest world-convert <mcPath> <worldFolder> <version>   A converted copy of a world (tools/worldconv on 26.3's classpath).
//!
//! `login` prints `CODE <user_code>\t<verification_uri>` the instant it has a device
//! code (the UI shows/opens it), then `DONE <name>\t<uuid>` and SAVES the account to
//! %APPDATA%/S1mp1e/config.json — the same file the UI reads. `play` then resolves
//! that saved account (refreshing silently) so Minecraft launches with a genuine MSA
//! session (user_type "msa") that online servers accept — fixing the "shell account"
//! where launch always used the offline placeholder.

use s1mp1e::{auth, config, default_mods, download, forge_deps, install, launch, media, meta, options_sync, ornithe, paths, perf, playtime};
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
        "default-mods" => cmd_default_mods(rest).await,
        "perf" => cmd_perf(rest).await,
        "plan" => cmd_plan(rest),
        "forge-deps" => cmd_forge_deps(rest).await,
        "list-versions" => cmd_list_versions().await,
        "refresh" => cmd_refresh().await,
        "media-serve" => cmd_media_serve(),
        "options-import" => cmd_options_import(rest),
        "world-convert" => cmd_world_convert(rest),
        "whoami" => {
            // Diagnostic: what identity would `play` launch with?
            let ai = resolve_auth("Player").await;
            println!("user_type={} name={} uuid={}", ai.user_type, ai.name, ai.uuid);
            if ai.user_type == "msa" { 0 } else { 1 }
        }
        _ => {
            eprintln!("usage: itest <login|refresh|play|install|default-mods|list-versions|whoami> ...");
            2
        }
    };
    std::process::exit(code);
}

/// `options-import <mcPath> <from> <to>`: the launcher's 「從其他版本匯入設定」. Prints `OK <game settings changed> <mod settings changed>`
/// or `ERR <message>`.
fn cmd_options_import(rest: &[String]) -> i32 {
    let (Some(root), Some(from), Some(to)) = (rest.first(), rest.get(1), rest.get(2)) else {
        println!("ERR usage: itest options-import <mcPath> <from> <to>");
        return 2;
    };
    match options_sync::import(std::path::Path::new(root), from, to) {
        Ok((n, mods)) => { println!("OK {n} {mods}"); 0 }
        Err(e) => { println!("ERR {e}"); 1 }
    }
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
            // Persist the account BEFORE announcing DONE. The UI reacts to the
            // "DONE" line by immediately calling ConfigStore.Load() — opening
            // config.json for read. If that read handle is open at the instant
            // our atomic save renames config.json.tmp over config.json, Windows
            // fails the rename with ERROR_ACCESS_DENIED (os error 5) and the
            // freshly signed-in account is dropped (the launcher then shows
            // "登入失敗" even though auth fully succeeded). Saving first closes
            // the race: the UI only ever reads a file we have finished writing.
            // Read-modify-write: a config that exists but cannot be read right now must not be replaced by defaults
            // (that would delete every other account and all settings).
            let mut c = match config::load_for_update() {
                Ok(c) => c,
                Err(e) => {
                    eprintln!("讀取設定失敗，帳號沒有儲存：{e}");
                    return 1;
                }
            };
            // Keep accounts[] in step with the active account. The UI's switcher lists
            // this array, and its logout path falls back to accounts[0] — with the array
            // never populated, signing in then logging out cleared the account outright.
            // Signing in again with a saved account refreshes it where it is in the list instead of moving it last.
            match c.accounts.iter().position(|a| a.uuid == account.uuid) {
                Some(i) => c.accounts[i] = account.clone(),
                None => c.accounts.push(account.clone()),
            }
            c.account = Some(account.clone());
            if let Err(e) = config::save(&c) {
                eprintln!("儲存帳號失敗：{e}");
                return 1;
            }
            let mut o = out.lock();
            let _ = writeln!(o, "DONE {}\t{}", account.name, account.uuid);
            let _ = o.flush();
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
/// True when a refresh failed because the network did (no connection, timeout, a Microsoft/Xbox server error), as
/// opposed to the account's session being rejected.
fn is_network_error(e: &anyhow::Error) -> bool {
    e.chain().any(|c| {
        c.downcast_ref::<reqwest::Error>().map_or(false, |r| {
            r.is_connect() || r.is_timeout() || r.is_request() || r.status().map_or(false, |s| s.is_server_error())
        }) || c.downcast_ref::<std::io::Error>().is_some()
    })
}

/// Store a refreshed session: the active account and its accounts[] entry (in place — the switcher lists accounts in
/// saved order, and a refresh must not reorder it). An unreadable config is not overwritten; the caller still has the
/// fresh token for what it is doing.
fn persist_refreshed(fresh: config::Account) {
    if let Ok(mut c) = config::load_for_update() {
        // Only if this account is still the active one: the player may have switched while the refresh ran.
        let still_active = c.account.as_ref().map(|a| a.uuid == fresh.uuid).unwrap_or(false);
        match c.accounts.iter().position(|a| a.uuid == fresh.uuid) {
            Some(i) => c.accounts[i] = fresh.clone(),
            None => c.accounts.push(fresh.clone()),
        }
        if still_active {
            c.account = Some(fresh);
        }
        if let Err(e) = config::save(&c) {
            eprintln!("儲存刷新後的登入失敗：{e}");
        }
    }
}

/// itest refresh — make sure the active account's Minecraft token is valid for at least five more minutes (the
/// launcher calls this before skin / cape changes, which use the token directly). Prints OK, NO_ACCOUNT,
/// AUTH_OFFLINE (network) or AUTH_EXPIRED (sign in again).
async fn cmd_refresh() -> i32 {
    let cfg = config::load();
    let Some(acc) = cfg.account else {
        println!("NO_ACCOUNT");
        return 1;
    };
    if acc.mc_expires_at > config::now_secs() + 300 {
        println!("OK");
        return 0;
    }
    match auth::refresh(&cfg.client_id, &acc.msa_refresh).await {
        Ok(fresh) => {
            persist_refreshed(fresh);
            println!("OK");
            0
        }
        Err(e) => {
            println!("{}", if is_network_error(&e) { "AUTH_OFFLINE" } else { "AUTH_EXPIRED" });
            eprintln!("MSA session refresh failed for {}: {e:#}", acc.name);
            1
        }
    }
}

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
                persist_refreshed(fresh);
                return ai;
            }
            Err(e) => {
                // DON'T silently launch offline while the UI still shows "signed in": tell the launcher why, then fall
                // back to offline so the game still opens (single-player / offline servers). A network problem is
                // temporary and the session is fine (AUTH_OFFLINE); only a rejected refresh means "sign in again"
                // (AUTH_EXPIRED) — reporting every failure as expired sent offline players to re-login for nothing.
                if is_network_error(&e) {
                    println!("AUTH_OFFLINE\t{}", acc.name);
                } else {
                    println!("AUTH_EXPIRED\t{}", acc.name);
                }
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
                // 1.8.9 的 Fabric 只認 Ornithe profile（別的啟動器留下的 LegacyFabric 1.8.9 不能用）
                if mc == ornithe::MC && !ornithe::is_ornithe_id(&id) { continue; }
                fabric.push(id);
            } else if id.starts_with(&forge_prefix) {
                forge.push(id);
            }
        }
    }
    // Newest loader by version, not by text (text order put 0.16.9 after 0.16.10).
    let fabric_ver = |id: &str| -> String {
        id.strip_prefix("fabric-loader-").unwrap_or(id).strip_suffix(suffix.as_str()).unwrap_or(id).to_string()
    };
    fabric.sort_by(|a, b| default_mods::compare_versions(&fabric_ver(a), &fabric_ver(b)));
    forge.sort_by(|a, b| default_mods::compare_versions(a.trim_start_matches(&forge_prefix), b.trim_start_matches(&forge_prefix)));
    match loader.to_lowercase().as_str() {
        "forge" => forge.pop(),
        // Only an explicit vanilla request launches the bare profile. Fabric/Forge never fall back to vanilla: that
        // silently started a game without the chosen loader, glass or mods (e.g. Forge picked while only vanilla /
        // Ornithe 1.8.9 was installed); the caller installs the requested loader instead.
        "vanilla" => paths::version_json(root, mc).exists().then(|| mc.to_string()),
        _ => fabric.pop(),
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

/// itest perf <mc> [mcPath] — prepare the performance pack for <mc> (as `play` does) and print the JVM flags and
/// pack jars a Fabric launch would use, without starting the game. Diagnostics for perf.rs.
async fn cmd_perf(a: &[String]) -> i32 {
    let mc = a.first().cloned().unwrap_or_default();
    let mc_path = a.get(1).filter(|s| !s.is_empty()).cloned();
    if mc.is_empty() {
        eprintln!("perf 需要 <mc>");
        return 2;
    }
    let root = paths::mc_root(mc_path.as_deref());
    let settings = config::load().settings;
    let jars = if settings.perf_pack { perf::ensure_pack(&root, &mc, &silent_emit()).await } else { Vec::new() };
    let st = perf::load_state(&root, &mc);
    println!("pack {} for {mc}: setting={} disabled={} ok_launches={} {}", st.revision, settings.perf_pack, st.disabled,
        st.ok_launches, if st.reason.is_empty() { String::new() } else { format!("reason: {}", st.reason) });
    for j in &jars {
        println!("jar {}", j.display());
    }
    let Some(id) = resolve_version_id(&root, &mc, "fabric") else {
        println!("no Fabric profile installed for {mc}: JVM flags not shown");
        return 0;
    };
    match launch::plan_launch(&root, &id, &launch::AuthInfo::offline("Player"), &settings) {
        Ok(plan) => {
            for arg in &plan.args {
                if arg.starts_with("-X") || arg.starts_with("-XX:") {
                    println!("jvm {arg}");
                } else if let Some(m) = arg.strip_prefix("-Dfabric.addMods=") {
                    for p in m.split(';') {
                        println!("mod {p}");
                    }
                }
            }
            println!("java {}", plan.java_exe.display());
            0
        }
        Err(e) => {
            eprintln!("{e:#}");
            1
        }
    }
}

/// itest forge-deps <mc> [mcPath] — fetch the libraries the Forge mods for <mc> need (what `play`
/// does on a Forge launch, forge_deps.rs). Prints one `ADDED <file>` per jar, then `DONE <mc>`.
async fn cmd_forge_deps(a: &[String]) -> i32 {
    let mc = a.first().cloned().unwrap_or_default();
    let mc_path = a.get(1).filter(|s| !s.is_empty()).cloned();
    if mc.is_empty() {
        eprintln!("forge-deps 需要 <mc>");
        return 2;
    }
    let root = paths::mc_root(mc_path.as_deref());
    match forge_deps::ensure_forge_deps(&root, &mc).await {
        Ok(added) => {
            for n in added {
                println!("ADDED {n}");
            }
            println!("DONE {mc}");
            0
        }
        Err(e) => {
            eprintln!("{e:#}");
            1
        }
    }
}

/// itest plan <mc> <loader> [mcPath] — print the launch an installed profile would get, as JSON
/// `{java, cwd, args}`, without starting the game. Always the OFFLINE identity ("Player"): no
/// account is read, so the output carries no token and can be pasted into a bug report. Like
/// `play`, it rebuilds the instance's mods/ folder (that folder is launcher-owned).
fn cmd_plan(a: &[String]) -> i32 {
    let mc = a.first().cloned().unwrap_or_default();
    let loader = a.get(1).cloned().unwrap_or_else(|| "fabric".into());
    let mc_path = a.get(2).filter(|s| !s.is_empty()).cloned();
    if mc.is_empty() {
        eprintln!("plan 需要 <mc>");
        return 2;
    }
    let root = paths::mc_root(mc_path.as_deref());
    let Some(id) = resolve_version_id(&root, &mc, &loader) else {
        eprintln!("{mc} 沒有安裝 {loader} 設定檔");
        return 1;
    };
    let settings = config::load().settings;
    match launch::plan_launch(&root, &id, &launch::AuthInfo::offline("Player"), &settings) {
        Ok(plan) => {
            let out = serde_json::json!({
                "java": plan.java_exe.to_string_lossy(),
                "cwd": plan.cwd.to_string_lossy(),
                "args": plan.args,
            });
            println!("{out}");
            0
        }
        Err(e) => {
            eprintln!("{e:#}");
            1
        }
    }
}

fn is_fabric_loader(loader: &str) -> bool {
    !loader.eq_ignore_ascii_case("forge")
}

/// itest default-mods <mc> [mcPath] — install the default Fabric mods for one version now
/// (what `play` does on a Fabric launch). Prints `DONE <mc>`.
async fn cmd_default_mods(a: &[String]) -> i32 {
    let mc = a.first().cloned().unwrap_or_default();
    let mc_path = a.get(1).filter(|s| !s.is_empty()).cloned();
    if mc.is_empty() {
        eprintln!("default-mods 需要 <mc>");
        return 2;
    }
    let root = paths::mc_root(mc_path.as_deref());
    match default_mods::ensure_default_mods(&root, &mc, &silent_emit()).await {
        Ok(()) => {
            println!("DONE {mc}");
            0
        }
        Err(e) => {
            eprintln!("{e:#}");
            1
        }
    }
}

/// itest play <mc> <loader> <mcPath> <name>
/// Game args that connect straight to a server: 1.20+ (and 26.x) take `--quickPlayMultiplayer host:port`;
/// older versions take `--server host --port port`.
fn join_args(mc: &str, addr: &str) -> Vec<String> {
    let mut nums = mc.split('.').map(|p| p.parse::<u32>().unwrap_or(0));
    let (major, minor) = (nums.next().unwrap_or(0), nums.next().unwrap_or(0));
    if major > 1 || minor >= 20 {
        return vec!["--quickPlayMultiplayer".into(), addr.to_string()];
    }
    let (host, port) = match addr.rsplit_once(':') {
        Some((h, p)) if !h.contains(':') && p.parse::<u16>().is_ok() => (h.to_string(), p.to_string()),
        _ => (addr.to_string(), "25565".to_string()),
    };
    vec!["--server".into(), host, "--port".into(), port]
}

#[cfg(test)]
mod join_tests {
    use super::join_args;
    #[test]
    fn quick_play_on_modern_versions() {
        assert_eq!(join_args("26.2", "mc.example.com"), vec!["--quickPlayMultiplayer", "mc.example.com"]);
        assert_eq!(join_args("1.20.1", "a.b:25570"), vec!["--quickPlayMultiplayer", "a.b:25570"]);
    }
    #[test]
    fn server_port_on_old_versions() {
        assert_eq!(join_args("1.8.9", "a.b:25570"), vec!["--server", "a.b", "--port", "25570"]);
        assert_eq!(join_args("1.12.2", "hypixel.net"), vec!["--server", "hypixel.net", "--port", "25565"]);
    }
}

/// An exclusive lock on one version's files for as long as the returned handle lives (the OS releases it if this
/// process dies). Taken by `play` and `install`, so an install and a launch of the same version — or two launches —
/// never write the same libraries, natives or instance mods folder at once.
fn version_lock(root: &std::path::Path, mc: &str) -> Result<std::fs::File, String> {
    let dir = root.join("instances").join(mc);
    let _ = std::fs::create_dir_all(&dir);
    let mut opts = std::fs::OpenOptions::new();
    opts.create(true).write(true);
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        opts.share_mode(0);
    }
    opts.open(dir.join(".s1mp1e.lock"))
        .map_err(|_| format!("{mc} 正在安裝或執行中（另一個啟動或安裝還沒結束），請等它完成再試"))
}

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
    let _lock = match version_lock(&root, &mc) {
        Ok(l) => l,
        Err(msg) => {
            eprintln!("{msg}");
            return 1;
        }
    };

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

    // An install that never finished (network drop, launcher closed) is repaired here instead of failing on every
    // Play: missing client jar / assets / libraries / Java are fetched, present ones only re-verified once.
    if !install::is_complete(&root, &id) {
        if let Err(e) = install::repair_version(root.clone(), id.clone(), silent_emit()).await {
            eprintln!("版本檔案不完整，修復失敗：{e:#}");
            return 1;
        }
    }
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
    // Fetch the glass jar from the repo on a fresh machine, and refresh it whenever the
    // published one changes (ETag), so players actually receive in-game glass updates.
    let is_ornithe = ornithe::is_ornithe(&mc, &loader);
    if is_ornithe {
        // Ornithe 1.8.9：Ornithe 版玻璃、Forge 相容層（Forge 模組靠它載入，和玻璃開關無關）、Pylon/OSL/Argentum
        if settings.glass {
            let _ = install::ensure_glass(&root, ornithe::GLASS_KEY, &silent_emit()).await;
        }
        let _ = install::ensure_glass(&root, ornithe::FORGECOMPAT_KEY, &silent_emit()).await;
        if let Err(e) = ornithe::ensure_stack(&root, &silent_emit()).await {
            eprintln!("Ornithe 模組準備失敗：{e:#}");
            return 1;
        }
    } else if settings.glass {
        let _ = install::ensure_glass(&root, &mc, &silent_emit()).await;
    }
    // Fabric profiles get the default mod set (Fabric API, Sodium, MaLiLib, Item Scroller,
    // Entity Culling) once per version — also on installs an earlier launcher made.
    // （Ornithe 1.8.9 沒有：Modrinth 上 1.8.9 的「fabric」是 LegacyFabric，對 Ornithe 不相容）
    if settings.default_mods && is_fabric_loader(&loader) && !is_ornithe {
        let _ = default_mods::ensure_default_mods(&root, &mc, &silent_emit()).await;
    } else if settings.glass && is_fabric_loader(&loader) && !is_ornithe {
        // default set switched off: still give liquid glass its one hard prerequisite
        let _ = default_mods::ensure_fabric_api(&root, &mc, &silent_emit()).await;
    }
    // Forge has no dependency resolution for coremods: a mod whose coremod needs a library
    // that is not installed (MixinBooter on 1.12.2) makes the game exit before its window
    // opens. Fetch what the installed mods need (forge_deps.rs). Never fatal.
    if !is_fabric_loader(&loader) {
        match forge_deps::ensure_forge_deps(&root, &mc).await {
            Ok(added) => {
                for n in added {
                    println!("[install] 已補上模組需要的前置：{n}");
                }
            }
            Err(e) => eprintln!("檢查 Forge 前置模組失敗（照常啟動）：{e:#}"),
        }
    }
    // Performance pack: verify / fetch the measured optimisation mods for this version
    // (perf.rs). Skipped entirely when the setting is off or after a crash switched it off.
    let pack_on = if settings.perf_pack && !is_ornithe {
        let jars = if is_fabric_loader(&loader) { perf::ensure_pack(&root, &mc, &silent_emit()).await } else { Vec::new() };
        !perf::load_state(&root, &mc).disabled && (!jars.is_empty() || perf::has_jvm_flags())
    } else {
        false
    };
    let mut plan = match launch::plan_launch(&root, &id, &auth_info, &settings) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("啟動規劃失敗：{e:#}");
            return 1;
        }
    };
    // 啟動器「伺服器」頁的「加入」：開遊戲後直接連進伺服器
    if let Some(addr) = a.iter().position(|s| s == "--join").and_then(|i| a.get(i + 1)) {
        plan.args.extend(join_args(&mc, addr));
    }
    // 遊戲內靈動島的「正在播放」服務（Windows 系統媒體控制）：只在遊戲執行期間、只綁 127.0.0.1
    if let Some(ms) = media::start() {
        insert_jvm_arg(&mut plan, ms.jvm_arg());
    }
    let gamedir = plan.cwd.clone();
    if pack_on {
        perf::mark_launch(&root, &mc);
    }
    // The game's own error stream goes to ours, so the launcher sees JVM start-up errors as errors; a failure to start
    // the game at all is printed with its reason instead of a bare -1 the UI can only call "no error output".
    // Play time counts from the moment the game window comes up (the same log markers the launcher UI watches), not
    // from the JVM start, and is recorded here so it survives the launcher window being closed during play.
    let running_since: Arc<std::sync::OnceLock<std::time::Instant>> = Arc::new(std::sync::OnceLock::new());
    let rs = running_since.clone();
    let code = match launch::run_blocking(plan, move |line, is_err| {
        if rs.get().is_none() && (line.contains("LWJGL") || line.contains("Setting user")) {
            let _ = rs.set(std::time::Instant::now());
        }
        // The launcher may be gone by now (after-launch "close"): writing to a closed pipe must not stop us.
        if is_err {
            let mut e = std::io::stderr();
            let _ = writeln!(e, "{line}");
            let _ = e.flush();
        } else {
            let mut o = std::io::stdout();
            let _ = writeln!(o, "{line}");
            let _ = o.flush();
        }
    }) {
        Ok(c) => c,
        Err(e) => {
            let _ = writeln!(std::io::stderr(), "無法啟動遊戲：{e:#}");
            -1
        }
    };
    if let Some(t) = running_since.get() {
        playtime::record(&mc, t.elapsed().as_secs());
    }
    if pack_on {
        if let Some(why) = perf::judge_exit(&root, &mc, &gamedir, code) {
            let _ = writeln!(std::io::stderr(), "效能套件已暫停（下次啟動不載入）：{why}");
        }
    }
    code
}

/// 在主類別名稱前插入一個 JVM 參數（-D 系統屬性要在主類別之前）
fn insert_jvm_arg(plan: &mut launch::LaunchPlan, arg: String) {
    let i = plan.args.iter().position(|a| *a == plan.main_class).unwrap_or(0);
    plan.args.insert(i, arg);
}

/// itest media-serve — 開發用：開「正在播放」服務並印出給遊戲的 JVM 參數，一直執行到被關掉。
fn cmd_media_serve() -> i32 {
    match media::start() {
        Some(ms) => {
            println!("{}", ms.jvm_arg());
            let _ = std::io::stdout().flush();
            loop {
                std::thread::sleep(std::time::Duration::from_secs(3600));
            }
        }
        None => {
            eprintln!("無法啟動媒體服務");
            1
        }
    }
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
    let _lock = match version_lock(&root, &mc) {
        Ok(l) => l,
        Err(msg) => {
            eprintln!("{msg}");
            return 1;
        }
    };
    let res = match kind {
        "fabric" => {
            let res = install::install_fabric(root.clone(), mc.clone(), silent_emit()).await;
            let st = config::load().settings;
            if res.is_ok() && mc != ornithe::MC {
                if st.default_mods {
                    let _ = default_mods::ensure_default_mods(&root, &mc, &silent_emit()).await;
                } else if st.glass {
                    let _ = default_mods::ensure_fabric_api(&root, &mc, &silent_emit()).await;
                }
            }
            res
        }
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

/// The world converter's jar: next to this exe when installed, tools/worldconv/build/ in a source checkout.
fn worldconv_jar() -> Option<std::path::PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let dir = exe.parent()?;
    let installed = dir.join("worldconv.jar");
    if installed.exists() {
        return Some(installed);
    }
    let mut d = dir.to_path_buf();
    for _ in 0..5 {
        let dev = d.join("tools").join("worldconv").join("build").join("worldconv.jar");
        if dev.exists() {
            return Some(dev);
        }
        d = d.parent()?.to_path_buf();
    }
    None
}

/// `world-convert <mcPath> <worldFolder> <version>`: the launcher's 「轉換版本」. Copies `saves/<worldFolder>` to
/// `saves/<worldFolder> (<version>)` (a free name) and converts the copy with tools/worldconv, which runs on the
/// installed 26.3's classpath (its DataFixerUpper knows every older format). The original world is never touched.
/// Streams the converter's `PROGRESS done total` lines, then prints `OUT <folder>` and `REPORT <dir>`, or `ERR <msg>`.
fn cmd_world_convert(rest: &[String]) -> i32 {
    let (Some(root), Some(folder), Some(version)) = (rest.first(), rest.get(1), rest.get(2)) else {
        println!("ERR usage: itest world-convert <mcPath> <worldFolder> <version>");
        return 2;
    };
    let root = std::path::PathBuf::from(root);
    let Some(id) = resolve_version_id(&root, "26.3", "vanilla") else {
        println!("ERR 轉換世界需要先安裝 26.3（在「開始遊戲」選 26.3 下載一次）");
        return 1;
    };
    let settings = config::load().settings;
    let plan = match launch::plan_launch(&root, &id, &launch::AuthInfo::offline("Player"), &settings) {
        Ok(p) => p,
        Err(e) => {
            println!("ERR 無法準備 26.3：{e}");
            return 1;
        }
    };
    let Some(cp) = plan.args.iter().position(|a| a == "-cp").and_then(|i| plan.args.get(i + 1)).cloned() else {
        println!("ERR 26.3 的啟動設定沒有 classpath");
        return 1;
    };
    let Some(jar) = worldconv_jar() else {
        println!("ERR 找不到世界轉換元件 worldconv.jar，請重新安裝啟動器");
        return 1;
    };
    let saves = root.join("saves");
    let src = saves.join(folder);
    if !src.join("level.dat").exists() {
        println!("ERR 找不到世界「{folder}」");
        return 1;
    }
    let mut name = format!("{folder} ({version})");
    let mut n = 2;
    while saves.join(&name).exists() {
        name = format!("{folder} ({version}) {n}");
        n += 1;
    }
    let java = {
        let j = plan.java_exe.to_string_lossy().to_string();
        std::path::PathBuf::from(if j.ends_with("javaw.exe") { j.trim_end_matches("javaw.exe").to_string() + "java.exe" } else { j })
    };
    // not with_extension: a folder name like "World (1.21.1)" would lose ".1)" as its "extension"
    let log = saves.join(format!("{name}.worldconv.log"));
    let mut cmd = std::process::Command::new(&java);
    cmd.arg("-Xmx4G")
        .arg("-cp")
        .arg(format!("{cp};{}", jar.display()))
        .arg("dev.s1mp1e.worldconv.Main")
        .arg("convert")
        .arg(&src)
        .arg(saves.join(&name))
        .arg(version)
        .stdout(std::process::Stdio::piped());
    if let Ok(f) = std::fs::File::create(&log) {
        cmd.stderr(f);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            println!("ERR 無法啟動 Java：{e}");
            return 1;
        }
    };
    let mut ok = false;
    if let Some(out) = child.stdout.take() {
        use std::io::BufRead;
        for line in std::io::BufReader::new(out).lines().map_while(Result::ok) {
            if line.starts_with("PROGRESS ") || line.starts_with("CONVERT ") {
                println!("{line}");
                let _ = std::io::stdout().flush();
            } else if let Some(dir) = line.strip_prefix("DONE ") {
                ok = true;
                println!("OUT {name}");
                println!("REPORT {dir}");
            } else if line.starts_with("ERR ") {
                println!("{line}");
            }
        }
    }
    let status = child.wait().map(|s| s.success()).unwrap_or(false);
    if ok && status {
        let _ = std::fs::remove_file(&log);
        0
    } else {
        // a failed conversion leaves no half-written world behind (the original was never touched)
        let _ = std::fs::remove_dir_all(saves.join(&name));
        if !ok {
            println!("ERR 轉換失敗（詳細記錄：{}）", log.display());
        }
        1
    }
}
