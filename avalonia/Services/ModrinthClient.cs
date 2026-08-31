using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace S1mp1e.Services;

public enum ModSort { Downloads, Updated, Relevance }

public sealed record ModHitDto(
    string ProjectId, string Slug, string Title, string Description,
    string Author, string? IconUrl, int Downloads,
    string[] Loaders);

// The full loader tag set Modrinth uses; anything else in `categories` is
// content categories (magic, adventure, …). Kept here so both the client and
// the caller filter the same way.
public static class ModrinthLoaders
{
    public static readonly HashSet<string> All = new(StringComparer.OrdinalIgnoreCase)
    {
        "fabric", "forge", "neoforge", "quilt", "liteloader", "modloader", "rift",
    };
}

public sealed record ModrinthFile(string Url, string Filename, string? Sha512, bool Primary);
public sealed record ModrinthDep(string? ProjectId, string DependencyType);
public sealed record ModrinthVersion(string Id, string VersionNumber, string VersionType, ModrinthFile[] Files, ModrinthDep[] Dependencies);

/// <summary>
/// Thin async wrapper over the Modrinth v2 API. Single process-wide HttpClient
/// (short-lived clients on Windows exhaust sockets), descriptive User-Agent
/// (Modrinth throttles anonymous UAs harder). All library methods use
/// <c>ConfigureAwait(false)</c>; the UI caller uses the default so mutation
/// of ObservableCollection lands back on the dispatcher.
/// </summary>
public static class ModrinthClient
{
    private const string Base = "https://api.modrinth.com/v2";

