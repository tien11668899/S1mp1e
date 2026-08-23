using System;
using System.Collections.Concurrent;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace S1mp1e.Services;

/// <summary>
/// Best-effort zh-TW machine translation via Google's unofficial gtx endpoint
/// (no API key needed; same one dozens of userscripts use). Result is cached
/// per source string for the process lifetime so a mod's description is only
/// translated once even if the user re-opens the sheet.
///
/// Falls back to the original text on any failure — this is a UX niceness,
/// not a hard requirement.
/// </summary>
public static class TranslateClient
{
    private static readonly HttpClient _http = new()
    {
        Timeout = TimeSpan.FromSeconds(8),
    };
    private static readonly ConcurrentDictionary<string, string> _cache = new();

    // Cheap ASCII pre-check: if the string has any CJK-range codepoint already,
    // assume it's already translated (or was authored in Chinese/Japanese) and
    // skip the network round-trip.
    private static readonly Regex _hasCjk = new(@"[㐀-鿿]", RegexOptions.Compiled);

    public static async Task<string> ToZhTwAsync(string text, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(text)) return text ?? "";
        if (_hasCjk.IsMatch(text)) return text;
        if (_cache.TryGetValue(text, out var cached)) return cached;
        try
        {
            // The gtx endpoint chunks source > ~5000 chars; mod descriptions are
            // short paragraphs so a single request is fine. dt=t returns the
            // translation array; each element is [translated, source, ...].
            var url = "https://translate.googleapis.com/translate_a/single"
                    + "?client=gtx&sl=auto&tl=zh-TW&dt=t&q="
                    + Uri.EscapeDataString(text);
            using var resp = await _http.GetAsync(url, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return text;
            var json = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(json);
            var sb = new StringBuilder(text.Length);
            var arr = doc.RootElement[0];
            foreach (var seg in arr.EnumerateArray())
            {
                if (seg.ValueKind == JsonValueKind.Array && seg.GetArrayLength() > 0)
                {
                    var t = seg[0].GetString();
                    if (!string.IsNullOrEmpty(t)) sb.Append(t);
                }
            }
            var translated = sb.ToString();
            if (string.IsNullOrWhiteSpace(translated)) return text;
            _cache[text] = translated;
            return translated;
        }
        catch { return text; }
    }
}
