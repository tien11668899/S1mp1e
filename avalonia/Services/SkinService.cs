using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace S1mp1e.Services;

/// <summary>Metadata for one entry in the skin gallery.</summary>
public sealed record TrendingSkin(string Id, string TextureUrl);

public sealed record CapeInfo(string Id, string Alias, string Url, bool Active);

/// <summary>
/// Change the current MC profile's skin via Mojang's REST API and fetch a
/// gallery of recently-uploaded skins from mineskin.org (NameMC is behind
/// Cloudflare's bot check and can't be scraped from a launcher).
/// </summary>
public static class SkinService
{
    private static readonly HttpClient _http = new()
    {
        Timeout = TimeSpan.FromSeconds(20),
    };
    static SkinService()
    {
        _http.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("S1mp1e-Launcher", "0.1.0"));
    }

    /// <summary>Null when <paramref name="png"/> is a skin Mojang accepts (a 64×64 or legacy 64×32 PNG), else why not.</summary>
    public static string? CheckSkinPng(byte[] png)
    {
        ReadOnlySpan<byte> sig = stackalloc byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        if (png.Length < 24 || !png.AsSpan(0, 8).SequenceEqual(sig)) return "這不是 PNG 圖片";
        int w = (png[16] << 24) | (png[17] << 16) | (png[18] << 8) | png[19];
        int h = (png[20] << 24) | (png[21] << 16) | (png[22] << 8) | png[23];
        return w == 64 && (h == 64 || h == 32) ? null : $"皮膚必須是 64×64（或舊版 64×32）的 PNG，這張是 {w}×{h}";
    }

    /// <summary>
    /// "slim" for an Alex-style skin (3-pixel arms), else "classic". On a 64×64 skin the slim arm leaves the right
    /// arm's outer columns (x 54–55, y 20–31) transparent; legacy 64×32 skins are always classic.
    /// </summary>
    public static string DetectVariant(byte[] png)
    {
        try
        {
            using var ms = new MemoryStream(png);
            using var bmp = new Avalonia.Media.Imaging.Bitmap(ms);
            if (bmp.PixelSize.Width != 64 || bmp.PixelSize.Height != 64) return "classic";
            const int x = 54, y = 20, w = 2, h = 12, stride = w * 4;
            var buf = new byte[stride * h];
            var handle = System.Runtime.InteropServices.GCHandle.Alloc(buf, System.Runtime.InteropServices.GCHandleType.Pinned);
            try { bmp.CopyPixels(new Avalonia.PixelRect(x, y, w, h), handle.AddrOfPinnedObject(), buf.Length, stride); }
            finally { handle.Free(); }
            for (int i = 3; i < buf.Length; i += 4)   // alpha is the 4th byte in both BGRA and RGBA
                if (buf[i] != 0) return "classic";
            return "slim";
        }
        catch { return "classic"; }
    }

    /// <summary>POST a raw skin PNG to Mojang, replacing the account's current skin.</summary>
    public static async Task ApplySkinAsync(string mcToken, byte[] png, string variant = "classic",
        CancellationToken ct = default)
    {
        using var mp = new MultipartFormDataContent();
        mp.Add(new StringContent(variant), "variant");
        var pngContent = new ByteArrayContent(png);
        pngContent.Headers.ContentType = new MediaTypeHeaderValue("image/png");
        mp.Add(pngContent, "file", "skin.png");
        using var req = new HttpRequestMessage(HttpMethod.Post,
            "https://api.minecraftservices.com/minecraft/profile/skins")
        {
            Content = mp,
        };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", mcToken);
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode)
        {
            var body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            throw new HttpRequestException($"Mojang skin upload failed ({(int)resp.StatusCode}): {body}");
        }
    }

    /// <summary>Fetch recent skins from mineskin.org. Returns up to
    /// <paramref name="count"/> entries with their raw texture URLs
    /// (textures.minecraft.net PNGs, fed straight into <see cref="ApplySkinAsync"/>).
    /// Uses the modern v2 endpoint first, falling back to the deprecated legacy list.</summary>
    public static async Task<IReadOnlyList<TrendingSkin>> FetchTrendingAsync(int count = 30, CancellationToken ct = default)
    {
        var v2 = await FetchV2Async(count, ct).ConfigureAwait(false);
        return v2.Count > 0 ? v2 : await FetchLegacyAsync(count, ct).ConfigureAwait(false);
    }

    // v2: GET /v2/skins?size=N → { skins:[{ uuid, texture:"<hash>", ... }] }.
    private static async Task<IReadOnlyList<TrendingSkin>> FetchV2Async(int count, CancellationToken ct)
    {
        try
        {
            var url = $"https://api.mineskin.org/v2/skins?size={count}";
            using var resp = await _http.GetAsync(url, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return Array.Empty<TrendingSkin>();
            await using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            using var doc = await JsonDocument.ParseAsync(s, cancellationToken: ct).ConfigureAwait(false);
            if (!doc.RootElement.TryGetProperty("skins", out var arr) || arr.ValueKind != JsonValueKind.Array)
                return Array.Empty<TrendingSkin>();
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var list = new List<TrendingSkin>(count);
            foreach (var e in arr.EnumerateArray())
            {
                var id = e.TryGetProperty("uuid", out var u) ? u.GetString() ?? "" : "";
                var hash = e.TryGetProperty("texture", out var t) ? t.GetString() ?? "" : "";
                if (string.IsNullOrEmpty(hash) || !seen.Add(hash)) continue;
                list.Add(new TrendingSkin(id, $"https://textures.minecraft.net/texture/{hash}"));
                if (list.Count >= count) break;
            }
            return list;
        }
        catch { return Array.Empty<TrendingSkin>(); }
    }

    // Legacy (deprecated but still live): GET /get/list → { skins:[{ uuid, url }] }.
    private static async Task<IReadOnlyList<TrendingSkin>> FetchLegacyAsync(int count, CancellationToken ct)
    {
        try
        {
            using var resp = await _http.GetAsync("https://api.mineskin.org/get/list?page=0", ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return Array.Empty<TrendingSkin>();
            await using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            using var doc = await JsonDocument.ParseAsync(s, cancellationToken: ct).ConfigureAwait(false);
            var arr = doc.RootElement.TryGetProperty("skins", out var a) ? a : default;
            if (arr.ValueKind != JsonValueKind.Array) return Array.Empty<TrendingSkin>();
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var list = new List<TrendingSkin>(count);
            foreach (var e in arr.EnumerateArray())
            {
                var id = e.TryGetProperty("uuid", out var u) ? u.GetString() ?? "" : "";
                var tex = e.TryGetProperty("url", out var t) ? t.GetString() ?? "" : "";
                if (string.IsNullOrEmpty(tex) || !seen.Add(tex)) continue;
                if (tex.StartsWith("http://textures.minecraft.net/")) tex = "https" + tex.Substring(4);
                list.Add(new TrendingSkin(id, tex));
                if (list.Count >= count) break;
            }
            return list;
        }
        catch { return Array.Empty<TrendingSkin>(); }
    }

    /// <summary>The capes this Minecraft profile owns (Minecon, Migrator, Vanilla, …) and which one is shown.</summary>
    public static async Task<IReadOnlyList<CapeInfo>> GetCapesAsync(string mcToken, CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, "https://api.minecraftservices.com/minecraft/profile");
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", mcToken);
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        if (resp.StatusCode == System.Net.HttpStatusCode.Unauthorized) throw new UnauthorizedAccessException();
        resp.EnsureSuccessStatusCode();
        await using var s = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var doc = await JsonDocument.ParseAsync(s, cancellationToken: ct).ConfigureAwait(false);
        var list = new List<CapeInfo>();
        if (doc.RootElement.TryGetProperty("capes", out var arr) && arr.ValueKind == JsonValueKind.Array)
            foreach (var c in arr.EnumerateArray())
                list.Add(new CapeInfo(
                    c.GetProperty("id").GetString() ?? "",
                    c.TryGetProperty("alias", out var a) ? a.GetString() ?? "" : "",
                    c.TryGetProperty("url", out var u) ? u.GetString() ?? "" : "",
                    c.TryGetProperty("state", out var st) && st.GetString() == "ACTIVE"));
        return list;
    }

    /// <summary>Show the given cape, or hide the cape entirely when <paramref name="capeId"/> is null.</summary>
    public static async Task SetActiveCapeAsync(string mcToken, string? capeId, CancellationToken ct = default)
    {
        using var req = capeId is null
            ? new HttpRequestMessage(HttpMethod.Delete, "https://api.minecraftservices.com/minecraft/profile/capes/active")
            : new HttpRequestMessage(HttpMethod.Put, "https://api.minecraftservices.com/minecraft/profile/capes/active")
            {
                Content = new StringContent(JsonSerializer.Serialize(new { capeId }), System.Text.Encoding.UTF8, "application/json"),
            };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", mcToken);
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        if (resp.StatusCode == System.Net.HttpStatusCode.Unauthorized) throw new UnauthorizedAccessException();
        if (!resp.IsSuccessStatusCode)
            throw new HttpRequestException($"Mojang cape change failed ({(int)resp.StatusCode}): {await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false)}");
    }

    /// <summary>Download the raw PNG behind a textures.minecraft.net URL.</summary>
    public static async Task<byte[]?> DownloadPngAsync(string url, CancellationToken ct = default)
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
