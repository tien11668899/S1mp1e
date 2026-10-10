using System;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace S1mp1e.Services;

/// <summary>
/// The CS2 knife pack (<c>&lt;.minecraft&gt;/s1mp1e-knives/</c>) the S1mp1e client mod's CS2 knife module draws from —
/// one shared copy for every game version (the mod walks up from <c>instances/&lt;mc&gt;</c> to find it).
///
/// <para>The pack is downloaded as one zip from the repo's fixed <c>knives-pack</c> release (a pre-release, so the
/// launcher's own update check on <c>releases/latest</c> never sees it). A small JSON manifest next to it carries the
/// pack version, zip size and SHA-256: the zip is streamed to a temp file, verified, extracted into a sibling folder
/// (zip-slip guarded) and swapped in, then the zip is deleted — so a failed or cancelled download never leaves a
/// half pack where the mod would find it.</para>
///
/// <para>The on/off switch is the mod's own <c>CS2Knife</c> module flag in the shared <c>modules.json</c>.</para>
/// </summary>
public static class KnifePackService
{
    public const string ManifestUrl =
        "https://github.com/tien11668899/S1mp1e/releases/download/knives-pack/s1mp1e-knives.json";
    /// <summary>Downloads are only accepted from this repo's release assets (GitHub redirects to its CDN).</summary>
    private const string AllowedPrefix = "https://github.com/tien11668899/S1mp1e/releases/download/";

    /// <summary>Game versions whose S1mp1e client carries the CS2 knife module.</summary>
    public static readonly string[] SupportedVersions = { "26.3", "26.2", "1.8.9" };

    public static bool Supports(string mc) => Array.IndexOf(SupportedVersions, mc) >= 0;

    public static string PackDir(string mcDir) => Path.Combine(mcDir, "s1mp1e-knives");

    public static bool Installed(string mcDir) => File.Exists(Path.Combine(PackDir(mcDir), "pack.json"));

    public sealed class Manifest
    {
        public int Version { get; set; }
        public string Url { get; set; } = "";
        public long Size { get; set; }
        public string Sha256 { get; set; } = "";
        public long Unpacked { get; set; }
    }

    private static readonly HttpClient Http = CreateHttp();

    private static HttpClient CreateHttp()
    {
        var h = new HttpClient { Timeout = Timeout.InfiniteTimeSpan };
        h.DefaultRequestHeaders.UserAgent.ParseAdd("S1mp1e-Launcher");
        return h;
    }

    /// <summary>Dev/test only: <c>S1MP1E_KNIFEPACK_MANIFEST</c> may point at a loopback server (http://127.0.0.1:…).</summary>
    private static string? TestManifest()
    {
        var v = Environment.GetEnvironmentVariable("S1MP1E_KNIFEPACK_MANIFEST");
        return !string.IsNullOrEmpty(v) && v.StartsWith("http://127.0.0.1:", StringComparison.Ordinal) ? v : null;
    }

