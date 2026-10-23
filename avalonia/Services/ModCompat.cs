using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text.Json;

namespace S1mp1e.Services;

/// <summary>What one Fabric jar declares: the ids it answers to (incl. nested jar-in-jar mods) and what it depends on.</summary>
public sealed class FabricModInfo
{
    public string Path { get; init; } = "";
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    /// The version the jar declares (fabric.mod.json "version"), as written.
    public string Version { get; init; } = "";
    public HashSet<string> Provides { get; } = new(StringComparer.OrdinalIgnoreCase);
    /// dependency id → version predicate(s) as written (any one may match).
    public Dictionary<string, List<string>> Depends { get; } = new(StringComparer.OrdinalIgnoreCase);
}

public static class ModCompat
{
    /// Ids the loader / game itself provides — never "missing".
    private static readonly HashSet<string> Builtin = new(StringComparer.OrdinalIgnoreCase)
    {
        "minecraft", "java", "fabricloader", "fabric-loader", "mixinextras", "mixin",
    };

    /// <summary>
    /// Whether <paramref name="id"/> is part of Fabric API (installed as the one "fabric-api" project): "fabric",
    /// "fabric-api", "fabric-api-base", "fabric-renderer-indigo" and the versioned modules "fabric-…-v1". Mods that
    /// merely share the prefix — fabric-language-kotlin, lucko's fabric-permissions-api-v0 — are separate projects.
    /// </summary>
    public static bool IsFabricApiModule(string id)
    {
        id = id.ToLowerInvariant();
        if (id is "fabric" or "fabric-api" or "fabric-api-base" or "fabric-renderer-indigo") return true;
        if (id is "fabric-permissions-api-v0") return false;
        return id.StartsWith("fabric-", StringComparison.Ordinal)
               && System.Text.RegularExpressions.Regex.IsMatch(id, @"-v\d+$");
    }

    public static FabricModInfo? Read(string jarPath)
    {
        try
        {
            using var zip = ZipFile.OpenRead(jarPath);
            return ReadZip(zip, jarPath);
        }
        catch { return null; }
    }

