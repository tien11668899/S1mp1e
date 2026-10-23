using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace S1mp1e.Services;

public sealed class CrashSuspect
{
    public string ModId { get; init; } = "";
    public string? ModName { get; set; }
    public string? JarPath { get; set; }
    public string Evidence { get; set; } = "";
}

public sealed class CrashDiagnosis
{
    public string Headline { get; set; } = "遊戲崩潰了";
    public string? Detail { get; set; }
    public List<CrashSuspect> Suspects { get; } = new();
    /// Mod ids some installed mod requires but that are not installed.
    public List<string> MissingDeps { get; } = new();
    public string? ReportPath { get; set; }
    public string? LogPath { get; set; }
    public bool GlassSuspected { get; set; }
    public bool OutOfMemory { get; set; }
    public bool NativeCrash { get; set; }
    public string? DriverHint { get; set; }
}

/// <summary>
/// Reads the crash report / latest.log / JVM hs_err a crashed run left behind and names the mod(s)
/// behind it: Fabric's incompatible-mod-set report, failed mixins, failed entrypoints, the crash
/// report's "Suspected Mods" line, legacy FML "Caught exception from", and — when only a broken
/// target class is named — which installed jar ships a mixin aimed at that class.
/// </summary>
public static class CrashDoctor
{
    private static readonly HashSet<string> NotMods = new(StringComparer.OrdinalIgnoreCase)
    {
        "minecraft", "java", "fabricloader", "fabric-loader", "mixinextras", "forge", "fml", "mcp",
        "ornithe", "none", "unknown", "mixin", "fabric-api-base",
    };

    private static readonly HashSet<string> GlassIds = new(StringComparer.OrdinalIgnoreCase) { "s1mp1e", "s1forge" };

    private const string Id = @"[A-Za-z0-9_.\-]+";
    private static readonly Regex RxInstall = new(@"^\s*-\s*Install (?<dep>" + Id + @")\b", RegexOptions.Multiline);
    private static readonly Regex RxRequires = new(
        @"Mod '(?<name>[^\r\n]+?)' \((?<id>" + Id + @")\)[^\r\n]*? requires [^\r\n]*? of (?:mod '(?<dname>[^\r\n]+?)' \((?<dep>" + Id + @")\)|(?<dep2>" + Id + @"))(?<rest>[^\r\n]*)");
    private static readonly Regex RxIncompat = new(
        @"Mod '(?<name>[^\r\n]+?)' \((?<id>" + Id + @")\)[^\r\n]*? is incompatible with [^\r\n]*?mod '(?<oname>[^\r\n]+?)' \((?<oid>" + Id + @")\)");
    private static readonly Regex RxMixinApply = new(@"Mixin apply for mod (?<id>" + Id + @") failed");
    private static readonly Regex RxFromMod = new(@"\.json:[\w.$]+ from mod (?<id>" + Id + @")\]");
    private static readonly Regex RxMixinTransform = new(@"Mixin transformation of (?<cls>[\w.$/]+) failed");
    private static readonly Regex RxEntrypoint = new(
        @"Could not execute entrypoint stage '(?<stage>\w+)' due to errors, provided by '(?<id>" + Id + @")'");
    private static readonly Regex RxProvidedBy = new(@"provided by '(?<id>" + Id + @")'");
    private static readonly Regex RxSuspected = new(@"Suspected Mods?:\s*(?<list>[^\r\n]+)");
    private static readonly Regex RxNameId = new(@"(?<name>[^,()]+?)\s*\((?<id>" + Id + @")\)");
    private static readonly Regex RxFmlCaught = new(@"Caught exception from (?<name>[^\r\n(]+?) \((?<id>" + Id + @")\)");
    private static readonly Regex RxDescription = new(@"^Description:\s*(?<d>.+)$", RegexOptions.Multiline);
    private static readonly Regex RxCrashSaved = new(@"Crash report saved to:\s*(?:#@!@#\s*)?(?<p>.+?\.txt)");

    /// <summary>Pull the crash-report path the game prints on stdout ("#@!@# Game crashed! Crash report saved to: …").</summary>
    public static string? ReportPathFromLine(string line)
    {
        var m = RxCrashSaved.Match(line);
        return m.Success ? m.Groups["p"].Value.Trim() : null;
    }

