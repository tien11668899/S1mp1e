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

    public sealed record ExportResult(int Indexed, int Bundled, string OutPath, int Packs = 0);
    public sealed record ImportResult(int Downloaded, int Extracted, int Skipped, string? DeclaredMc,
        string? DeclaredLoader, IReadOnlyList<string> Failures, int Packs = 0, string? SettingsPath = null, IReadOnlyList<string>? ImportedMods = null);

    /// The S1mp1e in-game settings travel inside the pack at this path (other launchers just copy it along, harmlessly).
    private const string SettingsEntry = "overrides/s1mp1e/modules.json";

    /// <summary>Write <paramref name="outPath"/> (.mrpack) from <paramref name="jarPaths"/>, optionally with resource
    /// packs and the S1mp1e in-game settings file (the "share everything with a friend" pack).</summary>
    public static async Task<ExportResult> ExportAsync(
        string outPath, string packName, string mc, string loaderKey, string? loaderVersion,
        IReadOnlyList<string> jarPaths, CancellationToken ct = default,
        IReadOnlyList<string>? resourcePacks = null, string? s1mp1eSettings = null)
    {
        // Hash every file (sha512 for the Modrinth lookup + index, sha1 also required by the spec).
        var info = new List<(string path, string folder, string sha512, string sha1, long size)>();
        async Task Add(IEnumerable<string> paths, string folder)
        {
            foreach (var p in paths.Distinct(StringComparer.OrdinalIgnoreCase))
            {
                ct.ThrowIfCancellationRequested();
                if (!File.Exists(p)) continue;
                var bytes = await File.ReadAllBytesAsync(p, ct).ConfigureAwait(false);
                info.Add((p, folder, Convert.ToHexString(SHA512.HashData(bytes)).ToLowerInvariant(),
                             Convert.ToHexString(SHA1.HashData(bytes)).ToLowerInvariant(), bytes.LongLength));
            }
        }
        await Add(jarPaths, "mods").ConfigureAwait(false);
        if (resourcePacks is not null) await Add(resourcePacks, "resourcepacks").ConfigureAwait(false);
        var hits = await ModrinthClient.LookupByHashesAsync(info.Select(i => i.sha512).ToList(), ct).ConfigureAwait(false);

        var files = new JsonArray();
        var bundle = new List<(string path, string folder)>();
        int modsIndexed = 0, packs = 0;
        foreach (var (path, folder, sha512, sha1, size) in info)
        {
            if (folder == "resourcepacks") packs++;
            var name = EnabledName(Path.GetFileName(path));
            if (hits.TryGetValue(sha512, out var hit) && !string.IsNullOrEmpty(hit.FileUrl) && IsAllowed(hit.FileUrl!))
            {
                files.Add(new JsonObject
                {
                    ["path"] = folder + "/" + name,
                    ["hashes"] = new JsonObject { ["sha1"] = sha1, ["sha512"] = sha512 },
                    ["downloads"] = new JsonArray { hit.FileUrl! },
                    ["fileSize"] = size,
                });
                if (folder == "mods") modsIndexed++;
            }
            else bundle.Add((path, folder));   // manual / not on Modrinth → ship the file itself
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
        try
        {
            await using (var fs = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None))
            using (var zip = new ZipArchive(fs, ZipArchiveMode.Create))
            {
                var ie = zip.CreateEntry("modrinth.index.json", CompressionLevel.Optimal);
                await using (var es = ie.Open())
                    await JsonSerializer.SerializeAsync(es, index, Pretty, ct).ConfigureAwait(false);
    
                foreach (var (p, folder) in bundle)
                {
                    ct.ThrowIfCancellationRequested();
                    var name = EnabledName(Path.GetFileName(p));
                    var be = zip.CreateEntry($"overrides/{folder}/{name}", CompressionLevel.Optimal);
                    await using var src = File.OpenRead(p);
                    await using var dst = be.Open();
                    await src.CopyToAsync(dst, ct).ConfigureAwait(false);
                }
                if (s1mp1eSettings is not null && File.Exists(s1mp1eSettings))
                {
                    var se = zip.CreateEntry(SettingsEntry, CompressionLevel.Optimal);
                    await using var src = new FileStream(s1mp1eSettings, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                    await using var dst = se.Open();
                    await src.CopyToAsync(dst, ct).ConfigureAwait(false);
                }
            }
            File.Move(tmp, outPath, overwrite: true);
        }
        catch
        {
            // A failed or cancelled export leaves no half-written pack behind.
            try { File.Delete(tmp); } catch { }
            throw;
        }
        return new ExportResult(modsIndexed, bundle.Count(b => b.folder == "mods"), outPath, packs);
    }

    /// <summary>
    /// Import a .mrpack into <paramref name="destModsDir"/>: download every index file (verified against its sha512) and
    /// extract <c>overrides/mods/</c>. Reports the pack's declared MC/loader so the caller can warn on a mismatch.
    /// </summary>
    /// <param name="contentRoot">The .minecraft folder: when given, resource packs go to its resourcepacks/ and the
    /// S1mp1e settings file is extracted to a temp path (<see cref="ImportResult.SettingsPath"/>) for the caller to offer.</param>
    public static async Task<ImportResult> ImportAsync(
        string mrpackPath, string destModsDir, IProgress<(int done, int total)>? progress = null, CancellationToken ct = default,
        string? contentRoot = null)
    {
        Directory.CreateDirectory(destModsDir);
        var packDir = contentRoot is null ? null : Path.Combine(contentRoot, "resourcepacks");
        int downloaded = 0, extracted = 0, skipped = 0, packs = 0;
        string? settingsPath = null;
        string? declMc = null, declLoader = null;
        var failures = new List<string>();
        var imported = new List<string>();

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
            bool isPack = packDir is not null && path.StartsWith("resourcepacks/", StringComparison.OrdinalIgnoreCase);
            if (!isPack && !path.StartsWith("mods/", StringComparison.OrdinalIgnoreCase)) { skipped++; continue; }
            string name;
            try { name = ModrinthClient.SafeFileName(Path.GetFileName(path)); }
            catch (ArgumentException) { skipped++; continue; }

            var urls = (fo["downloads"] as JsonArray)?.Select(x => x?.GetValue<string>() ?? "").Where(IsAllowed).ToList()
                       ?? new List<string>();
            var wantSha = (fo["hashes"] as JsonObject)?["sha512"]?.GetValue<string>();
            if (urls.Count == 0) { failures.Add(name + "（下載來源不被允許）"); continue; }

            if (isPack) Directory.CreateDirectory(packDir!);
            var dst = Path.Combine(isPack ? packDir! : destModsDir, name);
            bool ok = false;
            string? lastError = null;
            foreach (var url in urls)
            {
                try
                {
                    // The download verifies the sha512 itself before the file takes its final name, and keeps an
                    // identical file already there without downloading it again.
                    await ModrinthClient.DownloadFileAsync(url, dst, null, ct, wantSha).ConfigureAwait(false);
                    ok = true; break;
                }
                catch (OperationCanceledException) { throw; }
                catch (FileInUseException) { lastError = "檔案正被遊戲使用"; break; }
                catch (Exception ex) { lastError = ex is System.Net.Http.HttpRequestException ? "下載失敗" : ex.Message; }
            }
            if (!ok) failures.Add($"{name}（{lastError ?? "下載失敗"}）");
            else if (isPack) packs++;
            else { downloaded++; imported.Add(dst); }
        }

        // overrides/mods/* → the mod folder, overrides/resourcepacks/* → resourcepacks, with zip-slip protection.
        foreach (var e in zip.Entries)
        {
            ct.ThrowIfCancellationRequested();
            var p = e.FullName.Replace('\\', '/');
            if (contentRoot is not null && p.Equals(SettingsEntry, StringComparison.OrdinalIgnoreCase))
            {
                settingsPath = Path.Combine(Path.GetTempPath(), $"s1mp1e-shared-modules-{Guid.NewGuid():N}.json");
                e.ExtractToFile(settingsPath, true);
                continue;
            }
            bool pack = packDir is not null && p.StartsWith("overrides/resourcepacks/", StringComparison.OrdinalIgnoreCase);
            if (!pack && !p.StartsWith("overrides/mods/", StringComparison.OrdinalIgnoreCase)) continue;
            var name = Path.GetFileName(p);
            if (string.IsNullOrEmpty(name)) continue;   // directory entry
            var dir = pack ? packDir! : destModsDir;
            Directory.CreateDirectory(dir);
            var dirFull = Path.GetFullPath(dir).TrimEnd('\\', '/') + Path.DirectorySeparatorChar;
            var outPath = Path.GetFullPath(Path.Combine(dir, name));
            if (!outPath.StartsWith(dirFull, StringComparison.OrdinalIgnoreCase)) continue;   // zip-slip guard
            // Extract under a temp name and rename: an interrupted import never leaves a truncated jar that would load.
            var tmp = outPath + ".s1part";
            try
            {
                await using (var src = e.Open())
                await using (var dst = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None))
                    await src.CopyToAsync(dst, ct).ConfigureAwait(false);
                if (FileLocks.IsLocked(outPath)) throw new FileInUseException(outPath);
                File.Move(tmp, outPath, overwrite: true);
                if (pack) packs++; else { extracted++; imported.Add(outPath); }
            }
            catch (OperationCanceledException) { try { File.Delete(tmp); } catch { } throw; }
            catch (Exception ex)
            {
                try { File.Delete(tmp); } catch { }
                failures.Add(name + (ex is FileInUseException ? "（檔案正被遊戲使用）" : "（解壓失敗：" + ex.Message + "）"));
            }
        }

        return new ImportResult(downloaded, extracted, skipped, declMc, declLoader, failures, packs, settingsPath, imported);
    }
}
