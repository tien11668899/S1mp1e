using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Media.Imaging;

namespace S1mp1e.Services;

public sealed record LocalMod(
    string JarPath,
    string Id,
    string Name,
    string Description,
    byte[]? IconBytes,
    bool Enabled,
    string Loader = "",        // "fabric" | "forge" | "" (unknown) — which descriptor parsed
    string? Version = null,    // literal version string, null if absent/placeholder
    string Folder = "",        // the directory this jar sits in (which of the 3 mod folders)
    long Size = 0,             // bytes
    string? Sha512 = null);    // lowercase hex sha512 of the jar content (for Modrinth lookup + dedup)

/// <summary>
/// Scans <c>&lt;mcRoot&gt;/mods</c> for Fabric jars (both <c>.jar</c> and the
/// <c>.jar.disabled</c> soft-off form Prism/MultiMC use). Reads the mod's own
/// <c>fabric.mod.json</c> for id/name/description/icon so we don't need
/// Modrinth round-trips for installed mods.
///
/// Returns raw icon bytes rather than decoding to Bitmap here — decoding must
/// happen on the UI thread. Caller does <c>new Bitmap(new MemoryStream(bytes))</c>
/// on the dispatcher.
/// </summary>
public static class LocalModScanner
{
    /// <param name="computeHash">When true, the sha512 of each jar is computed (for Modrinth
    /// identification + dedup). Slightly slower; callers that only need names can pass false.</param>
    public static async Task<List<LocalMod>> ScanAsync(string mcRoot, string? mcVersion = null,
        bool computeHash = true, CancellationToken ct = default)
    {
        var result = new List<LocalMod>();
        if (string.IsNullOrWhiteSpace(mcRoot)) return result;
        // Three folders: the vanilla mods/ dir (manual drops), s1mp1e-mods/ (legacy flat
        // writes), and s1mp1e-mods/<mc>/ (per-version downloads). Every copy is returned —
        // the UI groups duplicates and marks the ones outside the actual launch folder, so we
        // deliberately do NOT cross-folder dedupe here any more.
        foreach (var sub in new[] { "mods", "s1mp1e-mods" })
        {
            var dir = Path.Combine(mcRoot, sub);
            if (!Directory.Exists(dir)) continue;
            await ScanDirAsync(dir, result, computeHash, ct).ConfigureAwait(false);
        }
        if (!string.IsNullOrWhiteSpace(mcVersion))
        {
            var perMc = Path.Combine(mcRoot, "s1mp1e-mods", mcVersion);
            if (Directory.Exists(perMc)) await ScanDirAsync(perMc, result, computeHash, ct).ConfigureAwait(false);
        }
        result.Sort((a, b) => string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase));
        return result;
    }

    private static async Task ScanDirAsync(string dir, List<LocalMod> result, bool computeHash, CancellationToken ct)
    {
        await Task.Yield();
        foreach (var f in Directory.EnumerateFiles(dir))
        {
            ct.ThrowIfCancellationRequested();
            var name = Path.GetFileName(f);
            bool enabled;
            if (name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) enabled = true;
            else if (name.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase)) enabled = false;
            else continue;
            // Skip our injected glass jar — it's an internal artifact, not a user mod.
            if (name.StartsWith("glass-", StringComparison.OrdinalIgnoreCase)) continue;

            long size = 0;
            try { size = new FileInfo(f).Length; } catch { }

            LocalMod? mod = null;
            try
            {
                await using var fs = File.OpenRead(f);
                using var zip = new ZipArchive(fs, ZipArchiveMode.Read, leaveOpen: false);
                mod = ReadFabricMeta(zip, f, enabled);
                mod ??= ReadForgeMeta(zip, f, enabled);
            }
            catch { /* corrupt jar — skip */ }

            string? sha = null;
            if (computeHash)
            {
                try
                {
                    await using var hs = File.OpenRead(f);
                    using var sha512 = System.Security.Cryptography.SHA512.Create();
                    var hash = await sha512.ComputeHashAsync(hs, ct).ConfigureAwait(false);
                    sha = Convert.ToHexString(hash).ToLowerInvariant();
                }
                catch { }
            }

            // Even unreadable jars should still appear so the user can toggle them off.
            mod ??= new LocalMod(
                JarPath: f,
                Id: Path.GetFileNameWithoutExtension(name),
                Name: Path.GetFileNameWithoutExtension(name),
                Description: "",
                IconBytes: null,
                Enabled: enabled);
            result.Add(mod with { Folder = dir, Size = size, Sha512 = sha });
        }
    }

    /// <summary>Read one jar's mod descriptor (Fabric, then Forge/NeoForge). Null if it isn't a recognisable mod.</summary>
    public static LocalMod? ReadJarMeta(string jarPath)
    {
        try
        {
            using var fs = File.OpenRead(jarPath);
            using var zip = new ZipArchive(fs, ZipArchiveMode.Read, leaveOpen: false);
            var enabled = !jarPath.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase);
            var mod = ReadFabricMeta(zip, jarPath, enabled) ?? ReadForgeMeta(zip, jarPath, enabled);
            if (mod != null) return mod with { Folder = Path.GetDirectoryName(jarPath) ?? "" };
            // Legacy Forge (1.8.9–1.12.2) ships mcmod.info instead of mods.toml — still a mod.
            if (zip.GetEntry("mcmod.info") != null)
                return new LocalMod(jarPath, Path.GetFileNameWithoutExtension(jarPath),
                                    Path.GetFileNameWithoutExtension(jarPath), "", null, enabled, "forge",
                                    Folder: Path.GetDirectoryName(jarPath) ?? "");
        }
        catch { }
        return null;
    }

    private static LocalMod? ReadFabricMeta(ZipArchive zip, string jarPath, bool enabled)
    {
        var entry = zip.GetEntry("fabric.mod.json");
        if (entry == null) return null;
        using var s = entry.Open();
        using var doc = JsonDocument.Parse(s, new JsonDocumentOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
        var root = doc.RootElement;
        var id   = root.TryGetProperty("id",          out var idp) ? idp.GetString() ?? "" : "";
        var name = root.TryGetProperty("name",        out var np)  ? np.GetString()  ?? id : id;
        var desc = root.TryGetProperty("description", out var dp)  ? dp.GetString()  ?? "" : "";
        // Version for conflict detection. An unresolved Loom placeholder ("${version}")
        // can't be compared, so treat it as unknown.
        var ver  = root.TryGetProperty("version",     out var vp)  ? vp.GetString() : null;
        if (ver != null && ver.Contains("${")) ver = null;

        byte[]? iconBytes = null;
        if (root.TryGetProperty("icon", out var ip))
        {
            string? path = ip.ValueKind switch
            {
                JsonValueKind.String => ip.GetString(),
                // Some mods use size-map: { "128": "path.png", ... } — pick the largest.
                JsonValueKind.Object => ip.EnumerateObject()
                                          .Select(p => p.Value.GetString())
                                          .LastOrDefault(v => !string.IsNullOrEmpty(v)),
                _ => null,
            };
            if (!string.IsNullOrEmpty(path))
            {
                var iconEntry = zip.GetEntry(path);
                if (iconEntry != null)
                {
                    using var ms = new MemoryStream();
                    using (var ic = iconEntry.Open()) ic.CopyTo(ms);
                    iconBytes = ms.ToArray();
                }
            }
        }
        return new LocalMod(jarPath, id, name, desc, iconBytes, enabled, "fabric", ver);
    }

    // Minimal Forge mods.toml sniffer — we don't parse TOML, just pull out the
    // displayName / description via naive line scan so at least the name shows.
    private static LocalMod? ReadForgeMeta(ZipArchive zip, string jarPath, bool enabled)
    {
        var entry = zip.GetEntry("META-INF/mods.toml") ?? zip.GetEntry("META-INF/neoforge.mods.toml");
        if (entry == null) return null;
        using var reader = new StreamReader(entry.Open());
        var text = reader.ReadToEnd();
        static string Pull(string src, string key)
        {
            var idx = src.IndexOf(key + "=", StringComparison.Ordinal);
            if (idx < 0) return "";
            var line = src.Substring(idx).Split('\n')[0];
            var q1 = line.IndexOf('"'); var q2 = line.LastIndexOf('"');
            return (q1 >= 0 && q2 > q1) ? line.Substring(q1 + 1, q2 - q1 - 1) : "";
        }
        var name = Pull(text, "displayName");
        var id   = Pull(text, "modId");
        var desc = Pull(text, "description");
        var ver  = Pull(text, "version");
        if (ver.Contains("${")) ver = "";      // ${file.jarVersion} placeholder → unknown
        if (string.IsNullOrEmpty(name)) name = Path.GetFileNameWithoutExtension(jarPath);
        return new LocalMod(jarPath, id, name, desc, null, enabled, "forge",
                            string.IsNullOrEmpty(ver) ? null : ver);
    }

    /// <summary>Toggle a jar between enabled/disabled by renaming
    /// <c>foo.jar</c> ↔ <c>foo.jar.disabled</c>. Returns the new path.</summary>
    public static string SetEnabled(string jarPath, bool enable)
    {
        var isDisabled = jarPath.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase);
        if (enable == !isDisabled) return jarPath;
        var target = enable
            ? jarPath.Substring(0, jarPath.Length - ".disabled".Length)
            : jarPath + ".disabled";
        if (File.Exists(target)) File.Delete(target);
        File.Move(jarPath, target);
        return target;
    }
}