    /// <param name="gameDir">The instance directory the game ran in (crash-reports/, logs/, hs_err live here).</param>
    /// <param name="modDirs">Folders holding the mod jars, most authoritative first (the source folder before any copy).</param>
    /// <returns>Null when the run left nothing to read.</returns>
    public static CrashDiagnosis? Analyze(string gameDir, IReadOnlyList<string> modDirs, DateTime launchedAtUtc, string? reportHint)
    {
        var since = launchedAtUtc.AddSeconds(-5);
        string? report = reportHint is not null && File.Exists(reportHint) ? reportHint : NewestAfter(Path.Combine(gameDir, "crash-reports"), "crash-*.txt", since);
        string? log = Path.Combine(gameDir, "logs", "latest.log");
        if (!File.Exists(log) || File.GetLastWriteTimeUtc(log) < since) log = null;
        string? hsErr = NewestAfter(gameDir, "hs_err_pid*.log", since);
        if (report is null && log is null && hsErr is null) return null;

        var dx = new CrashDiagnosis { ReportPath = report ?? hsErr, LogPath = log };
        var reportText = ReadTail(report, 4 << 20);
        var logText = ReadTail(log, 6 << 20);
        var all = reportText + "\n" + logText;

        var suspects = new List<CrashSuspect>();
        void Suspect(string id, string? name, string evidence)
        {
            if (string.IsNullOrWhiteSpace(id) || NotMods.Contains(id)) return;
            if (GlassIds.Contains(id)) { dx.GlassSuspected = true; return; }
            var s = suspects.FirstOrDefault(x => string.Equals(x.ModId, id, StringComparison.OrdinalIgnoreCase));
            if (s is null) suspects.Add(new CrashSuspect { ModId = id, ModName = name?.Trim(), Evidence = evidence });
            else if (s.ModName is null && name is not null) s.ModName = name.Trim();
        }

        // Fabric "incompatible mod set": the strongest, most explicit signal.
        foreach (Match m in RxRequires.Matches(all))
        {
            var dep = m.Groups["dep"].Success ? m.Groups["dep"].Value : m.Groups["dep2"].Value;
            var depName = m.Groups["dname"].Success ? m.Groups["dname"].Value : dep;
            var rest = m.Groups["rest"].Value;
            if (rest.Contains("which is missing", StringComparison.OrdinalIgnoreCase))
            {
                if (!NotMods.Contains(dep) && !dx.MissingDeps.Contains(dep, StringComparer.OrdinalIgnoreCase)) dx.MissingDeps.Add(dep);
                Suspect(m.Groups["id"].Value, m.Groups["name"].Value, $"缺少前置模組 {depName}");
            }
            else if (rest.Contains("wrong version", StringComparison.OrdinalIgnoreCase))
            {
                Suspect(m.Groups["id"].Value, m.Groups["name"].Value, $"需要另一個版本的 {depName}");
            }
            else Suspect(m.Groups["id"].Value, m.Groups["name"].Value, $"與 {depName} 版本不合");
        }
        foreach (Match m in RxInstall.Matches(all))
        {
            var dep = m.Groups["dep"].Value;
            if (!NotMods.Contains(dep) && !GlassIds.Contains(dep) && !dx.MissingDeps.Contains(dep, StringComparer.OrdinalIgnoreCase))
                dx.MissingDeps.Add(dep);
        }
        foreach (Match m in RxIncompat.Matches(all))
        {
            Suspect(m.Groups["id"].Value, m.Groups["name"].Value, $"與 {m.Groups["oname"].Value} 不相容");
            Suspect(m.Groups["oid"].Value, m.Groups["oname"].Value, $"與 {m.Groups["name"].Value} 不相容");
        }

        foreach (Match m in RxEntrypoint.Matches(all))
            Suspect(m.Groups["id"].Value, null, $"模組初始化（{m.Groups["stage"].Value}）時出錯");
        foreach (Match m in RxMixinApply.Matches(all))
            Suspect(m.Groups["id"].Value, null, "Mixin 套用失敗");
        foreach (var line in ErrorLines(all))
            foreach (Match m in RxFromMod.Matches(line))
                Suspect(m.Groups["id"].Value, null, "Mixin 套用失敗");
        foreach (Match m in RxFmlCaught.Matches(all))
            Suspect(m.Groups["id"].Value, m.Groups["name"].Value, "載入時拋出例外");
        foreach (Match m in RxSuspected.Matches(reportText))
            foreach (Match p in RxNameId.Matches(m.Groups["list"].Value))
                Suspect(p.Groups["id"].Value, p.Groups["name"].Value, "崩潰報告列為嫌疑模組");
        if (suspects.Count == 0)
            foreach (var line in ErrorLines(all))
                foreach (Match m in RxProvidedBy.Matches(line))
                    Suspect(m.Groups["id"].Value, null, "崩潰發生在它的程式碼");

        // Map ids → jars (and names) from the installed mods.
        var jarsById = IndexJars(modDirs);
        foreach (var s in suspects)
        {
            if (jarsById.TryGetValue(s.ModId, out var j)) { s.JarPath = j.path; s.ModName ??= j.name; }
        }

        // Only a broken target class named: find which installed jar ships a mixin aimed at it.
        var tm = RxMixinTransform.Match(all);
        if (suspects.Count == 0 && tm.Success)
        {
            var internalName = tm.Groups["cls"].Value.Replace('.', '/');
            foreach (var (id, j) in jarsById.DistinctBy(kv => kv.Value.path))
            {
                if (GlassIds.Contains(id)) continue;
                if (JarTargetsClass(j.path, internalName))
                    suspects.Add(new CrashSuspect { ModId = id, ModName = j.name, JarPath = j.path,
                        Evidence = $"它的 Mixin 修改了出錯的 {ShortClass(internalName)}" });
            }
        }
        dx.Suspects.AddRange(suspects);

        if (all.Contains("java.lang.OutOfMemoryError", StringComparison.Ordinal)) dx.OutOfMemory = true;
        if (hsErr is not null) { dx.NativeCrash = true; dx.DriverHint = DriverFromHsErr(hsErr); }

        dx.Headline = HeadlineFor(dx, reportText, tm);
        var desc = RxDescription.Match(reportText);
        if (desc.Success) dx.Detail = desc.Groups["d"].Value.Trim();
        return dx;
    }

