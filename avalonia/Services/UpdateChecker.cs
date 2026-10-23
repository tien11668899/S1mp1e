using System;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using System.Threading.Tasks;

namespace S1mp1e.Services;

/// <summary>A newer release than the one running, resolved from GitHub.</summary>
public record UpdateInfo(string Version, string HtmlUrl, string? SetupUrl, string? Notes = null, string? SetupSha256 = null);

/// <summary>
/// Checks GitHub Releases for a launcher update. Best-effort and non-blocking:
/// every failure (no network, rate-limit, malformed JSON) returns null so the
/// launcher never nags or stalls on startup.
/// </summary>
public static class UpdateChecker
{
    private const string Repo = "tien11668899/S1mp1e";
    private const string LatestApi = "https://api.github.com/repos/" + Repo + "/releases/latest";

    /// The running app's version as "Major.Minor.Patch", from the assembly &lt;Version&gt;.
    public static string CurrentVersion()
    {
        var v = Assembly.GetExecutingAssembly().GetName().Version ?? new Version(0, 0, 0);
        return $"{v.Major}.{v.Minor}.{v.Build}";
    }

    /// Return the latest release if it is newer than the running build, else null.
    public static async Task<UpdateInfo?> CheckAsync()
    {
        try
        {
            using var http = MakeClient(TimeSpan.FromSeconds(8));
            var json = await http.GetStringAsync(LatestApi).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            var tag = root.TryGetProperty("tag_name", out var t) ? t.GetString() ?? "" : "";
            var latest = tag.TrimStart('v', 'V');
            if (latest.Length == 0 || !IsNewer(latest, CurrentVersion())) return null;

            var htmlUrl = root.TryGetProperty("html_url", out var h) ? h.GetString() ?? "" : "";

            // Prefer the Inno Setup installer asset ("*Setup*.exe") for a one-click,
            // no-admin in-place upgrade; fall back to opening the release page.
            string? setup = null, sha256 = null;
            if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
            {
                foreach (var a in assets.EnumerateArray())
                {
                    var name = a.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                    if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
                        && name.Contains("setup", StringComparison.OrdinalIgnoreCase))
                    {
                        setup = a.TryGetProperty("browser_download_url", out var u) ? u.GetString() : null;
                        // GitHub publishes each asset's digest as "sha256:<hex>"; when present the download is verified.
                        if (a.TryGetProperty("digest", out var d) && d.GetString() is { } dg
                            && dg.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase))
                            sha256 = dg.Substring("sha256:".Length).Trim().ToLowerInvariant();
                        break;
                    }
                }
            }
            // Release notes (markdown body) → shown as the update banner's tooltip.
            string? notes = root.TryGetProperty("body", out var b) ? b.GetString() : null;
            if (!string.IsNullOrWhiteSpace(notes) && notes!.Length > 600) notes = notes.Substring(0, 600) + "…";
            return new UpdateInfo(latest, htmlUrl, setup, notes, sha256);
        }
        catch { return null; }
    }

    /// Our own folder under %TEMP% for downloaded installers, so they can be found and removed again.
    private static string UpdateDir => Path.Combine(Path.GetTempPath(), "S1mp1e-update");

    /// <summary>
    /// Download the installer and return its path, or null on failure. Streams into a temp file in
    /// <see cref="UpdateDir"/>, checks the length and — when GitHub published one — the SHA-256 digest, and only then
    /// renames it to the final name, so a broken or truncated installer is never run. Nothing is left behind on failure.
    /// </summary>
    public static async Task<string?> DownloadSetupAsync(string url, string version, string? sha256 = null,
                                                         IProgress<double>? progress = null)
    {
        string? part = null;
        try
        {
            Directory.CreateDirectory(UpdateDir);
            var dst = Path.Combine(UpdateDir, $"S1mp1e-Setup-{version}.exe");
            part = dst + "." + Guid.NewGuid().ToString("N").Substring(0, 8) + ".part";
            using var http = MakeClient(TimeSpan.FromMinutes(10));
            http.DefaultRequestHeaders.Accept.Clear();
            using var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
            resp.EnsureSuccessStatusCode();
            long total = resp.Content.Headers.ContentLength ?? -1L, read = 0;
            await using (var net = await resp.Content.ReadAsStreamAsync().ConfigureAwait(false))
            await using (var fs = new FileStream(part, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1 << 16, useAsync: true))
            {
                var buf = new byte[1 << 16];
                int n;
                while ((n = await net.ReadAsync(buf.AsMemory(0, buf.Length)).ConfigureAwait(false)) > 0)
                {
                    await fs.WriteAsync(buf.AsMemory(0, n)).ConfigureAwait(false);
                    read += n;
                    if (total > 0) progress?.Report((double)read / total);
                }
            }
            if (total > 0 && read != total) return null;
            if (!string.IsNullOrEmpty(sha256))
            {
                await using var check = File.OpenRead(part);
                var got = Convert.ToHexString(await System.Security.Cryptography.SHA256.HashDataAsync(check).ConfigureAwait(false));
                if (!string.Equals(got, sha256, StringComparison.OrdinalIgnoreCase)) return null;
            }
            File.Move(part, dst, overwrite: true);
            part = null;
            return dst;
        }
        catch { return null; }
        finally
        {
            if (part != null) try { File.Delete(part); } catch { }
        }
    }

    /// <summary>
    /// Remove installers left by earlier updates (in our folder, and the old <c>%TEMP%\S1mp1e-Setup-*.exe</c> location).
    /// The running launcher is already the result of any installer that ran, so none of them is needed any more; a file
    /// still locked (the installer that just relaunched us is finishing) is skipped and goes next time.
    /// </summary>
    public static void CleanupOldInstallers()
    {
        try
        {
            if (Directory.Exists(UpdateDir))
                foreach (var f in Directory.EnumerateFiles(UpdateDir))
                    try { File.Delete(f); } catch { }
            foreach (var f in Directory.EnumerateFiles(Path.GetTempPath(), "S1mp1e-Setup-*.exe"))
                try { File.Delete(f); } catch { }
        }
        catch { /* best effort */ }
    }

    private static HttpClient MakeClient(TimeSpan timeout)
    {
        var http = new HttpClient { Timeout = timeout };
        // GitHub's API rejects requests without a User-Agent.
        http.DefaultRequestHeaders.UserAgent.ParseAdd("S1mp1e-Launcher");
        http.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return http;
    }

    /// True when semver <paramref name="latest"/> &gt; <paramref name="current"/>.
    /// Lenient: parses the first three dot/dash/plus-separated numbers; non-numeric
    /// or missing parts count as 0, and anything unparseable is treated as "not newer".
    private static bool IsNewer(string latest, string current)
    {
        static int[] Parse(string s)
        {
            var parts = s.Split('.', '-', '+');
            var o = new int[3];
            for (int i = 0; i < 3 && i < parts.Length; i++) int.TryParse(parts[i], out o[i]);
            return o;
        }
        var a = Parse(latest);
        var b = Parse(current);
        for (int i = 0; i < 3; i++)
            if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }
}
