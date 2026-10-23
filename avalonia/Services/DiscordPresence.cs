using System;
using System.Buffers.Binary;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace S1mp1e.Services;

/// <summary>What the Discord profile shows while the game runs ("正在玩 S1mp1e" + these two lines + elapsed time).</summary>
public sealed record PresenceInfo(string Details, string State, DateTimeOffset Start);

/// <summary>
/// Discord Rich Presence over Discord's local IPC pipe (<c>\\.\pipe\discord-ipc-N</c>) — no SDK, no network. Frames are
/// <c>int32 opcode, int32 length, UTF-8 JSON</c> (little endian): handshake (0) → READY, then SET_ACTIVITY commands (1);
/// Discord pings (3) and expects a pong (4). Discord drops the activity by itself when the pipe closes.
/// <para>One background pump reconciles "wanted" with "sent": callers only set the wanted state from any thread, and a
/// Discord that was closed (or restarted) when the game started is picked up on the next 20 s tick.</para>
/// </summary>
public static class DiscordPresence
{
    /// <summary>Discord application id (Developer Portal → application "S1mp1e"; its name is the "正在玩 …" line).
    /// Empty = presence off.</summary>
    public const string ApplicationId = "";

    private const string Logo = "https://tien11668899.github.io/S1mp1e/assets/icon.png";
    private const string Releases = "https://github.com/tien11668899/S1mp1e/releases";

    private static readonly SemaphoreSlim Write = new(1, 1);
    private static readonly SemaphoreSlim Wake = new(0, int.MaxValue);
    private static volatile PresenceInfo? _want;
    private static PresenceInfo? _sent;
    private static NamedPipeClientStream? _pipe;
    private static volatile bool _dead;
    private static int _pumpStarted;

    public static void Set(PresenceInfo info) { _want = info; Kick(); }

    public static void Clear() { _want = null; Kick(); }

    private static void Kick()
    {
        if (ApplicationId.Length == 0) return;
        if (Interlocked.Exchange(ref _pumpStarted, 1) == 0) _ = Task.Run(PumpAsync);
        Wake.Release();
    }

    private static async Task PumpAsync()
    {
        while (true)
        {
            await Wake.WaitAsync(TimeSpan.FromSeconds(20)).ConfigureAwait(false);
            while (Wake.CurrentCount > 0) Wake.Wait(0);   // coalesce bursts of updates into one send
            try { await ReconcileAsync().ConfigureAwait(false); }
            catch { Close(); }
        }
    }

    private static async Task ReconcileAsync()
    {
        var want = _want;
        if (_dead) Close();
        if (want is null)
        {
            Close();   // closing the pipe is what clears the activity on Discord's side
            return;
        }
        if (_pipe is not null && want == _sent) return;
        if (_pipe is null && !await ConnectAsync().ConfigureAwait(false)) return;

        var cmd = new JsonObject
        {
            ["cmd"] = "SET_ACTIVITY",
            ["args"] = new JsonObject { ["pid"] = Environment.ProcessId, ["activity"] = Activity(want) },
            ["nonce"] = Guid.NewGuid().ToString(),
        };
        await SendAsync(_pipe!, 1, cmd.ToJsonString()).ConfigureAwait(false);
        _sent = want;
    }

    private static JsonObject Activity(PresenceInfo p) => new()
    {
        ["details"] = Fit(p.Details),
        ["state"] = Fit(p.State),
        ["timestamps"] = new JsonObject { ["start"] = p.Start.ToUnixTimeMilliseconds() },
        ["assets"] = new JsonObject { ["large_image"] = Logo, ["large_text"] = "S1mp1e 啟動器" },
        ["buttons"] = new JsonArray { new JsonObject { ["label"] = "下載 S1mp1e", ["url"] = Releases } },
    };

    // Discord rejects the whole activity when a field is under 2 or over 128 characters.
    private static string Fit(string s) => s.Length < 2 ? s.PadRight(2) : s.Length > 128 ? s[..127] + "…" : s;