    private static string HeadlineFor(CrashDiagnosis dx, string reportText, Match transform)
    {
        if (dx.MissingDeps.Count > 0) return "缺少前置模組";
        if (dx.Suspects.Count > 0)
            return dx.Suspects.Count == 1 ? $"「{dx.Suspects[0].ModName ?? dx.Suspects[0].ModId}」導致遊戲崩潰" : "有幾個模組可能導致遊戲崩潰";
        if (dx.GlassSuspected) return "S1mp1e 液態玻璃模組出錯";
        if (dx.OutOfMemory) return "記憶體不足";
        if (dx.DriverHint is not null) return "顯示卡驅動崩潰";
        if (dx.NativeCrash) return "Java 原生崩潰";
        if (transform.Success) return $"修改 {ShortClass(transform.Groups["cls"].Value)} 時出錯";
        return "遊戲崩潰了";
    }

    private static string ShortClass(string cls)
    {
        var s = cls.Replace('/', '.');
        var i = s.LastIndexOf('.');
        return i >= 0 ? s[(i + 1)..] : s;
    }

    private static IEnumerable<string> ErrorLines(string text)
    {
        foreach (var raw in text.Split('\n'))
        {
            if (raw.Contains("Error", StringComparison.Ordinal) || raw.Contains("Exception", StringComparison.Ordinal)
                || raw.Contains("failed", StringComparison.OrdinalIgnoreCase) || raw.Contains("Caused by", StringComparison.Ordinal)
                || raw.Contains("Critical injection", StringComparison.OrdinalIgnoreCase))
                yield return raw;
        }
    }

    private static string? NewestAfter(string dir, string pattern, DateTime sinceUtc)
    {
        try
        {
            if (!Directory.Exists(dir)) return null;
            return new DirectoryInfo(dir).EnumerateFiles(pattern)
                .Where(f => f.LastWriteTimeUtc >= sinceUtc)
                .OrderByDescending(f => f.LastWriteTimeUtc)
                .Select(f => f.FullName).FirstOrDefault();
        }
        catch { return null; }
    }

