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

    /// <summary>Fetch recent skins from mineskin.org's public list endpoint.
    /// Returns up to <paramref name="count"/> entries with their raw texture URLs
    /// (textures.minecraft.net PNGs, which can be fed straight into <see cref="ApplySkinAsync"/>).</summary>
    public static async Task<IReadOnlyList<TrendingSkin>> FetchTrendingAsync(int count = 30, CancellationToken ct = default)
    {
        var url = $"https://api.mineskin.org/get/list?page=0";
        using var resp = await _http.GetAsync(url, ct).ConfigureAwait(false);
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
            // Mineskin sometimes returns http URLs — upgrade to https so we don't
            // get blocked by ModernHttp / TLS-only policies on the client side.
            if (tex.StartsWith("http://textures.minecraft.net/"))
                tex = "https" + tex.Substring(4);
            list.Add(new TrendingSkin(id, tex));
            if (list.Count >= count) break;
        }
        return list;
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
