using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;

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
    [JsonPropertyName("default_mods")]        public bool   DefaultMods        { get; set; } = true;    // Fabric default mod set (default_mods.rs)
    [JsonPropertyName("perf_pack")]           public bool   PerfPack           { get; set; } = true;    // measured performance pack (perf.rs)
    [JsonPropertyName("discrete_gpu")]        public bool   DiscreteGpu        { get; set; } = true;    // game's Java marked "high performance" in Windows graphics settings (launch.rs)
    [JsonPropertyName("process_priority")]    public string ProcessPriority    { get; set; } = "above_normal"; // normal | above_normal | high (launch.rs)
    [JsonPropertyName("graphics_backend")]    public string GraphicsBackend    { get; set; } = "opengl"; // 26.x graphics API: opengl | vulkan (launch.rs)
    [JsonPropertyName("perf_check")]          public bool   PerfCheck          { get; set; } = true;    // performance checks around Play (PerfCheck.cs)
    [JsonPropertyName("perf_hint_borderless")] public bool  PerfHintBorderless { get; set; } = true;    // ask about borderless → exclusive fullscreen (false = "不再提醒")
    [JsonPropertyName("menu_key")]            public int    MenuKey            { get; set; } = 54;      // LWJGL RShift; the in-game config-GUI open key
    [JsonPropertyName("update_prerelease")]   public bool   UpdatePrerelease   { get; set; }            // mod updates: include beta/alpha, not just release
    [JsonPropertyName("cs2_knives")]          public bool   Cs2Knives          { get; set; }            // show the CS2 knife-pack row on the Play page
    [JsonPropertyName("discord_presence")]    public bool   DiscordPresence    { get; set; } = true;    // show the game on the Discord profile (DiscordPresence.cs)

    /// <summary>Settings this build does not know (written by a newer launcher or the CLI): kept and written back.</summary>
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; set; }
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

    /// <summary>Top-level keys this build does not know: kept and written back, so nothing another version wrote is lost.</summary>
    [JsonExtensionData] public Dictionary<string, JsonElement>? Extra { get; set; }

    /// <summary>
    /// True for the stand-in returned when config.json exists but could not be read (locked by antivirus, the indexer,
    /// or the CLI saving at that instant). It must never be saved: it holds no accounts, and writing it would replace the
    /// player's real file. <see cref="ConfigStore"/> refuses to persist it.
    /// </summary>
    [JsonIgnore] public bool Unreadable { get; init; }
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

    /// <summary>
    /// The saved config. A missing file is a fresh install. A file that exists but cannot be read right now is retried
    /// briefly; if it stays unreadable the result is an empty stand-in marked <see cref="LauncherConfig.Unreadable"/>,
    /// which every save path refuses to write — an I/O hiccup must never turn into "all accounts deleted". Only JSON
    /// that does not parse is treated as corrupt: it is moved aside under a timestamped name (an earlier backup is never
    /// overwritten) and a fresh config starts.
    /// </summary>
    public static LauncherConfig Load()
    {
        string? json = null;
        for (int attempt = 0; attempt < 6; attempt++)
        {
            try
            {
                if (!File.Exists(ConfigPath)) return new LauncherConfig();
                json = File.ReadAllText(ConfigPath);
                break;
            }
            catch (FileNotFoundException) { return new LauncherConfig(); }
            catch (DirectoryNotFoundException) { return new LauncherConfig(); }
            catch (Exception) when (attempt < 5) { Thread.Sleep(50 * (attempt + 1)); }
            catch (Exception) { return new LauncherConfig { Unreadable = true }; }
        }
        try
        {
            return JsonSerializer.Deserialize<LauncherConfig>(json!) ?? new LauncherConfig();
        }
        catch (JsonException)
        {
            try { File.Move(ConfigPath, $"{ConfigPath}.corrupt-{DateTime.Now:yyyyMMdd-HHmmss}", overwrite: false); } catch { }
            return new LauncherConfig();
        }
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
    public static bool SaveUiOwned(LauncherConfig cfg)
    {
        // The in-memory copy may itself be the stand-in from an unreadable startup: its settings are defaults, not the
        // player's, and must not be merged into the real file either.
        if (cfg.Unreadable) return false;
        try
        {
            var live = Load();
            if (live.Unreadable) return false;   // never write over a file we could not read
            live.Settings           = cfg.Settings;
            live.ClientId           = cfg.ClientId;
            live.DownloadedMods     = cfg.DownloadedMods;
            live.DownloadedModFiles = cfg.DownloadedModFiles;
            return Save(live);
        }
        catch { return false; }
    }

    /// <summary>
    /// Write the whole config atomically: a per-process temp file (the CLI uses its own), then a rename over the real
    /// file, retried briefly when another process has config.json open for a moment. Refuses an
    /// <see cref="LauncherConfig.Unreadable"/> stand-in. Returns false when nothing was written.
    /// </summary>
    public static bool Save(LauncherConfig cfg)
    {
        if (cfg.Unreadable) return false;
        string? tmp = null;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
            tmp = $"{ConfigPath}.{Environment.ProcessId}.tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(cfg, Opts));
            for (int attempt = 0; ; attempt++)
            {
                try { File.Move(tmp!, ConfigPath, overwrite: true); tmp = null; return true; }
                catch (IOException) when (attempt < 5) { Thread.Sleep(40 * (attempt + 1)); }
                catch (UnauthorizedAccessException) when (attempt < 5) { Thread.Sleep(40 * (attempt + 1)); }
            }
        }
        catch { return false; }
        finally
        {
            if (tmp != null) try { File.Delete(tmp); } catch { }
        }
    }
}