    private static string ReadTail(string? path, int maxBytes)
    {
        if (path is null) return "";
        try
        {
            using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            if (fs.Length > maxBytes) fs.Seek(-maxBytes, SeekOrigin.End);
            using var r = new StreamReader(fs, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
            return r.ReadToEnd();
        }
        catch { return ""; }
    }

    private static Dictionary<string, (string path, string name)> IndexJars(IReadOnlyList<string> dirs)
    {
        var map = new Dictionary<string, (string, string)>(StringComparer.OrdinalIgnoreCase);
        foreach (var dir in dirs)
        {
            if (!Directory.Exists(dir)) continue;
            IEnumerable<string> files;
            try { files = Directory.EnumerateFiles(dir, "*.jar").ToList(); } catch { continue; }
            foreach (var f in files)
            {
                foreach (var (id, name) in ModIdsOf(f))
                    map.TryAdd(id, (f, name));
            }
        }
        return map;
    }

    /// <summary>Every id a jar answers to: its own id plus Fabric "provides" aliases, or the Forge/legacy modid.</summary>
    public static List<(string id, string name)> ModIdsOf(string jar)
    {
        var ids = new List<(string, string)>();
        try
        {
            using var zip = ZipFile.OpenRead(jar);
            var fmj = zip.GetEntry("fabric.mod.json");
            if (fmj is not null)
            {
                using var s = fmj.Open();
                using var doc = JsonDocument.Parse(s, new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
                var root = doc.RootElement;
                var id = root.TryGetProperty("id", out var ip) ? ip.GetString() ?? "" : "";
                var name = root.TryGetProperty("name", out var np) ? np.GetString() ?? id : id;
                if (id.Length > 0) ids.Add((id, name));
                if (root.TryGetProperty("provides", out var pv) && pv.ValueKind == JsonValueKind.Array)
                    foreach (var p in pv.EnumerateArray())
                        if (p.GetString() is { Length: > 0 } alias) ids.Add((alias, name));
                return ids;
            }
            var meta = LocalModScanner.ReadJarMeta(jar);
            if (meta is not null && meta.Id.Length > 0) ids.Add((meta.Id, meta.Name));
            var info = zip.GetEntry("mcmod.info");
            if (info is not null)
            {
                using var r = new StreamReader(info.Open());
                foreach (Match m in Regex.Matches(r.ReadToEnd(), "\"modid\"\\s*:\\s*\"([^\"]+)\""))
                    ids.Add((m.Groups[1].Value, meta?.Name ?? m.Groups[1].Value));
            }
        }
        catch { }
        return ids;
    }

    /// <summary>Does any class under this jar's mixin packages reference the target class (i.e. a mixin aimed at it)?</summary>
    private static bool JarTargetsClass(string jar, string internalName)
    {
        try
        {
            using var zip = ZipFile.OpenRead(jar);
            var packages = new List<string>();
            foreach (var e in zip.Entries)
            {
                if (!e.FullName.EndsWith(".json", StringComparison.OrdinalIgnoreCase) || e.FullName.Contains('/')) continue;
                if (!e.FullName.Contains("mixin", StringComparison.OrdinalIgnoreCase)) continue;
                try
                {
                    using var s = e.Open();
                    using var doc = JsonDocument.Parse(s, new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
                    if (doc.RootElement.TryGetProperty("package", out var p) && p.GetString() is { Length: > 0 } pkg)
                        packages.Add(pkg.Replace('.', '/') + "/");
                }
                catch { }
            }
            if (packages.Count == 0) return false;
            var needle = Encoding.UTF8.GetBytes(internalName);
            foreach (var e in zip.Entries)
            {
                if (!e.FullName.EndsWith(".class", StringComparison.Ordinal)) continue;
                if (!packages.Any(p => e.FullName.StartsWith(p, StringComparison.Ordinal))) continue;
                using var ms = new MemoryStream();
                using (var s = e.Open()) s.CopyTo(ms);
                if (ms.GetBuffer().AsSpan(0, (int)ms.Length).IndexOf(needle) >= 0) return true;
            }
        }
        catch { }
        return false;
    }

    private static string? DriverFromHsErr(string hsErr)
    {
        var text = ReadTail(hsErr, 1 << 20);
        var i = text.IndexOf("Problematic frame:", StringComparison.Ordinal);
        var frame = i >= 0 ? text.Substring(i, Math.Min(400, text.Length - i)).ToLowerInvariant() : text.ToLowerInvariant();
        if (frame.Contains("nvoglv") || frame.Contains("nvwgf2um") || frame.Contains("nvcuda")) return "NVIDIA";
        if (frame.Contains("atio6axx") || frame.Contains("atig6pxx") || frame.Contains("amdvlk") || frame.Contains("atiumd")) return "AMD";
        if (frame.Contains("ig9icd") || frame.Contains("ig7icd") || frame.Contains("ig75icd") || frame.Contains("igxelpicd") || frame.Contains("igdumd")) return "Intel";
        return null;
    }
}
