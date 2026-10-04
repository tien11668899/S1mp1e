//! `itest` — the CLI the Avalonia launcher UI spawns for real work. Subcommands:
//!
//!   itest login   <clientId>                    Microsoft device-code sign-in.
//!   itest play    <mc> <loader> <mcPath> <name> Launch (online if signed in).
//!   itest install fabric <mc> [mcPath]          Install a Fabric profile.
//!   itest default-mods <mc> [mcPath]            Install the default Fabric mods for <mc>.
//!   itest perf <mc> [mcPath]                    Prepare the performance pack and print what a Fabric launch would use.
//!   itest plan <mc> <loader> [mcPath]           Print the launch command (offline identity) as JSON, don't start.
//!   itest forge-deps <mc> [mcPath]              Fetch the libraries the Forge mods for <mc> need.
//!
//! `login` prints `CODE <user_code>\t<verification_uri>` the instant it has a device
//! code (the UI shows/opens it), then `DONE <name>\t<uuid>` and SAVES the account to
//! %APPDATA%/S1mp1e/config.json — the same file the UI reads. `play` then resolves
//! that saved account (refreshing silently) so Minecraft launches with a genuine MSA
//! session (user_type "msa") that online servers accept — fixing the "shell account"
//! where launch always used the offline placeholder.

use s1mp1e::{auth, config, default_mods, download, forge_deps, install, launch, meta, ornithe, paths, perf};
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
        "whoami" => {
            // Diagnostic: what identity would `play` launch with?
            let ai = resolve_auth("Player").await;
            println!("user_type={} name={} uuid={}", ai.user_type, ai.name, ai.uuid);
            if ai.user_type == "msa" { 0 } else { 1 }
        }
        _ => {
            eprintln!("usage: itest <login|play|install|default-mods|list-versions|whoami> ...");
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
            // Persist the account BEFORE announcing DONE. The UI reacts to the
            // "DONE" line by immediately calling ConfigStore.Load() — opening
            // config.json for read. If that read handle is open at the instant
            // our atomic save renames config.json.tmp over config.json, Windows
            // fails the rename with ERROR_ACCESS_DENIED (os error 5) and the
            // freshly signed-in account is dropped (the launcher then shows
            // "登入失敗" even though auth fully succeeded). Saving first closes
            // the race: the UI only ever reads a file we have finished writing.
            let mut c = config::load();
            // Keep accounts[] in step with the active account. The UI's switcher lists
            // this array, and its logout path falls back to accounts[0] — with the array
            // never populated, signing in then logging out cleared the account outright.
            c.accounts.retain(|a| a.uuid != account.uuid);
            c.accounts.push(account.clone());
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
                // Mirror the refreshed token into accounts[] too, or a later switch
                // would hand back the stale entry we just replaced.
                c.accounts.retain(|a| a.uuid != fresh.uuid);
                c.accounts.push(fresh.clone());
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
                // 1.8.9 的 Fabric 只認 Ornithe profile（別的啟動器留下的 LegacyFabric 1.8.9 不能用）
                if mc == ornithe::MC && !ornithe::is_ornithe_id(&id) { continue; }
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
    let plan = match launch::plan_launch(&root, &id, &auth_info, &settings) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("啟動規劃失敗：{e:#}");
            return 1;
        }
    };
    let gamedir = plan.cwd.clone();
    if pack_on {
        perf::mark_launch(&root, &mc);
    }
    let code = launch::run_blocking(plan, |line| {
        let mut o = std::io::stdout();
        let _ = writeln!(o, "{line}");
        let _ = o.flush();
    })
    .unwrap_or(-1);
    if pack_on {
        if let Some(why) = perf::judge_exit(&root, &mc, &gamedir, code) {
            eprintln!("效能套件已暫停（下次啟動不載入）：{why}");
        }
    }
    code
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
        "fabric" => {
            let res = install::install_fabric(root.clone(), mc.clone(), silent_emit()).await;
            if res.is_ok() && config::load().settings.default_mods && mc != ornithe::MC {
                let _ = default_mods::ensure_default_mods(&root, &mc, &silent_emit()).await;
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
