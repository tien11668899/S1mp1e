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
public sealed record ModrinthVersion(string Id, string VersionNumber, string VersionType, ModrinthFile[] Files, ModrinthDep[] Dependencies, string ProjectId = "");

/// <summary>Result of a sha512 → Modrinth lookup: which project/version a jar is.</summary>
public sealed record ModrinthHashHit(string Hash, string ProjectId, string VersionId, string VersionNumber, string VersionType,
    string[]? GameVersions = null, string[]? Loaders = null, string? FileUrl = null);

/// <summary>Minimal project metadata (name/slug/icon) for batch id → display resolution.</summary>
public sealed record ModrinthProject(string Id, string Slug, string Title, string? IconUrl);

/// <summary>A candidate version for the update check, newest-published first per channel.</summary>
public sealed record ModrinthVersionInfo(string Id, string VersionNumber, string VersionType, DateTimeOffset Published, ModrinthFile[] Files, ModrinthDep[] Dependencies);

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
        int limit = 40, int offset = 0, CancellationToken ct = default, string? category = null,
        string projectType = "mod")
    {
        mcVersion = EffectiveMc(mcVersion);

        // Facets: project_type AND versions=<mc> [AND categories=<loader>, only for mods] [AND categories=<content category>].
        // Modrinth ANDs across the outer groups and ORs within one. Resource/shader/datapacks have no loader, so the
        // loader facet is added only for mods (adding it elsewhere returns nothing).
        var facets = $"[[\"project_type:{projectType}\"],[\"versions:{mcVersion}\"]"
                   + (projectType == "mod" ? $",[\"categories:{loader.ToLowerInvariant()}\"]" : "")
                   + (string.IsNullOrEmpty(category) ? "" : $",[\"categories:{category}\"]")
                   + "]";
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
        string projectIdOrSlug, string mcVersion, string loader, CancellationToken ct = default, bool loaderAgnostic = false)
    {
        mcVersion = EffectiveMc(mcVersion);
        var url = $"{Base}/project/{Uri.EscapeDataString(projectIdOrSlug)}/version"
                + (loaderAgnostic ? "" : $"?loaders={Uri.EscapeDataString($"[\"{loader.ToLowerInvariant()}\"]")}")
                + (loaderAgnostic ? "?" : "&") + $"game_versions={Uri.EscapeDataString($"[\"{mcVersion}\"]")}";
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
        return ParseVersion(pick);
    }

    /// <summary>
    /// One exact version by id (<c>GET /version/{id}</c>). Updates install the version the update check chose, so the
    /// check and the download can never disagree (e.g. a beta found with "include prereleases" on, then a release-preferring
    /// resolve picking the installed release again — the loop that kept 有更新 lit forever).
    /// </summary>
    public static async Task<ModrinthVersion?> GetVersionAsync(string versionId, CancellationToken ct = default)
    {
        using var resp = await _http.GetAsync($"{Base}/version/{Uri.EscapeDataString(versionId)}", ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode) return null;
        await using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var doc = await JsonDocument.ParseAsync(s, cancellationToken: ct).ConfigureAwait(false);
        return doc.RootElement.ValueKind == JsonValueKind.Object ? ParseVersion(doc.RootElement) : null;
    }

    private static ModrinthVersion ParseVersion(JsonElement pick)
    {
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
            Dependencies: deps.ToArray(),
            ProjectId: pick.TryGetProperty("project_id", out var pj) ? pj.GetString() ?? "" : "");
    }

    /// <summary>Downloads currently writing a temp file; orphan cleanup only runs while this is 0.</summary>
    public static int ActiveDownloads => Volatile.Read(ref _activeDownloads);
    private static int _activeDownloads;

    /// <summary>The suffix of in-flight download temp files (<c>name.jar.&lt;random&gt;.part</c>).</summary>
    public const string PartSuffix = ".part";

    /// <summary>
    /// Stream-download <paramref name="url"/> to <paramref name="destPath"/>, safely:
    /// <list type="bullet">
    ///   <item>an existing file whose SHA-512 already equals <paramref name="expectedSha512"/> is kept and nothing is
    ///   downloaded (returns false);</item>
    ///   <item>the bytes go to a uniquely named <c>.part</c> file next to the target, are checked against
    ///   <paramref name="expectedSha512"/> when Modrinth supplied one, and only then replace the target in one atomic
    ///   rename (MoveFileEx REPLACE_EXISTING) — a failed or cancelled transfer never touches the existing jar;</item>
    ///   <item>the <c>.part</c> file is removed on every failure path, so no stray copies are left behind;</item>
    ///   <item>a target held open by another process (on Windows: a running game with the jar loaded) raises
    ///   <see cref="FileInUseException"/> so the caller can say so instead of failing silently.</item>
    /// </list>
    /// Returns true when the file was written.
    /// </summary>
    public static async Task<bool> DownloadFileAsync(
        string url, string destPath, IProgress<double>? progress, CancellationToken ct = default, string? expectedSha512 = null)
    {
        if (!string.IsNullOrEmpty(expectedSha512) && File.Exists(destPath)
            && string.Equals(await Sha512OfAsync(destPath, ct).ConfigureAwait(false), expectedSha512, StringComparison.OrdinalIgnoreCase))
        {
            progress?.Report(1.0);
            return false;
        }
        if (File.Exists(destPath) && FileLocks.IsLocked(destPath)) throw new FileInUseException(destPath);

        var part = destPath + "." + Guid.NewGuid().ToString("N").Substring(0, 8) + PartSuffix;
        Interlocked.Increment(ref _activeDownloads);
        try
        {
            using (var resp = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false))
            {
                resp.EnsureSuccessStatusCode();
                var total = resp.Content.Headers.ContentLength ?? -1L;
                await using var net = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
                await using var fs = new FileStream(part, FileMode.CreateNew, FileAccess.Write, FileShare.None, 64 * 1024, useAsync: true);
                var buf = new byte[64 * 1024];
                long read = 0;
                int n;
                while ((n = await net.ReadAsync(buf.AsMemory(0, buf.Length), ct).ConfigureAwait(false)) > 0)
                {
                    await fs.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                    read += n;
                    if (total > 0) progress?.Report((double)read / total);
                }
                if (total > 0 && read != total)
                    throw new IOException($"下載不完整：收到 {read} / {total} 位元組");
            }
            if (!string.IsNullOrEmpty(expectedSha512))
            {
                var got = await Sha512OfAsync(part, ct).ConfigureAwait(false);
                if (!string.Equals(got, expectedSha512, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("下載的檔案校驗失敗（SHA-512 不符），已捨棄");
            }
            try
            {
                File.Move(part, destPath, overwrite: true);
            }
            catch (IOException ex) when (FileLocks.IsSharingViolation(ex) || FileLocks.IsLocked(destPath))
            {
                throw new FileInUseException(destPath, ex);
            }
            catch (UnauthorizedAccessException ex) when (FileLocks.IsLocked(destPath))
            {
                throw new FileInUseException(destPath, ex);
            }
            return true;
        }
        finally
        {
            Interlocked.Decrement(ref _activeDownloads);
            try { if (File.Exists(part)) File.Delete(part); } catch { /* the orphan sweep gets it next scan */ }
        }
    }

    /// <summary>Lower-case hex SHA-512 of a file (the form Modrinth uses).</summary>
    public static async Task<string> Sha512OfAsync(string path, CancellationToken ct = default)
    {
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 64 * 1024, useAsync: true);
        var hash = await System.Security.Cryptography.SHA512.HashDataAsync(fs, ct).ConfigureAwait(false);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    /// <summary>
    /// A file name from network data (Modrinth "filename", a modpack path segment) that is safe to put inside our folder:
    /// no directory parts, no "..", no characters Windows rejects. Throws instead of silently rewriting, so a malformed
    /// listing can never write outside the mods folder.
    /// </summary>
    public static string SafeFileName(string name)
    {
        if (string.IsNullOrWhiteSpace(name) || name != Path.GetFileName(name) || name is "." or ".."
            || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
            throw new InvalidDataException($"不安全的檔名：{name}");
        return name;
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

    /// <summary>The changelog (Markdown) of one Modrinth version, or null.</summary>
    public static async Task<string?> GetChangelogAsync(string versionId, CancellationToken ct = default)
    {
        try
        {
            using var resp = await _http.GetAsync($"{Base}/version/{Uri.EscapeDataString(versionId)}", ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;
            await using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            using var doc = await JsonDocument.ParseAsync(s, cancellationToken: ct).ConfigureAwait(false);
            return doc.RootElement.TryGetProperty("changelog", out var c) ? c.GetString() : null;
        }
        catch { return null; }
    }

    /// <summary>
    /// Identify jars by their sha512 against Modrinth's <c>/version_files</c>. Returns a map
    /// keyed by the LOWERCASE hex hash for every jar Modrinth recognises (manual drops get
    /// identified here). Hashes Modrinth doesn't know are simply absent. Batched.
    /// </summary>
    public static async Task<Dictionary<string, ModrinthHashHit>> LookupByHashesAsync(
        IReadOnlyList<string> sha512Hashes, CancellationToken ct = default)
    {
        var outMap = new Dictionary<string, ModrinthHashHit>(StringComparer.OrdinalIgnoreCase);
        var hashes = sha512Hashes.Where(h => !string.IsNullOrWhiteSpace(h))
                                 .Select(h => h.ToLowerInvariant()).Distinct().ToList();
        if (hashes.Count == 0) return outMap;
        for (int i = 0; i < hashes.Count; i += 100)
        {
            ct.ThrowIfCancellationRequested();
            var chunk = hashes.Skip(i).Take(100).ToList();
            var body = JsonSerializer.Serialize(new { hashes = chunk, algorithm = "sha512" });
            using var req = new HttpRequestMessage(HttpMethod.Post, $"{Base}/version_files")
            {
                Content = new StringContent(body, System.Text.Encoding.UTF8, "application/json"),
            };
            using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) continue;
            await using var st = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            using var doc = await JsonDocument.ParseAsync(st, cancellationToken: ct).ConfigureAwait(false);
            if (doc.RootElement.ValueKind != JsonValueKind.Object) continue;
            foreach (var prop in doc.RootElement.EnumerateObject())
            {
                var v = prop.Value;
                try
                {
                    outMap[prop.Name] = new ModrinthHashHit(
                        Hash: prop.Name,
                        ProjectId: v.GetProperty("project_id").GetString() ?? "",
                        VersionId: v.GetProperty("id").GetString() ?? "",
                        VersionNumber: v.GetProperty("version_number").GetString() ?? "",
                        VersionType: v.TryGetProperty("version_type", out var vt) ? vt.GetString() ?? "" : "",
                        GameVersions: v.TryGetProperty("game_versions", out var gv) && gv.ValueKind == JsonValueKind.Array
                            ? gv.EnumerateArray().Select(x => x.GetString() ?? "").Where(x => x.Length > 0).ToArray() : null,
                        Loaders: v.TryGetProperty("loaders", out var lo) && lo.ValueKind == JsonValueKind.Array
                            ? lo.EnumerateArray().Select(x => x.GetString() ?? "").Where(x => x.Length > 0).ToArray() : null,
                        FileUrl: FileUrlForHash(v, prop.Name));
                }
                catch { }
            }
        }
        return outMap;
    }

    // The download URL of the file (inside a version object) whose sha512 equals `hash`.
    private static string? FileUrlForHash(JsonElement version, string hash)
    {
        try
        {
            if (!version.TryGetProperty("files", out var fa) || fa.ValueKind != JsonValueKind.Array) return null;
            foreach (var f in fa.EnumerateArray())
                if (f.TryGetProperty("hashes", out var hh) && hh.TryGetProperty("sha512", out var sh)
                    && string.Equals(sh.GetString(), hash, StringComparison.OrdinalIgnoreCase))
                    return f.TryGetProperty("url", out var u) ? u.GetString() : null;
        }
        catch { }
        return null;
    }

    /// <summary>Batch project id/slug → name/icon via <c>/projects?ids=[...]</c>.</summary>
    public static async Task<Dictionary<string, ModrinthProject>> GetProjectsAsync(
        IReadOnlyList<string> ids, CancellationToken ct = default)
    {
        var map = new Dictionary<string, ModrinthProject>(StringComparer.OrdinalIgnoreCase);
        var list = ids.Where(x => !string.IsNullOrWhiteSpace(x)).Distinct().ToList();
        if (list.Count == 0) return map;
        for (int i = 0; i < list.Count; i += 100)
        {
            ct.ThrowIfCancellationRequested();
            var chunk = list.Skip(i).Take(100);
            var idsJson = JsonSerializer.Serialize(chunk);
            var url = $"{Base}/projects?ids={Uri.EscapeDataString(idsJson)}";
            using var resp = await _http.GetAsync(url, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) continue;
            await using var st = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            using var doc = await JsonDocument.ParseAsync(st, cancellationToken: ct).ConfigureAwait(false);
            if (doc.RootElement.ValueKind != JsonValueKind.Array) continue;
            foreach (var p in doc.RootElement.EnumerateArray())
            {
                try
                {
                    var id = p.GetProperty("id").GetString() ?? "";
                    var slug = p.TryGetProperty("slug", out var sl) ? sl.GetString() ?? "" : "";
                    var title = p.TryGetProperty("title", out var ti) ? ti.GetString() ?? slug : slug;
                    var icon = p.TryGetProperty("icon_url", out var ic) ? ic.GetString() : null;
                    var pr = new ModrinthProject(id, slug, title, icon);
                    if (!string.IsNullOrEmpty(id))   map[id]   = pr;
                    if (!string.IsNullOrEmpty(slug)) map[slug] = pr;
                }
                catch { }
            }
        }
        return map;
    }

    /// <summary>
    /// Every version of a project for this MC+loader, newest-published first — for the update
    /// check. <paramref name="includePrerelease"/> keeps beta/alpha; otherwise only release.
    /// </summary>
    public static async Task<IReadOnlyList<ModrinthVersionInfo>> GetProjectVersionsAsync(
        string projectIdOrSlug, string mcVersion, string loader, bool includePrerelease, CancellationToken ct = default)
    {
        mcVersion = EffectiveMc(mcVersion);
        var url = $"{Base}/project/{Uri.EscapeDataString(projectIdOrSlug)}/version"
                + $"?loaders={Uri.EscapeDataString("[\"" + loader.ToLowerInvariant() + "\"]")}"
                + $"&game_versions={Uri.EscapeDataString("[\"" + mcVersion + "\"]")}";
        using var resp = await _http.GetAsync(url, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode) return Array.Empty<ModrinthVersionInfo>();
        await using var st = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var doc = await JsonDocument.ParseAsync(st, cancellationToken: ct).ConfigureAwait(false);
        if (doc.RootElement.ValueKind != JsonValueKind.Array) return Array.Empty<ModrinthVersionInfo>();
        var outList = new List<ModrinthVersionInfo>();
        foreach (var v in doc.RootElement.EnumerateArray())
        {
            var vtype = v.TryGetProperty("version_type", out var vt) ? vt.GetString() ?? "release" : "release";
            if (!includePrerelease && !string.Equals(vtype, "release", StringComparison.OrdinalIgnoreCase)) continue;
            var files = new List<ModrinthFile>();
            if (v.TryGetProperty("files", out var fa) && fa.ValueKind == JsonValueKind.Array)
                foreach (var f in fa.EnumerateArray())
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
            if (v.TryGetProperty("dependencies", out var da) && da.ValueKind == JsonValueKind.Array)
                foreach (var d in da.EnumerateArray())
                    deps.Add(new ModrinthDep(
                        ProjectId: d.TryGetProperty("project_id", out var pi) ? pi.GetString() : null,
                        DependencyType: d.TryGetProperty("dependency_type", out var dt) ? dt.GetString() ?? "" : ""));
            DateTimeOffset pub = default;
            if (v.TryGetProperty("date_published", out var dp) && dp.ValueKind == JsonValueKind.String
                && DateTimeOffset.TryParse(dp.GetString(), out var pp)) pub = pp;
            outList.Add(new ModrinthVersionInfo(
                Id: v.GetProperty("id").GetString() ?? "",
                VersionNumber: v.GetProperty("version_number").GetString() ?? "",
                VersionType: vtype,
                Published: pub,
                Files: files.ToArray(),
                Dependencies: deps.ToArray()));
        }
        outList.Sort((a, b) => b.Published.CompareTo(a.Published));
        return outList;
    }

    /// <summary>Fetch a single version's changelog markdown (empty if none).</summary>
    public static async Task<string> GetVersionChangelogAsync(string versionId, CancellationToken ct = default)
    {
        try
        {
            var url = $"{Base}/version/{Uri.EscapeDataString(versionId)}";
            using var resp = await _http.GetAsync(url, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return "";
            await using var st = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            using var doc = await JsonDocument.ParseAsync(st, cancellationToken: ct).ConfigureAwait(false);
            return doc.RootElement.TryGetProperty("changelog", out var cl) ? cl.GetString() ?? "" : "";
        }
        catch { return ""; }
    }
}