    public static async Task<Manifest> FetchManifestAsync(CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(20));
        var test = TestManifest();
        var json = await Http.GetStringAsync(test ?? ManifestUrl, cts.Token);
        var o = JsonNode.Parse(json) as JsonObject ?? throw new InvalidDataException("刀包清單格式錯誤");
        var m = new Manifest
        {
            Version = o["version"]?.GetValue<int>() ?? 0,
            Url = o["url"]?.GetValue<string>() ?? "",
            Size = o["size"]?.GetValue<long>() ?? 0,
            Sha256 = (o["sha256"]?.GetValue<string>() ?? "").ToLowerInvariant(),
            Unpacked = o["unpacked"]?.GetValue<long>() ?? 0,
        };
        bool urlOk = m.Url.StartsWith(AllowedPrefix, StringComparison.Ordinal)
                     || (test is not null && m.Url.StartsWith("http://127.0.0.1:", StringComparison.Ordinal));
        if (!urlOk || m.Sha256.Length != 64 || m.Size <= 0)
            throw new InvalidDataException("刀包清單內容不正確");
        return m;
    }

    /// <summary>
    /// Download + verify + install the pack. <paramref name="progress"/> gets 0..1 for the download, then the
    /// extraction (reported as the second half after the bytes arrive). Throws on failure; the existing pack (if any)
    /// is left untouched until the new one is fully in place.
    /// </summary>
    public static async Task DownloadAsync(string mcDir, IProgress<(string phase, double frac)> progress, CancellationToken ct)
    {
        var m = await FetchManifestAsync(ct);
        Directory.CreateDirectory(mcDir);

        long need = m.Size + Math.Max(m.Unpacked, m.Size);
        try
        {
            var drive = new DriveInfo(Path.GetPathRoot(Path.GetFullPath(mcDir))!);
            if (drive.AvailableFreeSpace < need + 64L * 1024 * 1024)
                throw new IOException($"磁碟空間不足（需要約 {need / 1048576 + 64} MB）");
        }
        catch (IOException) { throw; }
        catch { /* can't query: try anyway */ }

        var zip = Path.Combine(mcDir, "s1mp1e-knives.download");
        var stage = Path.Combine(mcDir, "s1mp1e-knives.new");
        var old = Path.Combine(mcDir, "s1mp1e-knives.old");
        var dest = PackDir(mcDir);
        try
        {
            // 1) stream the zip, hashing as it arrives
            using (var resp = await Http.GetAsync(m.Url, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                resp.EnsureSuccessStatusCode();
                long total = resp.Content.Headers.ContentLength ?? m.Size;
                await using var src = await resp.Content.ReadAsStreamAsync(ct);
                await using var dst = new FileStream(zip, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 20, true);
                using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                var buf = new byte[1 << 20];
                long done = 0; int n; long lastTick = 0;
                while ((n = await src.ReadAsync(buf, ct)) > 0)
                {
                    await dst.WriteAsync(buf.AsMemory(0, n), ct);
                    sha.AppendData(buf, 0, n);
                    done += n;
                    long now = Environment.TickCount64;
                    if (now - lastTick > 100) { lastTick = now; progress.Report(("download", total > 0 ? (double)done / total : 0)); }
                }
                progress.Report(("download", 1));
                var got = Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant();
                if (got != m.Sha256) throw new InvalidDataException("下載的檔案校驗不符，請重試");
            }

            // 2) extract into a sibling stage folder (zip-slip guarded)
            if (Directory.Exists(stage)) Directory.Delete(stage, true);
            Directory.CreateDirectory(stage);
            var root = Path.GetFullPath(stage) + Path.DirectorySeparatorChar;
            await Task.Run(() =>
            {
                using var za = ZipFile.OpenRead(zip);
                int i = 0, count = za.Entries.Count;
                foreach (var e in za.Entries)
                {
                    ct.ThrowIfCancellationRequested();
                    var target = Path.GetFullPath(Path.Combine(stage, e.FullName));
                    if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("刀包內含不安全的路徑");
                    if (e.FullName.EndsWith("/") || e.FullName.EndsWith("\\")) { Directory.CreateDirectory(target); }
                    else
                    {
                        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                        e.ExtractToFile(target, true);
                    }
                    if ((++i & 15) == 0) progress.Report(("extract", (double)i / count));
                }
            }, ct);
            if (!File.Exists(Path.Combine(stage, "pack.json"))) throw new InvalidDataException("刀包內容不完整");
            File.WriteAllText(Path.Combine(stage, ".pack-version"), m.Version.ToString());

            // 3) swap in
            if (Directory.Exists(old)) Directory.Delete(old, true);
            if (Directory.Exists(dest)) Directory.Move(dest, old);
            Directory.Move(stage, dest);
            try { if (Directory.Exists(old)) Directory.Delete(old, true); } catch { }
            progress.Report(("extract", 1));
        }
        finally
        {
            try { if (File.Exists(zip)) File.Delete(zip); } catch { }
            try { if (Directory.Exists(stage)) Directory.Delete(stage, true); } catch { }
        }
    }

    // ---- the in-game module switch (shared modules.json, merging write) -------------------------------------

    private const string Module = "CS2Knife";

    /// <summary>Whether the client's CS2 knife module is on (null = never set: the module's default is off).</summary>
    public static bool? ReadEnabled(string mcDir)
    {
        try
        {
            var path = S1mp1eModConfig.SharedModulesJsonPath(mcDir);
            if (!File.Exists(path)) return null;
            if (JsonNode.Parse(File.ReadAllText(path)) is not JsonObject root) return null;
            return root["modules"]?[Module]?["enabled"]?.GetValue<bool>();
        }
        catch { return null; }
    }

    public static void WriteEnabled(string mcDir, bool on)
    {
        try
        {
            var path = S1mp1eModConfig.SharedModulesJsonPath(mcDir);
            JsonObject root;
            try
            {
                root = File.Exists(path)
                    ? (JsonNode.Parse(File.ReadAllText(path)) as JsonObject) ?? new JsonObject()
                    : new JsonObject();
            }
            catch { root = new JsonObject(); }
            root["version"] ??= 1;
            if (root["modules"] is not JsonObject mods) { mods = new JsonObject(); root["modules"] = mods; }
            if (mods[Module] is not JsonObject mod) { mod = new JsonObject(); mods[Module] = mod; }
            mod["enabled"] = on;   // its settings (knife / skin / boxing...) stay exactly as the mod wrote them

            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
            File.Move(tmp, path, overwrite: true);
        }
        catch { /* best effort — the in-game module toggle is the reliable path */ }
    }
}
