using System;
using System.Collections.Generic;
using System.Linq;
using Avalonia.Interactivity;
using S1mp1e.Services;

namespace S1mp1e.Views;

public partial class MainWindow
{
    // ---------------- Discord presence ----------------

    private volatile PresenceInfo? _presenceNow;     // what the profile should show while the game runs (null = no game)
    private List<SavedServer> _presenceServers = new();

    private bool PresenceOn => _cfg.Settings.DiscordPresence && !_captureMode;

    /// <summary>Right after the game process starts (UI thread).</summary>
    private void PresenceGameStarted(string mc, string loader, string? join)
    {
        // servers.dat names, so the profile says "Hypixel" (the name the player gave it) and never a raw private address.
        try { _presenceServers = ServerListService.LoadAll(EffectiveMcDir()); } catch { _presenceServers = new(); }
        var details = loader.Length == 0 ? $"Minecraft {mc}" : $"Minecraft {mc} · {char.ToUpperInvariant(loader[0])}{loader[1..]}";
        var info = new PresenceInfo(details, join is null ? "啟動中…" : ServerLabel(join), DateTimeOffset.UtcNow);
        _presenceNow = info;
        if (PresenceOn) DiscordPresence.Set(info);
    }

    /// <summary>Every game stdout line (any thread).</summary>
    private void PresenceFromLog(string line, bool reachedRunning)
    {
        if (_presenceNow is not { } now) return;
        string? state;
        if (DiscordPresence.SceneFromLog(line) is not { } hit)
            state = reachedRunning && now.State == "啟動中…" ? "主選單" : null;
        else
            state = hit.scene switch
            {
                DiscordPresence.GameScene.Multiplayer => ServerLabel(hit.address!),
                DiscordPresence.GameScene.Singleplayer => "單人遊戲",
                _ => "主選單",
            };
        if (state is null || state == now.State) return;
        var next = now with { State = state };
        _presenceNow = next;
        if (PresenceOn) DiscordPresence.Set(next);
    }

    private void PresenceGameEnded()
    {
        _presenceNow = null;
        DiscordPresence.Clear();
    }

    private string ServerLabel(string address)
    {
        var (host, port) = ServerListService.Split(address);
        var saved = _presenceServers.FirstOrDefault(s =>
        {
            var (h, p) = ServerListService.Split(s.Address);
            return p == port && string.Equals(h, host, StringComparison.OrdinalIgnoreCase);
        });
        return saved is null ? "多人遊戲" : $"多人遊戲 · {saved.Name}";
    }

    private void OnDiscordToggleChanged(object? sender, RoutedEventArgs e)
    {
        if (_hydrating || DiscordToggle is null) return;
        _cfg.Settings.DiscordPresence = DiscordToggle.IsChecked == true;
        SaveCfg();
        // Applies to a game that is already running, too.
        if (!PresenceOn) DiscordPresence.Clear();
        else if (_presenceNow is { } now) DiscordPresence.Set(now);
    }
}
