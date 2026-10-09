using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace S1mp1e.Services;

/// <summary>
/// One tracked mod jar. Keyed by <see cref="Mc"/> + <see cref="File"/> (the enabled
/// filename, with any <c>.disabled</c> stripped — stable across enable/disable).
///
/// <para>Everything the mod page needs to answer "what version is this, where did it come
/// from, when did I add it, is it a dependency, is it locked" lives here. Written by both
/// the download path (full Modrinth metadata) and the scanner's hash-backfill (manual drops
/// identified by sha512 against Modrinth's <c>/version_files</c>).</para>
/// </summary>
public sealed class ModRecord
{
    [JsonPropertyName("mc")]           public string  Mc          { get; set; } = "";
    [JsonPropertyName("file")]         public string  File        { get; set; } = "";
    [JsonPropertyName("project_id")]   public string? ProjectId   { get; set; }
    [JsonPropertyName("slug")]         public string? Slug        { get; set; }
    [JsonPropertyName("name")]         public string? Name        { get; set; }
    [JsonPropertyName("version_id")]   public string? VersionId   { get; set; }
    [JsonPropertyName("version")]      public string? Version     { get; set; }   // human version string
    [JsonPropertyName("sha512")]       public string? Sha512      { get; set; }
    [JsonPropertyName("size")]         public long    Size        { get; set; }   // bytes, to detect a changed file
    [JsonPropertyName("installed_at")] public long    InstalledAt { get; set; }   // unix seconds; 0 = unknown
    [JsonPropertyName("source")]       public string  Source      { get; set; } = "manual"; // modrinth|manual|default|perf
    [JsonPropertyName("dep_of")]       public List<string>? DepOf { get; set; }   // project ids that required this mod
    [JsonPropertyName("locked")]       public bool    Locked      { get; set; }   // excluded from 全部更新
    /// Set transiently in stage 2 when a newer compatible version exists (not persisted).
    [JsonIgnore] public string? UpdateVersion { get; set; }
    [JsonIgnore] public string? UpdateVersionId { get; set; }

    public bool IsDependency => DepOf is { Count: > 0 };
}

/// <summary>
/// Per-mc-root mod index persisted at <c>&lt;mcRoot&gt;/s1mp1e-mods/.s1mp1e-index.json</c>.
///
/// <para>Deliberately NOT in config.json: that file is co-owned by the Rust CLI, whose
/// <c>Config</c> struct has no field for this and would drop it on the next token-refresh
/// rewrite. Storing it next to the mods it describes keeps it durable and portable.</para>
/// </summary>
public sealed class ModIndex
{
    [JsonPropertyName("records")] public List<ModRecord> Records { get; set; } = new();

    [JsonIgnore] public string? Path { get; private set; }

    private static readonly JsonSerializerOptions J = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static string IndexPath(string mcRoot)
        => System.IO.Path.Combine(mcRoot, "s1mp1e-mods", ".s1mp1e-index.json");

    public static ModIndex Load(string mcRoot)
    {
        var path = IndexPath(mcRoot);
        ModIndex idx;
        try
        {
            if (File.Exists(path))
                idx = JsonSerializer.Deserialize<ModIndex>(File.ReadAllText(path)) ?? new ModIndex();
            else
                idx = new ModIndex();
        }
        catch { idx = new ModIndex(); }
        idx.Path = path;
        return idx;
    }

    public void Save()
    {
        if (string.IsNullOrEmpty(Path)) return;
        try
        {
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
            var tmp = Path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(this, J));
            File.Move(tmp, Path, overwrite: true);
        }
        catch { /* best effort */ }
    }

    private static string Norm(string file)
        => file.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase)
            ? file.Substring(0, file.Length - ".disabled".Length)
            : file;

    public ModRecord? FindBySha(string? sha)
    {
        if (string.IsNullOrWhiteSpace(sha)) return null;
        return Records.FirstOrDefault(r => string.Equals(r.Sha512, sha, StringComparison.OrdinalIgnoreCase));
    }

    public ModRecord? Get(string mc, string file)
    {
        var f = Norm(file);
        return Records.FirstOrDefault(r =>
            string.Equals(r.Mc, mc, StringComparison.OrdinalIgnoreCase) &&
            string.Equals(r.File, f, StringComparison.OrdinalIgnoreCase));
    }

    public ModRecord Upsert(string mc, string file)
    {
        var r = Get(mc, file);
        if (r == null)
        {
            r = new ModRecord { Mc = mc, File = Norm(file) };
            Records.Add(r);
        }
        return r;
    }

    public void Remove(string mc, string file)
    {
        var f = Norm(file);
        Records.RemoveAll(r =>
            string.Equals(r.Mc, mc, StringComparison.OrdinalIgnoreCase) &&
            string.Equals(r.File, f, StringComparison.OrdinalIgnoreCase));
    }

    public IEnumerable<ModRecord> ForMc(string mc)
        => Records.Where(r => string.Equals(r.Mc, mc, StringComparison.OrdinalIgnoreCase));

    /// <summary>Records sharing a resolved project id (for duplicate detection by source project).</summary>
    public IEnumerable<ModRecord> ByProject(string mc, string projectId)
        => ForMc(mc).Where(r => string.Equals(r.ProjectId, projectId, StringComparison.OrdinalIgnoreCase));

    /// <summary>
    /// One-time migration from the legacy config.json bookkeeping
    /// (<c>downloaded_mod_files</c>: "projectId@mc" → filename). Seeds a minimal record so
    /// existing downloads keep their project link until the next hash-backfill fills the rest.
    /// Idempotent: never overwrites a record that already exists.
    /// </summary>
    public bool MigrateFrom(Dictionary<string, string> downloadedModFiles)
    {
        bool changed = false;
        foreach (var (key, file) in downloadedModFiles)
        {
            var at = key.LastIndexOf('@');
            if (at <= 0 || at >= key.Length - 1) continue;
            var pid = key.Substring(0, at);
            var mc = key.Substring(at + 1);
            if (Get(mc, file) != null) continue;
            var r = Upsert(mc, file);
            r.ProjectId = pid;
            r.Source = "modrinth";
            changed = true;
        }
        return changed;
    }
}
