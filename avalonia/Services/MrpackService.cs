using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace S1mp1e.Services;

/// <summary>
/// Export / import the current mod set as a Modrinth modpack (<c>.mrpack</c>) — the standard format Prism Launcher and
/// the Modrinth App also read and write, so a list exported here opens there and vice versa.
///
/// <para>Export: every jar Modrinth recognises (by sha512) becomes an index entry (download URL + hashes — tiny file);
/// anything Modrinth doesn't know (manual jars) is bundled verbatim under <c>overrides/mods/</c>, so the pack is
/// always complete.</para>
///
/// <para>Import: index files are downloaded (only from the hosts the mrpack spec allows) and verified against their
/// sha512 before being kept; <c>overrides/mods/</c> is extracted with zip-slip protection.</para>
/// </summary>
public static class MrpackService
{
    // The mrpack spec's download allow-list.
    private static readonly string[] AllowedHosts =
        { "cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com" };

    private static readonly JsonSerializerOptions Pretty = new() { WriteIndented = true };

    private static string EnabledName(string file)
        => file.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase)
            ? file.Substring(0, file.Length - ".disabled".Length) : file;

    private static bool IsAllowed(string url)
        => Uri.TryCreate(url, UriKind.Absolute, out var u)
        && u.Scheme == Uri.UriSchemeHttps
        && AllowedHosts.Any(h => u.Host.Equals(h, StringComparison.OrdinalIgnoreCase)
                              || u.Host.EndsWith("." + h, StringComparison.OrdinalIgnoreCase));

    public sealed record ExportResult(int Indexed, int Bundled, string OutPath);
    public sealed record ImportResult(int Downloaded, int Extracted, int Skipped, string? DeclaredMc,
        string? DeclaredLoader, IReadOnlyList<string> Failures);

    /// <summary>Write <paramref name="outPath"/> (.mrpack) from <paramref name="jarPaths"/>.</summary>
    public static async Task<ExportResult> ExportAsync(
        string outPath, string packName, string mc, string loaderKey, string? loaderVersion,
        IReadOnlyList<string> jarPaths, CancellationToken ct = default)
    {
        // Hash every jar (sha512 for the Modrinth lookup + index, sha1 also required by the spec).
        var info = new List<(string path, string sha512, string sha1, long size)>();
        foreach (var p in jarPaths.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            ct.ThrowIfCancellationRequested();
            if (!File.Exists(p)) continue;
            var bytes = await File.ReadAllBytesAsync(p, ct).ConfigureAwait(false);
            info.Add((p, Convert.ToHexString(SHA512.HashData(bytes)).ToLowerInvariant(),
                         Convert.ToHexString(SHA1.HashData(bytes)).ToLowerInvariant(), bytes.LongLength));
        }
        var hits = await ModrinthClient.LookupByHashesAsync(info.Select(i => i.sha512).ToList(), ct).ConfigureAwait(false);

        var files = new JsonArray();
        var bundle = new List<string>();
        foreach (var (path, sha512, sha1, size) in info)
        {
            var name = EnabledName(Path.GetFileName(path));
            if (hits.TryGetValue(sha512, out var hit) && !string.IsNullOrEmpty(hit.FileUrl) && IsAllowed(hit.FileUrl!))
            {
                files.Add(new JsonObject
                {
                    ["path"] = "mods/" + name,
                    ["hashes"] = new JsonObject { ["sha1"] = sha1, ["sha512"] = sha512 },
                    ["downloads"] = new JsonArray { hit.FileUrl! },
                    ["fileSize"] = size,
                });
            }
            else bundle.Add(path);   // manual / not on Modrinth → ship the jar itself
        }

        var deps = new JsonObject { ["minecraft"] = mc };
        var loaderDep = loaderKey.ToLowerInvariant() switch
        {
            "forge" => "forge", "neoforge" => "neoforge", "quilt" => "quilt-loader", _ => "fabric-loader",
        };
        deps[loaderDep] = string.IsNullOrEmpty(loaderVersion) ? "*" : loaderVersion;

        var index = new JsonObject
        {
            ["formatVersion"] = 1,
            ["game"] = "minecraft",
            ["versionId"] = DateTime.Now.ToString("yyyyMMdd"),
            ["name"] = string.IsNullOrWhiteSpace(packName) ? "S1mp1e Modpack" : packName,
            ["files"] = files,
            ["dependencies"] = deps,
        };

        var tmp = outPath + ".part";
        if (File.Exists(tmp)) File.Delete(tmp);
        await using (var fs = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None))
        using (var zip = new ZipArchive(fs, ZipArchiveMode.Create))
        {
            var ie = zip.CreateEntry("modrinth.index.json", CompressionLevel.Optimal);
            await using (var es = ie.Open())
                await JsonSerializer.SerializeAsync(es, index, Pretty, ct).ConfigureAwait(false);

            foreach (var p in bundle)
            {
                ct.ThrowIfCancellationRequested();
                var name = EnabledName(Path.GetFileName(p));
                var be = zip.CreateEntry("overrides/mods/" + name, CompressionLevel.Optimal);
                await using var src = File.OpenRead(p);
                await using var dst = be.Open();
                await src.CopyToAsync(dst, ct).ConfigureAwait(false);
            }
        }
        if (File.Exists(outPath)) File.Delete(outPath);
        File.Move(tmp, outPath);
        return new ExportResult(files.Count, bundle.Count, outPath);
    }

    /// <summary>
    /// Import a .mrpack into <paramref name="destModsDir"/>: download every index file (verified against its sha512) and
    /// extract <c>overrides/mods/</c>. Reports the pack's declared MC/loader so the caller can warn on a mismatch.
    /// </summary>
    public static async Task<ImportResult> ImportAsync(
        string mrpackPath, string destModsDir, IProgress<(int done, int total)>? progress = null, CancellationToken ct = default)
    {
        Directory.CreateDirectory(destModsDir);
        int downloaded = 0, extracted = 0, skipped = 0;
        string? declMc = null, declLoader = null;
        var failures = new List<string>();

        using var zip = ZipFile.OpenRead(mrpackPath);
        var indexEntry = zip.GetEntry("modrinth.index.json")
            ?? throw new InvalidDataException("這不是有效的 .mrpack（缺 modrinth.index.json）");

        JsonNode? index;
        await using (var s = indexEntry.Open()) index = await JsonNode.ParseAsync(s, cancellationToken: ct).ConfigureAwait(false);
        var root = index as JsonObject ?? throw new InvalidDataException("modrinth.index.json 格式錯誤");

        if (root["dependencies"] is JsonObject dep)
        {
            declMc = dep["minecraft"]?.GetValue<string>();
            foreach (var k in new[] { "fabric-loader", "forge", "neoforge", "quilt-loader" })
                if (dep[k] != null) { declLoader = k.Replace("-loader", ""); break; }
        }

        var fileArr = root["files"] as JsonArray ?? new JsonArray();
        int total = fileArr.Count;
        int done = 0;
        foreach (var fnode in fileArr)
        {
            ct.ThrowIfCancellationRequested();
            done++; progress?.Report((done, total));
            if (fnode is not JsonObject fo) { skipped++; continue; }
            var path = fo["path"]?.GetValue<string>() ?? "";
            // Only mods are in scope (resourcepacks/shaders/config are out of this launcher's mod folder).
            if (!path.StartsWith("mods/", StringComparison.OrdinalIgnoreCase)) { skipped++; continue; }
            var name = Path.GetFileName(path);
            if (string.IsNullOrEmpty(name) || name.Contains("..")) { skipped++; continue; }

            var urls = (fo["downloads"] as JsonArray)?.Select(x => x?.GetValue<string>() ?? "").Where(IsAllowed).ToList()
                       ?? new List<string>();
            var wantSha = (fo["hashes"] as JsonObject)?["sha512"]?.GetValue<string>();
            if (urls.Count == 0) { failures.Add(name + "（下載來源不被允許）"); continue; }

            var dst = Path.Combine(destModsDir, name);
            bool ok = false;
            foreach (var url in urls)
            {
                try
                {
                    await ModrinthClient.DownloadFileAsync(url, dst, null, ct).ConfigureAwait(false);
                    if (!string.IsNullOrEmpty(wantSha))
                    {
                        await using var hs = File.OpenRead(dst);
                        var got = Convert.ToHexString(await SHA512.HashDataAsync(hs, ct).ConfigureAwait(false)).ToLowerInvariant();
                        if (!string.Equals(got, wantSha, StringComparison.OrdinalIgnoreCase))
                        { try { File.Delete(dst); } catch { } continue; }   // corrupt / wrong file → try next url
                    }
                    ok = true; break;
                }
                catch { /* try next url */ }
            }
            if (ok) downloaded++; else failures.Add(name + "（下載失敗）");
        }

        // overrides/mods/* → the mod folder, with zip-slip protection.
        var destFull = Path.GetFullPath(destModsDir);
        foreach (var e in zip.Entries)
        {
            ct.ThrowIfCancellationRequested();
            var p = e.FullName.Replace('\\', '/');
            if (!p.StartsWith("overrides/mods/", StringComparison.OrdinalIgnoreCase)) continue;
            var name = Path.GetFileName(p);
            if (string.IsNullOrEmpty(name)) continue;   // directory entry
            var outPath = Path.GetFullPath(Path.Combine(destModsDir, name));
            if (!outPath.StartsWith(destFull, StringComparison.OrdinalIgnoreCase)) continue;   // zip-slip guard
            try
            {
                await using var src = e.Open();
                await using var dst = new FileStream(outPath, FileMode.Create, FileAccess.Write, FileShare.None);
                await src.CopyToAsync(dst, ct).ConfigureAwait(false);
                extracted++;
            }
            catch (Exception ex) { failures.Add(name + "（解壓失敗：" + ex.Message + "）"); }
        }

        return new ImportResult(downloaded, extracted, skipped, declMc, declLoader, failures);
    }
}
