//! Ornithe 1.8.9 —— S1mp1e 的 1.8.9「Fabric」路線（衝 1000+fps）：
//! Ornithe gen2（Fabric Loader 0.19.3＋calamus intermediary）＋Java 25＋Pylon（LWJGL3/SDL3 視窗層）
//! ＋OSL＋Argentum（Celeritas 衍生的效能引擎）＋S1mp1e 液態玻璃（Ornithe 版）＋Forge 相容層
//! （Forge 1.8.9 模組照樣放 s1mp1e-mods/1.8.9/）。
//!
//! 版本全部鎖定成 2026-10-04 實測過的組合，下載都驗 sha1。Argentum 沒有授權條款（保留所有權利），
//! 只從作者 GitHub 發佈頁下載到玩家電腦，不打包、不轉發。

use crate::download::{client, download_file, get_text};
use crate::install::{install_version, tick, Emit};
use crate::meta::VersionJson;
use crate::paths::*;
use anyhow::{Context, Result};
use std::path::PathBuf;

pub const MC: &str = "1.8.9";
/// 實測過的 Ornithe（Fabric）載入器版本
pub const LOADER: &str = "0.19.3";

/// 這個 (mc, loader) 組合是不是走 Ornithe：1.8.9 選 Fabric 就是。
pub fn is_ornithe(mc: &str, loader: &str) -> bool {
    mc == MC && !loader.eq_ignore_ascii_case("forge")
}

/// 已安裝的 profile id 是不是 Ornithe 的（`fabric-loader-0.19.3-ornithe-1.8.9`）。
pub fn is_ornithe_id(id: &str) -> bool {
    id.contains("-ornithe-")
}

pub fn profile_id() -> String {
    format!("fabric-loader-{LOADER}-ornithe-{MC}")
}

/// Ornithe gen2 的函式庫升級（和 Loom 開發環境的 library-upgrades.json 相同）：
/// (maven 座標, maven 根網址, sha1)。啟動時子層的 group:artifact 會取代原版的舊版。
const UPGRADES: &[(&str, &str, &str)] = &[
    ("org.slf4j:slf4j-api:2.0.17", "https://repo1.maven.org/maven2/", "d9e58ac9c7779ba3bf8142aff6c830617a7fe60f"),
    ("org.apache.logging.log4j:log4j-slf4j2-impl:2.19.0", "https://repo1.maven.org/maven2/", "5c04bfdd63ce9dceb2e284b81e96b6a70010ee10"),
    ("org.apache.logging.log4j:log4j-api:2.19.0", "https://repo1.maven.org/maven2/", "ea1b37f38c327596b216542bc636cfdc0b8036fa"),
    ("org.apache.logging.log4j:log4j-core:2.19.0", "https://repo1.maven.org/maven2/", "3b6eeb4de4c49c0fe38a4ee27188ff5fee44d0bb"),
    ("it.unimi.dsi:fastutil:8.5.9", "https://repo1.maven.org/maven2/", "bb7ea75ecdb216654237830b3a96d87ad91f8cc5"),
    ("com.google.code.gson:gson:2.10", "https://repo1.maven.org/maven2/", "dd9b193aef96e973d5a11ab13cd17430c2e4306b"),
    ("net.ornithemc:log4j-patch:1.0.0", "https://maven.ornithemc.net/releases/", "00bf01289838da1061d03a249cca149f3d98c0a0"),
    ("net.sf.jopt-simple:jopt-simple:5.0.4", "https://repo1.maven.org/maven2/", "4fdac2fbe92dfad86aa6e9301736f6b4342a3f5c"),
];

/// 這條路線需要的模組（啟動器擁有，放 `s1mp1e-mods/.ornithe-1.8.9/`）：(檔名, 網址, sha1)。
const STACK: &[(&str, &str, &str)] = &[
    ("pylon-0.1.9.jar", "https://maven.cloverclient.com/releases/pl/tomgirl/pylon/0.1.9/pylon-0.1.9.jar", "15537edf00a7d2532ac0f241ef8ad71d6c00159d"),
    ("argentum-1.0.0.jar", "https://github.com/QuicksilverMC/Argentum/releases/download/v1.0.0/argentum-1.0.0.jar", "5723a4b884396010df44f4e7f27af38010fe12ed"),
];

/// OSL gen2 模組：(模組, 版本, sha1)，來源 maven.ornithemc.net。
const OSL: &[(&str, &str, &str)] = &[
    ("blocks", "0.1.1+mc14w27a-mc17w46a", "cb156141d6931181ee2d08ba3df3e96cd97cdbbc"),
    ("branding", "0.4.3+mc14w30a-mc16w05a", "ea041e635bdd2ce165c9dfbb41c1f20745d4ffbe"),
    ("config", "0.6.2+mc14w27a-mc15w39c", "66c9c45e50d9aa3340f23b3d5c0ef51c9dfc5c01"),
    ("core", "0.9.0+mc14w27a-mc1.14.4", "e73b040adcd98ec724717f9a5cef6288fe29f694"),
    ("entrypoints", "0.6.1+mc13w16a-mc1.14.4", "c20aafd9d8df057fafbde7eafd7fcd9f2c1b99c5"),
    ("executors", "0.1.0+mc14w21a-mc1.13.2", "25a14b8b04b9e365718f915a62d313be527748b2"),
    ("items", "0.1.0+mc14w26a-mc17w46a", "34b32bc2cdeb715acb1036cdf529ee416e19a006"),
    ("keybinds", "0.3.0+mc13w36a-mc17w15a", "38b3a08ec74b71b2504652bd5fa321e7ab7488c9"),
    ("lifecycle-events", "0.6.1+mc13w36a-mc19w07a", "3ef8d7c6667dd3c4043ffe243e29cfde7bb32585"),
    ("localization", "0.1.0+mc13w26a-mc18w01a", "5afbea0c2573334f5d150bf32053129a69d91e34"),
    ("networking", "0.9.0+mc13w41a-mc18w30b", "541214adc070735ba430799a092ad21a0be1c0fd"),
    ("networking-impl", "0.1.1+mc14w31a-mc1.13-pre2", "6ee4dfd15e693e09594893fe7ba361487333b82f"),
    ("resource-loader", "0.7.4+mc1.8.2-pre5-mc1.12.2", "9cb5cf8b8c66b3d53fb2af524656cab36373cbfb"),
    ("text-components", "0.1.0-alpha.3+mca0.1.0-mc1.14.4", "8a7579705400b63b4457e1c1acd32f5d8fd91d0a"),
];