    private static FabricModInfo? ReadZip(ZipArchive zip, string path, int depth = 0)
    {
        var e = zip.GetEntry("fabric.mod.json");
        if (e is null) return null;
        FabricModInfo info;
        using (var s = e.Open())
        using (var doc = JsonDocument.Parse(s, new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip }))
        {
            var root = doc.RootElement;
            var id = root.TryGetProperty("id", out var ip) ? ip.GetString() ?? "" : "";
            info = new FabricModInfo { Path = path, Id = id,
                Name = root.TryGetProperty("name", out var np) ? np.GetString() ?? id : id,
                Version = root.TryGetProperty("version", out var vp) && vp.ValueKind == JsonValueKind.String ? vp.GetString() ?? "" : "" };
            if (id.Length > 0) info.Provides.Add(id);
            if (root.TryGetProperty("provides", out var pv) && pv.ValueKind == JsonValueKind.Array)
                foreach (var p in pv.EnumerateArray()) if (p.GetString() is { Length: > 0 } a) info.Provides.Add(a);
            if (root.TryGetProperty("depends", out var dp) && dp.ValueKind == JsonValueKind.Object)
                foreach (var d in dp.EnumerateObject())
                {
                    var preds = new List<string>();
                    if (d.Value.ValueKind == JsonValueKind.String) preds.Add(d.Value.GetString() ?? "*");
                    else if (d.Value.ValueKind == JsonValueKind.Array)
                        foreach (var x in d.Value.EnumerateArray()) preds.Add(x.GetString() ?? "*");
                    info.Depends[d.Name] = preds;
                }
        }
        // Jar-in-jar: bundled libraries satisfy dependencies too (fabric-api modules, cloth config, …).
        if (depth < 2)
        {
            foreach (var n in zip.Entries.Where(x => x.FullName.StartsWith("META-INF/jars/", StringComparison.Ordinal)
                                                     && x.FullName.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)))
            {
                try
                {
                    using var ms = new MemoryStream();
                    using (var ns = n.Open()) ns.CopyTo(ms);
                    ms.Position = 0;
                    using var inner = new ZipArchive(ms, ZipArchiveMode.Read);
                    var sub = ReadZip(inner, path, depth + 1);
                    if (sub is not null) info.Provides.UnionWith(sub.Provides);
                }
                catch { }
            }
        }
        return info;
    }

    /// <summary>Enabled Fabric jars in these folders (non-recursive).</summary>
    public static List<FabricModInfo> ReadFolder(IEnumerable<string> dirs)
    {
        var list = new List<FabricModInfo>();
        foreach (var d in dirs)
        {
            if (!Directory.Exists(d)) continue;
            foreach (var f in Directory.EnumerateFiles(d, "*.jar"))
                if (Read(f) is { } i) list.Add(i);
        }
        return list;
    }

    /// <summary>Required ids no jar (nor the loader) provides, with the mods that need them.</summary>
    public static List<(string dep, List<string> neededBy)> MissingDependencies(List<FabricModInfo> mods, IEnumerable<string>? alsoProvided = null)
    {
        var have = new HashSet<string>(Builtin, StringComparer.OrdinalIgnoreCase);
        foreach (var m in mods) have.UnionWith(m.Provides);
        if (alsoProvided is not null) have.UnionWith(alsoProvided);
        var missing = new Dictionary<string, List<string>>(StringComparer.OrdinalIgnoreCase);
        foreach (var m in mods)
            foreach (var dep in m.Depends.Keys)
                if (!have.Contains(dep))
                {
                    if (!missing.TryGetValue(dep, out var l)) missing[dep] = l = new List<string>();
                    l.Add(m.Name);
                }
        return missing.Select(kv => (kv.Key, kv.Value)).ToList();
    }

    /// <summary>Null when the jar accepts this Minecraft version (or says nothing / can't be parsed), else what it asks for.</summary>
    public static string? MinecraftMismatch(FabricModInfo m, string mc)
    {
        if (!m.Depends.TryGetValue("minecraft", out var preds) || preds.Count == 0) return null;
        bool? any = null;
        foreach (var p in preds)
        {
            var r = Matches(p, mc);
            if (r is null) return null;          // unparseable: never cry wolf
            any = (any ?? false) || r.Value;
        }
        return any == false ? string.Join(" 或 ", preds) : null;
    }

    /// Fabric version predicates: space-separated AND of "*", "x"-wildcards, "=", ">", ">=", "<", "<=", "~", "^".
    public static bool? Matches(string predicate, string version)
    {
        var v = Parse(version);
        if (v is null) return null;
        foreach (var raw in predicate.Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
            var t = raw.Trim();
            if (t == "*") continue;
            string op = "";
            foreach (var o in new[] { ">=", "<=", ">", "<", "=", "~", "^" })
                if (t.StartsWith(o, StringComparison.Ordinal)) { op = o; t = t[o.Length..]; break; }
            if (t.Contains('x', StringComparison.OrdinalIgnoreCase) || t.Contains('*'))
            {
                var parts = t.Split('.');
                for (int i = 0; i < parts.Length; i++)
                {
                    if (parts[i] is "x" or "X" or "*") break;
                    if (!int.TryParse(parts[i], out var n)) return null;
                    if ((i < v.Length ? v[i] : 0) != n) return false;
                }
                continue;
            }
            var w = Parse(t);
            if (w is null) return null;
            int c = Compare(v, w);
            if (c == 0) c = ComparePre(PreOf(version), PreOf(t));   // same core: 1.0.0 > 1.0.0-beta.2 > 1.0.0-beta.1
            bool ok = op switch
            {
                ">=" => c >= 0, "<=" => c <= 0, ">" => c > 0, "<" => c < 0,
                "~" => c >= 0 && Compare(v, Bump(w, 1)) < 0,
                "^" => c >= 0 && Compare(v, Bump(w, 0)) < 0,
                _ => c == 0,
            };
            if (!ok) return false;
        }
        return true;
    }

    private static int[] Bump(int[] w, int idx)
    {
        var r = new int[Math.Max(w.Length, idx + 1)];
        Array.Copy(w, r, w.Length);
        r[idx]++;
        for (int i = idx + 1; i < r.Length; i++) r[i] = 0;
        return r.Take(idx + 1).ToArray();
    }

    private static int[]? Parse(string s)
    {
        var core = s.Split('-', '+')[0];
        var parts = core.Split('.');
        var r = new int[parts.Length];
        for (int i = 0; i < parts.Length; i++)
            if (!int.TryParse(parts[i], out r[i])) return null;
        return r;
    }

    private static int Compare(int[] a, int[] b)
    {
        for (int i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            int x = i < a.Length ? a[i] : 0, y = i < b.Length ? b[i] : 0;
            if (x != y) return x.CompareTo(y);
        }
        return 0;
    }

    /// <summary>The pre-release part of a semantic version ("1.0.0-beta.1+mc26.3" → "beta.1"), "" for a release.</summary>
    private static string PreOf(string s)
    {
        var noBuild = s.Split('+')[0];
        var dash = noBuild.IndexOf('-');
        return dash < 0 ? "" : noBuild[(dash + 1)..];
    }

    /// <summary>Semver pre-release order: a release outranks any pre-release; identifiers compare numerically when both are numbers.</summary>
    private static int ComparePre(string a, string b)
    {
        if (a.Length == 0 && b.Length == 0) return 0;
        if (a.Length == 0) return 1;    // a is the release
        if (b.Length == 0) return -1;
        var x = a.Split('.'); var y = b.Split('.');
        for (int i = 0; i < Math.Min(x.Length, y.Length); i++)
        {
            bool nx = int.TryParse(x[i], out var ix), ny = int.TryParse(y[i], out var iy);
            int c = nx && ny ? ix.CompareTo(iy) : nx ? -1 : ny ? 1 : string.CompareOrdinal(x[i], y[i]);
            if (c != 0) return c;
        }
        return x.Length.CompareTo(y.Length);
    }

    /// <summary>
    /// Dependencies that ARE installed but in a version the dependent does not accept — e.g. Reese's Sodium Options
    /// 2.2.6-beta.1 asking for Sodium ≥ 0.9.3-beta.1 while 0.9.2 is installed (the game refuses to start). Only
    /// top-level jars with a declared version are judged; the loader, Minecraft and Java are checked elsewhere.
    /// </summary>
    public static List<(string dep, string depName, string need, string have, List<string> neededBy)> UnmetVersions(List<FabricModInfo> mods)
    {
        var byId = new Dictionary<string, FabricModInfo>(StringComparer.OrdinalIgnoreCase);
        foreach (var m in mods)
            if (m.Id.Length > 0 && m.Version.Length > 0) byId[m.Id] = m;
        var unmet = new Dictionary<string, (string depName, string need, string have, List<string> by)>(StringComparer.OrdinalIgnoreCase);
        foreach (var m in mods)
            foreach (var (dep, preds) in m.Depends)
            {
                if (Builtin.Contains(dep) || !byId.TryGetValue(dep, out var provider)) continue;
                bool? ok = false;
                foreach (var p in preds)
                {
                    var r = Matches(p, provider.Version);
                    if (r is null || r == true) { ok = true; break; }   // unparsable: give it the benefit of the doubt
                }
                if (ok == true) continue;
                if (!unmet.TryGetValue(dep, out var u))
                    unmet[dep] = u = (provider.Name, string.Join(" 或 ", preds), provider.Version, new List<string>());
                u.by.Add(m.Name);
            }
        return unmet.Select(kv => (kv.Key, kv.Value.depName, kv.Value.need, kv.Value.have, kv.Value.by)).ToList();
    }
}
