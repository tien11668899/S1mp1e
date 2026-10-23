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
    // The version this one replaced, kept under s1mp1e-mods/.rollback/<mc>/ so a bad update can be undone.
    [JsonPropertyName("prev_file")]       public string? PrevFile      { get; set; }
    [JsonPropertyName("prev_version")]    public string? PrevVersion   { get; set; }
    [JsonPropertyName("prev_version_id")] public string? PrevVersionId { get; set; }
    [JsonPropertyName("prev_sha512")]     public string? PrevSha512    { get; set; }
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

    public static string RollbackDir(string mcRoot, string mc)
        => System.IO.Path.Combine(mcRoot, "s1mp1e-mods", ".rollback", mc);

    public bool CanRollback(string mcRoot, ModRecord r)
        => !string.IsNullOrEmpty(r.PrevFile) && File.Exists(System.IO.Path.Combine(RollbackDir(mcRoot, r.Mc), r.PrevFile));

    /// <summary>
    /// Load the index. A missing file is an empty index. A file that cannot be READ right now (antivirus, another
    /// launcher saving) is retried briefly and otherwise yields an empty index that refuses to save — an I/O hiccup must
    /// not erase locks, rollback links and dependency marks. Only JSON that does not parse is replaced (the scan
    /// rebuilds it from the jars on disk), after keeping a copy.
    /// </summary>
    public static ModIndex Load(string mcRoot)
    {
        var path = IndexPath(mcRoot);
        ModIndex idx = new();
        for (int attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                idx = File.Exists(path)
                    ? JsonSerializer.Deserialize<ModIndex>(File.ReadAllText(path)) ?? new ModIndex()
                    : new ModIndex();
                break;
            }
            catch (JsonException)
            {
                try { File.Copy(path, path + ".corrupt", overwrite: true); } catch { }
                idx = new ModIndex();
                break;
            }
            catch (Exception) when (attempt < 4) { System.Threading.Thread.Sleep(40 * (attempt + 1)); }
            catch (Exception) { idx = new ModIndex { _readOnly = true }; }
        }
        idx.Path = path;
        return idx;
    }

    private bool _readOnly;

    public void Save()
    {
        if (string.IsNullOrEmpty(Path) || _readOnly) return;
        string? tmp = null;
        try
        {
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
            tmp = $"{Path}.{Environment.ProcessId}.tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(this, J));
            for (int attempt = 0; ; attempt++)
            {
                try { File.Move(tmp!, Path, overwrite: true); tmp = null; break; }
                catch (IOException) when (attempt < 4) { System.Threading.Thread.Sleep(40 * (attempt + 1)); }
            }
        }
        catch { /* best effort: the next scan rebuilds what is missing from the jars on disk */ }
        finally
        {
            if (tmp != null) try { File.Delete(tmp); } catch { }
        }
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

    /// <summary>A record of this version with this content — the same jar under another name in the same folder.</summary>
    public ModRecord? FindBySha(string mc, string? sha)
    {
        if (string.IsNullOrWhiteSpace(sha)) return null;
        return ForMc(mc).FirstOrDefault(r => string.Equals(r.Sha512, sha, StringComparison.OrdinalIgnoreCase));
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
    /// Idempotent: never overwrites a record that already exists. Entries whose jar is no longer on disk seed nothing
    /// (re-creating them made every scan "change" the index, and the index save re-triggered the scan) and are
    /// reported in <paramref name="staleKeys"/> so the caller can drop them from the legacy map.
    /// </summary>
    public bool MigrateFrom(Dictionary<string, string> downloadedModFiles, string mcRoot, List<string>? staleKeys = null)
    {
        bool changed = false;
        foreach (var (key, file) in downloadedModFiles)
        {
            var at = key.LastIndexOf('@');
            if (at <= 0 || at >= key.Length - 1) { staleKeys?.Add(key); continue; }
            var pid = key.Substring(0, at);
            var mc = key.Substring(at + 1);
            var onDisk = System.IO.Path.Combine(mcRoot, "s1mp1e-mods", mc, Norm(file));
            if (!File.Exists(onDisk) && !File.Exists(onDisk + ".disabled")) { staleKeys?.Add(key); continue; }
            if (Get(mc, file) != null) continue;
            var r = Upsert(mc, file);
            r.ProjectId = pid;
            r.Source = "modrinth";
            changed = true;
        }
        return changed;
    }
}
