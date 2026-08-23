//! Serde models for Mojang piston-meta, asset index, java-runtime manifest, and
//! Fabric meta. Only the fields S1mp1e actually uses are modelled; everything
//! else is ignored so schema drift across the 1.8.9 → 26.2 range stays tolerant.

use serde::Deserialize;
use std::collections::BTreeMap;

// ---- version_manifest_v2.json ----------------------------------------------

pub const VERSION_MANIFEST: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

#[derive(Deserialize)]
pub struct VersionManifest {
    pub latest: Latest,
    pub versions: Vec<ManifestVersion>,
}

#[derive(Deserialize)]
pub struct Latest {
    pub release: String,
    pub snapshot: String,
}

#[derive(Deserialize, Clone)]
pub struct ManifestVersion {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub url: String,
    #[serde(rename = "releaseTime")]
    pub release_time: Option<String>,
}

// ---- <version>.json (both eras) --------------------------------------------

#[derive(Deserialize)]
pub struct VersionJson {
    pub id: String,
    #[serde(rename = "inheritsFrom")]
    pub inherits_from: Option<String>,
    #[serde(rename = "mainClass")]
    pub main_class: Option<String>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    pub downloads: Option<VersionDownloads>,
    #[serde(rename = "assetIndex")]
    pub asset_index: Option<AssetIndexRef>,
    pub assets: Option<String>,
    #[serde(rename = "javaVersion")]
    pub java_version: Option<JavaVersion>,
    // modern (1.13+) structured args
    pub arguments: Option<Arguments>,
    // legacy (<=1.12) flat string
    #[serde(rename = "minecraftArguments")]
    pub minecraft_arguments: Option<String>,
}

#[derive(Deserialize)]
pub struct VersionDownloads {
    pub client: Option<Artifact>,
}

#[derive(Deserialize)]
pub struct AssetIndexRef {
    pub id: String,
    pub url: String,
    pub sha1: Option<String>,
    pub size: Option<u64>,
    #[serde(rename = "totalSize")]
    pub total_size: Option<u64>,
}

#[derive(Deserialize, Clone)]
pub struct JavaVersion {
    pub component: String,
    #[serde(rename = "majorVersion")]
    pub major_version: u32,
}

#[derive(Deserialize)]
pub struct Arguments {
    #[serde(default)]
    pub jvm: Vec<ArgEntry>,
    #[serde(default)]
    pub game: Vec<ArgEntry>,
}

/// An arg entry is either a bare string or a { rules, value } object.
#[derive(Deserialize)]
#[serde(untagged)]
pub enum ArgEntry {
    Plain(String),
    Conditional {
        rules: Vec<Rule>,
        value: StringOrList,
    },
}

#[derive(Deserialize)]
#[serde(untagged)]
pub enum StringOrList {
    One(String),
    Many(Vec<String>),
}

// ---- libraries -------------------------------------------------------------

#[derive(Deserialize, Clone)]
pub struct Library {
    pub name: String,
    pub downloads: Option<LibraryDownloads>,
    /// Fabric/legacy style: base maven url, no explicit artifact block.
    pub url: Option<String>,
    #[serde(default)]
    pub rules: Vec<Rule>,
    /// Legacy natives classifier map, e.g. { "windows": "natives-windows" }.
    pub natives: Option<BTreeMap<String, String>>,
}

#[derive(Deserialize, Clone)]
pub struct LibraryDownloads {
    pub artifact: Option<Artifact>,
    pub classifiers: Option<BTreeMap<String, Artifact>>,
}

#[derive(Deserialize, Clone)]
pub struct Artifact {
    pub path: Option<String>,
    pub url: String,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

#[derive(Deserialize, Clone)]
pub struct Rule {
    pub action: String,
    pub os: Option<OsRule>,
    /// Feature gate (`is_demo_user`, `has_custom_resolution`, `has_quick_plays_support`, …).
    /// S1mp1e enables NONE of these, so any rule carrying a features object must
    /// NOT match — otherwise `--demo` and unsubstituted `${resolution_width}` /
    /// `${quickPlay*}` args leak into the launch (demo mode + junk args).
    pub features: Option<serde_json::Value>,
}

#[derive(Deserialize, Clone)]
pub struct OsRule {
    pub name: Option<String>,
    pub arch: Option<String>,
}

impl Rule {
    /// Evaluate rules for the current OS (Windows). Returns whether allowed.
    pub fn allows(rules: &[Rule]) -> bool {
        if rules.is_empty() {
            return true;
        }
        let mut allowed = false;
        for r in rules {
            // any feature-gated rule can't match — we enable no optional features
            if r.features.is_some() {
                continue;
            }
            let matches = match &r.os {
                None => true,
                Some(os) => {
                    let name_ok = os.name.as_deref().map(|n| n == "windows").unwrap_or(true);
                    let arch_ok = os
                        .arch
                        .as_deref()
                        .map(|a| a == "x86" || a == "x64" || a == "x86_64")
                        .unwrap_or(true);
                    name_ok && arch_ok
                }
            };
            if matches {
                allowed = r.action == "allow";
            }
        }
        allowed
    }
}

// ---- asset index -----------------------------------------------------------

#[derive(Deserialize)]
pub struct AssetIndex {
    pub objects: BTreeMap<String, AssetObject>,
}

#[derive(Deserialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
}

// ---- java-runtime manifest -------------------------------------------------

/// The umbrella "all.json" listing every runtime component per OS.
pub const JAVA_RUNTIME_ALL: &str =
    "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// Keyed by OS ("windows-x64"), then component ("java-runtime-epsilon").
pub type JavaRuntimeAll = BTreeMap<String, BTreeMap<String, Vec<JavaRuntimeEntry>>>;

#[derive(Deserialize)]
pub struct JavaRuntimeEntry {
    pub manifest: ManifestRef,
    pub version: Option<JavaRuntimeVersion>,
}

#[derive(Deserialize)]
pub struct JavaRuntimeVersion {
    pub name: Option<String>,
}

#[derive(Deserialize)]
pub struct ManifestRef {
    pub url: String,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

#[derive(Deserialize)]
pub struct JavaRuntimeManifest {
    pub files: BTreeMap<String, JavaRuntimeFile>,
}

#[derive(Deserialize)]
pub struct JavaRuntimeFile {
    #[serde(rename = "type")]
    pub kind: String,
    pub downloads: Option<JavaFileDownloads>,
    #[serde(default)]
    pub executable: bool,
    pub target: Option<String>,
}

#[derive(Deserialize)]
pub struct JavaFileDownloads {
    pub raw: Option<Artifact>,
}

// ---- Fabric meta -----------------------------------------------------------

pub fn fabric_loader_list(mc: &str) -> String {
    format!("https://meta.fabricmc.net/v2/versions/loader/{mc}")
}
pub fn fabric_profile_json(mc: &str, loader: &str) -> String {
    format!("https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json")
}

#[derive(Deserialize)]
pub struct FabricLoaderInfo {
    pub loader: FabricLoaderVersion,
}

#[derive(Deserialize)]
pub struct FabricLoaderVersion {
    pub version: String,
}