    private static readonly HttpClient _http;
    static ModrinthClient()
    {
        _http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        // ProductInfoHeaderValue rejects "author/product/version" (only one /); use
        // product/version + a comment part for contact info. Modrinth docs recommend
        // "GitHubUsername/ProjectName/version (contact)".
        _http.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("S1mp1e-Launcher", "0.1.0"));
        _http.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("(github.com/ispa20230501; ispa20230501@gmail.com)"));
    }

    private static readonly JsonSerializerOptions J = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    /// <summary>MC versions Modrinth doesn't index — mapped to a base Modrinth
    /// version so search + download still work. 26.2 is our internal preview
    /// built on the 1.21.x API, so we alias it to 1.21.1.</summary>
    // No aliases. 26.2 used to alias to 1.21.1 so its mod pane showed 1.21.1 mods —
    // but 26.2's internals differ enough that a 1.21.1 mod's mixins fail injection and
    // CRASH the game on launch. Aliasing a real, distinct version to another is wrong:
    // the downloaded jar is the WRONG version's file. 26.2 has no Modrinth-compatible
    // mods, so its pane is now (correctly) empty; it still runs its own glass-26.2.jar
    // (deployed separately, exact-matched by the launcher — unaffected by this map).
    private static readonly Dictionary<string, string> McAlias = new(StringComparer.OrdinalIgnoreCase);
    /// <summary>Modrinth search key for the UI-selected MC version — internal
    /// versions get aliased to their closest supported Modrinth base.</summary>
    public static string EffectiveMc(string mc) => McAlias.TryGetValue(mc, out var v) ? v : mc;
    // Kept as always-true; aliases handle the odd versions.
    public static bool McSupported(string mc) => true;

    /// <summary>Search projects. Loader is a `categories` facet on Modrinth (NOT `loaders:`).</summary>
    public static async Task<IReadOnlyList<ModHitDto>> SearchAsync(
        string query, string mcVersion, string loader, ModSort sort,
        int limit = 40, int offset = 0, CancellationToken ct = default)
    {
        mcVersion = EffectiveMc(mcVersion);

        // Facets: project_type=mod AND versions=<mc> AND categories=<loader>
        var facets = $"[[\"project_type:mod\"],[\"versions:{mcVersion}\"],[\"categories:{loader.ToLowerInvariant()}\"]]";
        var idx = sort switch
        {
            ModSort.Downloads => "downloads",
            ModSort.Updated => "updated",
            _ => "relevance",
        };
        var url = $"{Base}/search?query={Uri.EscapeDataString(query ?? "")}"
                + $"&limit={limit}&offset={offset}&index={idx}"
                + $"&facets={Uri.EscapeDataString(facets)}";

        using var resp = await _http.GetAsync(url, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode) return Array.Empty<ModHitDto>();
        await using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var doc = await JsonDocument.ParseAsync(s, cancellationToken: ct).ConfigureAwait(false);

        var hits = new List<ModHitDto>();
        if (!doc.RootElement.TryGetProperty("hits", out var arr)) return hits;
        foreach (var h in arr.EnumerateArray())
        {
            // Modrinth mashes loader tags and content categories into ONE `categories`
            // array (plus `display_categories`); we intersect with the known loader
            // set so downstream code can trust the list.
            var loaders = new List<string>();
            if (h.TryGetProperty("categories", out var cats) && cats.ValueKind == JsonValueKind.Array)
            {
                foreach (var c in cats.EnumerateArray())
                {
                    var tag = c.GetString();
                    if (!string.IsNullOrEmpty(tag) && ModrinthLoaders.All.Contains(tag))
                        loaders.Add(tag.ToLowerInvariant());
                }
            }
            hits.Add(new ModHitDto(
                ProjectId:  h.GetProperty("project_id").GetString() ?? "",
                Slug:       h.GetProperty("slug").GetString() ?? "",
                Title:      h.GetProperty("title").GetString() ?? "",
                Description:h.GetProperty("description").GetString() ?? "",
                Author:     h.TryGetProperty("author", out var au) ? au.GetString() ?? "" : "",
                IconUrl:    h.TryGetProperty("icon_url", out var iu) ? iu.GetString() : null,
                Downloads:  h.TryGetProperty("downloads", out var dl) ? dl.GetInt32() : 0,
                Loaders:    loaders.ToArray()));
        }
        return hits;
    }

    /// <summary>Pick the primary release version for this MC+loader.</summary>
    public static async Task<ModrinthVersion?> ResolvePrimaryVersionAsync(
        string projectIdOrSlug, string mcVersion, string loader, CancellationToken ct = default)
    {
        mcVersion = EffectiveMc(mcVersion);
        var url = $"{Base}/project/{Uri.EscapeDataString(projectIdOrSlug)}/version"
                + $"?loaders={Uri.EscapeDataString($"[\"{loader.ToLowerInvariant()}\"]")}"
                + $"&game_versions={Uri.EscapeDataString($"[\"{mcVersion}\"]")}";
        using var resp = await _http.GetAsync(url, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode) return null;
        await using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var doc = await JsonDocument.ParseAsync(s, cancellationToken: ct).ConfigureAwait(false);
        if (doc.RootElement.ValueKind != JsonValueKind.Array || doc.RootElement.GetArrayLength() == 0)
            return null;
        // Prefer version_type=release; else take [0].
        JsonElement pick = doc.RootElement[0];
        foreach (var v in doc.RootElement.EnumerateArray())
        {
            if (v.TryGetProperty("version_type", out var vt) && vt.GetString() == "release") { pick = v; break; }
        }
        var files = new List<ModrinthFile>();
        foreach (var f in pick.GetProperty("files").EnumerateArray())
        {
            string? sha = null;
            if (f.TryGetProperty("hashes", out var hh) && hh.TryGetProperty("sha512", out var sh)) sha = sh.GetString();
            files.Add(new ModrinthFile(
                Url: f.GetProperty("url").GetString() ?? "",
                Filename: f.GetProperty("filename").GetString() ?? "",
                Sha512: sha,
                Primary: f.TryGetProperty("primary", out var pr) && pr.GetBoolean()));
        }
        var deps = new List<ModrinthDep>();
        if (pick.TryGetProperty("dependencies", out var da) && da.ValueKind == JsonValueKind.Array)
        {
            foreach (var d in da.EnumerateArray())
            {
                deps.Add(new ModrinthDep(
                    ProjectId: d.TryGetProperty("project_id", out var pi) ? pi.GetString() : null,
                    DependencyType: d.TryGetProperty("dependency_type", out var dt) ? dt.GetString() ?? "" : ""));
            }
        }
        return new ModrinthVersion(
            Id: pick.GetProperty("id").GetString() ?? "",
            VersionNumber: pick.GetProperty("version_number").GetString() ?? "",
            VersionType: pick.TryGetProperty("version_type", out var vt2) ? vt2.GetString() ?? "" : "",
            Files: files.ToArray(),
            Dependencies: deps.ToArray());
    }

    /// <summary>Stream-download <paramref name="url"/> to <paramref name="destPath"/>.
    /// Writes to <c>dest.part</c> then atomically renames so a mid-transfer abort
    /// never leaves a truncated jar Fabric would refuse to load.</summary>
    public static async Task DownloadFileAsync(
        string url, string destPath, IProgress<double>? progress, CancellationToken ct = default)
    {
        var part = destPath + ".part";
        using var resp = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
        var total = resp.Content.Headers.ContentLength ?? -1L;
        await using (var net = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
        await using (var fs = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 64 * 1024, useAsync: true))
        {
            var buf = new byte[64 * 1024];
            long read = 0;
            int n;
            while ((n = await net.ReadAsync(buf.AsMemory(0, buf.Length), ct).ConfigureAwait(false)) > 0)
            {
                await fs.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                read += n;
                if (total > 0) progress?.Report((double)read / total);
            }
        }
        if (File.Exists(destPath)) File.Delete(destPath);
        File.Move(part, destPath);
    }

    /// <summary>Fetch an icon PNG as raw bytes; caller decodes on the UI thread.</summary>
    public static async Task<byte[]?> GetIconBytesAsync(string url, CancellationToken ct = default)
    {
        try
        {
            using var resp = await _http.GetAsync(url, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;
            return await resp.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
        }
        catch { return null; }
    }
}
