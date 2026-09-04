using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace S1mp1e.Services;

/// <summary>
/// Mirror of the Rust <c>Settings</c> struct in
/// <c>src-tauri/src/config.rs</c>. Persisted at
/// <c>%APPDATA%\S1mp1e\config.json</c> under the "settings" key so both the
/// Avalonia UI and the Rust CLI read/write the SAME file.
/// </summary>
public class LauncherSettings
{
    [JsonPropertyName("ram_mb")]              public int    RamMb              { get; set; } = 4096;
    [JsonPropertyName("after_launch")]        public string AfterLaunch        { get; set; } = "hide";  // hide | keep | close
    [JsonPropertyName("mc_path")]             public string McPath             { get; set; } = "";
    [JsonPropertyName("offline_name")]        public string OfflineName        { get; set; } = "Player";
    [JsonPropertyName("accent")]              public string Accent             { get; set; } = "#0a84ff";
    [JsonPropertyName("glass")]               public bool   Glass              { get; set; } = true;
    [JsonPropertyName("reduce_transparency")] public bool   ReduceTransparency { get; set; }
    [JsonPropertyName("version")]             public string Version            { get; set; } = "26.2";
    [JsonPropertyName("loader")]              public string Loader             { get; set; } = "fabric";
    [JsonPropertyName("theme")]               public string Theme              { get; set; } = "auto";  // auto | light | dark
    [JsonPropertyName("jvm_args")]            public string JvmArgs            { get; set; } = "";
    [JsonPropertyName("res_width")]           public int    ResWidth           { get; set; }            // 0 = MC default
    [JsonPropertyName("res_height")]          public int    ResHeight          { get; set; }
    [JsonPropertyName("java_path")]           public string JavaPath           { get; set; } = "";      // "" = auto
}

/// One saved MC account (mirrors Rust's <c>Account</c>). The tokens are the raw
/// MSA + Minecraft session — treat as sensitive; only stored on the user's own
/// disk under %APPDATA%\S1mp1e\config.json (same file Rust owns).
public class SavedAccount
{
    [JsonPropertyName("uuid")]           public string Uuid          { get; set; } = "";
    [JsonPropertyName("name")]           public string Name          { get; set; } = "";
    [JsonPropertyName("mc_token")]       public string McToken       { get; set; } = "";
    [JsonPropertyName("mc_expires_at")]  public long   McExpiresAt   { get; set; }
    [JsonPropertyName("msa_refresh")]    public string MsaRefresh    { get; set; } = "";
}

public class LauncherConfig
{
    [JsonPropertyName("client_id")] public string             ClientId { get; set; } = "";
    /// Currently-selected account. Always mirrors one entry in <see cref="Accounts"/>.
    [JsonPropertyName("account")]   public SavedAccount?      Account  { get; set; }
    [JsonPropertyName("accounts")]  public List<SavedAccount> Accounts { get; set; } = new();
    [JsonPropertyName("settings")]  public LauncherSettings   Settings { get; set; } = new();

    /// projectId → list of MC versions we've already downloaded it for. Consulted
    /// on Modrinth search to mark existing rows as "已下載" up-front and to skip
    /// hitting the network again for repeat clicks.
    [JsonPropertyName("downloaded_mods")]
    public Dictionary<string, List<string>> DownloadedMods { get; set; } = new();

    /// "projectId@mc" → the jar filename we last wrote, so an 更新 can delete the old
    /// version before writing the new one (else two versions double-load and clash).
    [JsonPropertyName("downloaded_mod_files")]
    public Dictionary<string, string> DownloadedModFiles { get; set; } = new();
}

/// <summary>Load / save the launcher config shared with the Rust CLI.</summary>
public static class ConfigStore
{
    private static readonly JsonSerializerOptions Opts = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public static string ConfigPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                     "S1mp1e", "config.json");

    public static LauncherConfig Load()
    {
        try
        {
            if (File.Exists(ConfigPath))
            {
                var json = File.ReadAllText(ConfigPath);
                return JsonSerializer.Deserialize<LauncherConfig>(json) ?? new LauncherConfig();
            }
        }
        catch
        {
            // Corrupt config → keep a copy for recovery instead of silently wiping the
            // account + settings, then start fresh.
            try { File.Move(ConfigPath, ConfigPath + ".corrupt", overwrite: true); } catch { }
        }
        return new LauncherConfig();
    }

    /// <summary>
    /// Persist only the fields the UI owns, re-reading the file first so whatever
    /// the <c>itest</c> CLI has written for the account survives.
    /// </summary>
    /// <remarks>
    /// The CLI is a SEPARATE PROCESS that rewrites this same file: on login, and on
    /// every silent token refresh at launch. The UI calls this from ~17 settings
    /// handlers (version, loader, RAM, theme, accent, glass, mod bookkeeping...), so
    /// writing the whole in-memory snapshot would clobber anything the CLI wrote
    /// after we last loaded. On a FRESH profile the in-memory account starts null,
    /// so picking a version right after signing in wrote <c>account: null</c>
    /// straight over the successful sign-in and the next launch fell back to
    /// offline. Account changes are deliberate and go through <see cref="Save"/>.
    /// </remarks>
    public static void SaveUiOwned(LauncherConfig cfg)
    {
        try
        {
            var live = Load();
            live.Settings           = cfg.Settings;
            live.ClientId           = cfg.ClientId;
            live.DownloadedMods     = cfg.DownloadedMods;
            live.DownloadedModFiles = cfg.DownloadedModFiles;
            Save(live);
        }
        catch { /* best effort */ }
    }

    public static void Save(LauncherConfig cfg)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
            // Atomic: write a temp file then move over the real one, so a crash mid-write
            // can't corrupt config.json (which Load would then reset — losing the account).
            var tmp = ConfigPath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(cfg, Opts));
            File.Move(tmp, ConfigPath, overwrite: true);
        }
        catch { /* best effort */ }
    }
}
