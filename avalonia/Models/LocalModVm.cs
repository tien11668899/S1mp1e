using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using Avalonia.Media.Imaging;

namespace S1mp1e.Models;

/// <summary>
/// Row view-model for the local mods list. The <see cref="JarPath"/> mutates
/// when the user toggles enable/disable (rename foo.jar ↔ foo.jar.disabled),
/// so it's a settable property, not init-only.
///
/// <para>Beyond name/icon/enabled it now carries the index-derived facts the mod page
/// surfaces: version, source, added date, whether the jar actually loads this launch,
/// how many duplicate copies exist, dependency / locked / update state.</para>
/// </summary>
public sealed class LocalModVm : INotifyPropertyChanged
{
    private string _jarPath = "";
    public string JarPath
    {
        get => _jarPath;
        set { if (_jarPath != value) { _jarPath = value; OnChanged(); } }
    }

    public string Id          { get; init; } = "";
    public string Name        { get; init; } = "";
    public string Description { get; init; } = "";
    public Bitmap? Icon       { get; init; }

    // ---- index-derived metadata ----
    public string? ProjectId  { get; set; }
    public string? Slug       { get; set; }
    public string  Folder     { get; init; } = "";      // directory the (primary) copy sits in
    public string? Sha512     { get; init; }
    public long    Size       { get; init; }

    private string? _version;
    public string? Version { get => _version; set { if (_version != value) { _version = value; OnChanged(); OnChanged(nameof(Meta)); } } }

    /// "modrinth" | "manual" | "default" | "perf" | "unknown".
    public string Source { get; set; } = "unknown";

    public long InstalledAt { get; set; }               // unix seconds; 0 = unknown

    /// How many copies of this same mod exist across all folders (1 = unique).
    private int _dupCount = 1;
    public int DupCount { get => _dupCount; set { if (_dupCount != value) { _dupCount = value; OnChanged(); OnChanged(nameof(DupBadge)); OnChanged(nameof(HasDup)); } } }
    public bool HasDup => _dupCount > 1;
    public string DupBadge => _dupCount > 1 ? $"重複 ×{_dupCount}" : "";
    /// JarPaths of the other duplicate copies (for "keep this one" cleanup).
    public List<string> DupPaths { get; set; } = new();

    /// True when this jar is in the folder the game actually launches from.
    private bool _loads = true;
    public bool Loads { get => _loads; set { if (_loads != value) { _loads = value; OnChanged(); OnChanged(nameof(LoadWarn)); OnChanged(nameof(RowOpacity)); } } }
    public bool LoadWarn => !_loads;
    public double RowOpacity => _loads ? 1.0 : 0.55;

    public bool IsDependency { get; set; }
    public List<string> DepOf { get; set; } = new();    // project ids that require this mod

    private bool _locked;
    public bool Locked { get => _locked; set { if (_locked != value) { _locked = value; OnChanged(); OnChanged(nameof(LockGlyphOpacity)); } } }
    public double LockGlyphOpacity => _locked ? 1.0 : 0.3;
    /// Only mods resolvable on Modrinth can be locked / updated, so the lock control only shows for those.
    public bool CanLock => !string.IsNullOrEmpty(ProjectId);

    // ---- update state (stage 2) ----
    private bool _hasUpdate;
    public bool HasUpdate { get => _hasUpdate; set { if (_hasUpdate != value) { _hasUpdate = value; OnChanged(); OnChanged(nameof(UpdateBadgeOpacity)); } } }
    public double UpdateBadgeOpacity => _hasUpdate ? 1.0 : 0.0;
    private string? _updateVersion;
    public string? UpdateVersion
    {
        get => _updateVersion;
        set { if (_updateVersion != value) { _updateVersion = value; OnChanged(); OnChanged(nameof(UpdateBadge)); } }
    }
    /// "↑ 0.8.13" — the clean mod version only (Modrinth version numbers often embed the MC version + loader).
    public string UpdateBadge => "↑ " + PrettyVersion(_updateVersion);
    public string? UpdateVersionId { get; set; }
    public string? InstalledVersionId { get; set; }   // Modrinth version id currently on disk, if known

    private bool _enabled;
    public bool Enabled
    {
        get => _enabled;
        set { if (_enabled != value) { _enabled = value; OnChanged(); } }
    }

    /// Secondary line under the name: "v1.2.3 · Modrinth · 3天前" (omits unknown parts).
    public string Meta
    {
        get
        {
            var parts = new List<string>();
            if (!string.IsNullOrWhiteSpace(_version)) parts.Add("v" + PrettyVersion(_version));
            var src = Source switch
            {
                "modrinth" => "Modrinth",
                "manual"   => "手動加入",
                "default"  => "預設模組",
                "perf"     => "效能套件",
                _          => null,
            };
            if (src != null) parts.Add(src);
            if (InstalledAt > 0) parts.Add(RelTime(InstalledAt));
            return string.Join("  ·  ", parts);
        }
    }

    private static string RelTime(long unixSeconds)
    {
        try
        {
            var when = DateTimeOffset.FromUnixTimeSeconds(unixSeconds);
            var d = DateTimeOffset.UtcNow - when;
            if (d.TotalDays >= 365) return $"{(int)(d.TotalDays / 365)} 年前";
            if (d.TotalDays >= 30)  return $"{(int)(d.TotalDays / 30)} 個月前";
            if (d.TotalDays >= 1)   return $"{(int)d.TotalDays} 天前";
            if (d.TotalHours >= 1)  return $"{(int)d.TotalHours} 小時前";
            if (d.TotalMinutes >= 1)return $"{(int)d.TotalMinutes} 分鐘前";
            return "剛剛";
        }
        catch { return ""; }
    }

    public void RaiseMeta() { OnChanged(nameof(Meta)); }

    private static readonly System.Text.RegularExpressions.Regex VerTok =
        new(@"\d+(?:\.\d+)+(?:-(?:alpha|beta|rc|pre)(?:[.\d]+)?)?", System.Text.RegularExpressions.RegexOptions.IgnoreCase);

    /// <summary>
    /// Reduce a version string to the mod's own version: "mc1.21.1-0.8.13-fabric" → "0.8.13",
    /// "0.116.17+1.21.1" → "0.116.17", "0.6.13+mc1.21.1" → "0.6.13". A token right after "mc" or "+" is the
    /// Minecraft version and is dropped; if that doesn't leave exactly one candidate, the input is returned as-is.
    /// </summary>
    public static string PrettyVersion(string? v)
    {
        if (string.IsNullOrWhiteSpace(v)) return "";
        var ms = VerTok.Matches(v);
        if (ms.Count == 0) return v;
        if (ms.Count == 1) return ms[0].Value;
        var keep = new System.Collections.Generic.List<string>();
        foreach (System.Text.RegularExpressions.Match m in ms)
        {
            var i = m.Index;
            bool mcTag = (i >= 2 && v.Substring(i - 2, 2).Equals("mc", StringComparison.OrdinalIgnoreCase))
                      || (i >= 1 && v[i - 1] == '+');
            if (!mcTag) keep.Add(m.Value);
        }
        return keep.Count == 1 ? keep[0] : v;
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