    private static async Task<bool> ConnectAsync()
    {
        for (int i = 0; i < 10; i++)
        {
            var pipe = new NamedPipeClientStream(".", $"discord-ipc-{i}", PipeDirection.InOut, PipeOptions.Asynchronous);
            try
            {
                await pipe.ConnectAsync(200).ConfigureAwait(false);
                await SendAsync(pipe, 0, new JsonObject { ["v"] = 1, ["client_id"] = ApplicationId }.ToJsonString()).ConfigureAwait(false);
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
                var (op, json) = await ReadAsync(pipe, cts.Token).ConfigureAwait(false);
                if (op != 1 || !json.Contains("\"READY\"", StringComparison.Ordinal)) { pipe.Dispose(); continue; }
                _pipe = pipe;
                _sent = null;
                _dead = false;
                _ = Task.Run(() => ReadLoopAsync(pipe));
                return true;
            }
            catch { pipe.Dispose(); }
        }
        return false;
    }

    /// <summary>Drains Discord's replies (so its side never blocks on a full pipe), answers pings, notices a close.</summary>
    private static async Task ReadLoopAsync(NamedPipeClientStream pipe)
    {
        try
        {
            while (true)
            {
                var (op, json) = await ReadAsync(pipe, CancellationToken.None).ConfigureAwait(false);
                if (op == 3) await SendAsync(pipe, 4, json).ConfigureAwait(false);
                else if (op == 2) break;
            }
        }
        catch { }
        if (ReferenceEquals(pipe, _pipe)) { _dead = true; Wake.Release(); }
    }

    private static async Task SendAsync(Stream s, int op, string json)
    {
        var body = Encoding.UTF8.GetBytes(json);
        var frame = new byte[8 + body.Length];
        BinaryPrimitives.WriteInt32LittleEndian(frame.AsSpan(0), op);
        BinaryPrimitives.WriteInt32LittleEndian(frame.AsSpan(4), body.Length);
        body.CopyTo(frame, 8);
        await Write.WaitAsync().ConfigureAwait(false);
        try
        {
            await s.WriteAsync(frame).ConfigureAwait(false);
            await s.FlushAsync().ConfigureAwait(false);
        }
        finally { Write.Release(); }
    }

    private static async Task<(int op, string json)> ReadAsync(Stream s, CancellationToken ct)
    {
        var head = new byte[8];
        await s.ReadExactlyAsync(head, ct).ConfigureAwait(false);
        int op = BinaryPrimitives.ReadInt32LittleEndian(head.AsSpan(0));
        int len = BinaryPrimitives.ReadInt32LittleEndian(head.AsSpan(4));
        if (len is < 0 or > 1 << 20) throw new InvalidDataException("bad Discord IPC frame");
        var body = new byte[len];
        await s.ReadExactlyAsync(body, ct).ConfigureAwait(false);
        return (op, Encoding.UTF8.GetString(body));
    }

    private static void Close()
    {
        var p = _pipe;
        _pipe = null;
        _sent = null;
        _dead = false;
        try { p?.Dispose(); } catch { }
    }

    // ---------------- reading the game log ----------------

    public enum GameScene { Menu, Singleplayer, Multiplayer }

    // "Connecting to mc.example.net, 25565" — ConnectScreen logs this the same way from 1.8.9 to 26.x.
    private static readonly Regex ConnectingRx = new(@"Connecting to (\S+?), (\d+)", RegexOptions.Compiled);

    /// <summary>The scene a game log line moves the player into, or null when the line says nothing about it.
    /// Leaving a server logs nothing, so a multiplayer scene lasts until the next world or server.</summary>
    public static (GameScene scene, string? address)? SceneFromLog(string line)
    {
        if (ConnectingRx.Match(line) is { Success: true } m)
            return (GameScene.Multiplayer, m.Groups[2].Value == "25565" ? m.Groups[1].Value : $"{m.Groups[1].Value}:{m.Groups[2].Value}");
        if (line.Contains("Starting integrated minecraft server", StringComparison.Ordinal))
            return (GameScene.Singleplayer, null);
        if (line.Contains("Stopping server", StringComparison.Ordinal))
            return (GameScene.Menu, null);
        return null;
    }
}
