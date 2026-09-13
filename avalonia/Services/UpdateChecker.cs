using System;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using System.Threading.Tasks;

namespace S1mp1e.Services;

/// <summary>A newer release than the one running, resolved from GitHub.</summary>
public record UpdateInfo(string Version, string HtmlUrl, string? SetupUrl, string? Notes = null);

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
            string? setup = null;
            if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
            {
                foreach (var a in assets.EnumerateArray())
                {
                    var name = a.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                    if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
                        && name.Contains("setup", StringComparison.OrdinalIgnoreCase))
                    {
                        setup = a.TryGetProperty("browser_download_url", out var u) ? u.GetString() : null;
                        break;
                    }
                }
            }
            // Release notes (markdown body) → shown as the update banner's tooltip.
            string? notes = root.TryGetProperty("body", out var b) ? b.GetString() : null;
            if (!string.IsNullOrWhiteSpace(notes) && notes!.Length > 600) notes = notes.Substring(0, 600) + "…";
            return new UpdateInfo(latest, htmlUrl, setup, notes);
        }
        catch { return null; }
    }

    /// Download the installer to a temp path and return it, or null on failure.
    public static async Task<string?> DownloadSetupAsync(string url, string version)
    {
        try
        {
            var dst = Path.Combine(Path.GetTempPath(), $"S1mp1e-Setup-{version}.exe");
            using var http = MakeClient(TimeSpan.FromMinutes(5));
            var bytes = await http.GetByteArrayAsync(url).ConfigureAwait(false);
            await File.WriteAllBytesAsync(dst, bytes).ConfigureAwait(false);
            return dst;
        }
        catch { return null; }
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
