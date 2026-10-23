using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace S1mp1e.Services;

/// <summary>
/// Per-version play time at <c>%APPDATA%\S1mp1e\playtime.json</c>. Its own file, not config.json: the Rust CLI rewrites
/// config.json from its own struct and would drop fields it doesn't know. Written by the CLI when a game session ends
/// (src-tauri/src/playtime.rs), so it counts sessions even after the launcher window was closed; the UI only reads it.
/// </summary>
public static class PlayStats
{
    public sealed class Entry
    {
        [JsonPropertyName("seconds")] public long Seconds { get; set; }
        [JsonPropertyName("last")] public long LastPlayed { get; set; }
        [JsonPropertyName("sessions")] public int Sessions { get; set; }
    }

    private static string FilePath => Environment.GetEnvironmentVariable("S1MP1E_PLAYTIME_FILE") is { Length: > 0 } test
        ? test
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "S1mp1e", "playtime.json");

    public static Dictionary<string, Entry> Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Dictionary<string, Entry>>(File.ReadAllText(FilePath)) ?? new();
        }
        catch { }
        return new();
    }

    public static string Format(Entry? e)
    {
        if (e is null || e.Seconds <= 0) return "還沒用 S1mp1e 玩過這個版本";
        var t = TimeSpan.FromSeconds(e.Seconds);
        var dur = t.TotalHours >= 1 ? $"{t.TotalHours:0.#} 小時" : $"{Math.Max(1, (int)t.TotalMinutes)} 分鐘";
        var ago = DateTimeOffset.UtcNow - DateTimeOffset.FromUnixTimeSeconds(e.LastPlayed);
        var last = ago.TotalDays >= 1 ? $"{(int)ago.TotalDays} 天前" : ago.TotalHours >= 1 ? $"{(int)ago.TotalHours} 小時前" : "剛剛";
        return $"共 {dur} · 上次 {last}";
    }
}