/// 玻璃（Ornithe 版）與 Forge 相容層在 `s1mp1e-mods/` 的名稱（`ensure_glass` 的鍵）。
pub const GLASS_KEY: &str = "1.8.9-ornithe";
pub const FORGECOMPAT_KEY: &str = "1.8.9-forgecompat";

fn stack_dir(root: &PathBuf) -> PathBuf {
    root.join("s1mp1e-mods").join(".ornithe-1.8.9")
}

/// 安裝 Ornithe 1.8.9：原版 1.8.9、Ornithe 的 loader profile（改寫成 S1mp1e 用的形式）、函式庫。
/// 回傳 profile id。
pub async fn install_ornithe(root: PathBuf, emit: Emit) -> Result<String> {
    let cl = client();
    install_version(root.clone(), MC.to_string(), emit.clone()).await?;

    tick(&emit, "fabric", "查詢 Ornithe 載入器", 0, 0);
    let url = format!("https://meta.ornithemc.net/v3/versions/fabric-loader/{MC}/{LOADER}/profile/json");
    let text = get_text(&cl, &url).await.context("Ornithe profile json")?;
    let mut v: serde_json::Value = serde_json::from_str(&text).context("parse Ornithe profile")?;

    let id = profile_id();
    v["id"] = id.clone().into();
    // Ornithe 的安裝器會另建 `1.8.9-vanilla`；我們直接繼承已安裝的原版 1.8.9
    v["inheritsFrom"] = MC.into();
    // Java 25（Pylon 的 FFM、相容層的 Unsafe 寫入、Argentum 都需要）
    v["javaVersion"] = serde_json::json!({ "component": "java-runtime-epsilon", "majorVersion": 25 });
    // Pylon 自帶 LWJGL3：原版的 LWJGL2（含 natives）不能上 classpath
    v["s1mp1eExclude"] = serde_json::json!(["org.lwjgl.lwjgl:"]);
    let libs = v["libraries"].as_array_mut().context("profile libraries")?;
    for (name, repo, sha1) in UPGRADES {
        libs.push(serde_json::json!({ "name": name, "url": repo, "sha1": sha1 }));
    }
    let jvm = v["arguments"]["jvm"].as_array_mut().context("profile arguments.jvm")?;
    for a in ["--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow"] {
        jvm.push(a.into());
    }

    let out = serde_json::to_string_pretty(&v)?;
    let dest = version_json(&root, &id);
    if let Some(p) = dest.parent() {
        tokio::fs::create_dir_all(p).await?;
    }
    tokio::fs::write(&dest, &out).await?;
    let vj: VersionJson = serde_json::from_str(&out)?;
    crate::install::install_libraries(&cl, &root, &vj, &emit).await?;
    ensure_stack(&root, &emit).await?;
    // Java 25（Mojang 的 java-runtime-epsilon），安裝時就備好
    crate::install::ensure_java(root.clone(), id.clone(), emit.clone()).await?;
    tick(&emit, "done", format!("Ornithe {LOADER}（1.8.9）安裝完成"), 1, 1);
    Ok(id)
}

/// 下載／核對 Pylon、OSL、Argentum（已存在且 sha1 相符就不動）。
pub async fn ensure_stack(root: &PathBuf, emit: &Emit) -> Result<()> {
    let cl = client();
    let dir = stack_dir(root);
    tokio::fs::create_dir_all(&dir).await?;
    let mut jobs: Vec<(String, String, &str)> = STACK.iter().map(|(f, u, s)| (f.to_string(), u.to_string(), *s)).collect();
    for (m, ver, sha1) in OSL {
        jobs.push((
            format!("osl-{m}-{ver}.jar"),
            format!("https://maven.ornithemc.net/releases/net/ornithemc/osl-gen2/{m}/{ver}/{m}-{ver}.jar"),
            sha1,
        ));
    }
    let n = jobs.len() as u64;
    for (i, (file, url, sha1)) in jobs.iter().enumerate() {
        tick(emit, "ornithe", format!("Ornithe 模組 {file}"), i as u64, n);
        download_file(&cl, url, &dir.join(file), Some(sha1), |_| {})
            .await
            .with_context(|| format!("下載 {file}"))?;
    }
    // 舊版本留下的檔案（日後換版本時）清掉，免得兩版同時載入
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if name.ends_with(".jar") && !jobs.iter().any(|(f, _, _)| *f == name) {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
    tick(emit, "ornithe", "Ornithe 模組就緒", n, n);
    Ok(())
}

/// 啟動時要放進該版本 mods/ 的 Ornithe 模組（Pylon、OSL、Argentum）。
pub fn stack_jars(root: &PathBuf) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = std::fs::read_dir(stack_dir(root))
        .map(|rd| rd.flatten().map(|e| e.path()).filter(|p| p.extension().map(|x| x == "jar").unwrap_or(false)).collect())
        .unwrap_or_default();
    out.sort();
    out
}
