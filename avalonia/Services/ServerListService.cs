using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace S1mp1e.Services;

public sealed record SavedServer(string Name, string Address, string? IconBase64, List<string> Versions);

public sealed record ServerStatus(bool Online, int Players, int Max, string Motd, string? Version, int LatencyMs, string? FaviconBase64);

public static class ServerListService
{
    /// <summary>Servers saved in any instance's servers.dat (and the shared root), merged by address.</summary>
    public static List<SavedServer> LoadAll(string mcDir)
    {
        var files = new List<(string mc, string path)> { ("", Path.Combine(mcDir, "servers.dat")) };
        var inst = Path.Combine(mcDir, "instances");
        if (Directory.Exists(inst))
            files.AddRange(Directory.EnumerateDirectories(inst).Select(d => (Path.GetFileName(d), Path.Combine(d, "servers.dat"))));
        var byAddr = new Dictionary<string, SavedServer>(StringComparer.OrdinalIgnoreCase);
        foreach (var (mc, path) in files)
        {
            if (!File.Exists(path)) continue;
            if (Nbt.ReadFile(path) is not { } root || !root.TryGetValue("servers", out var lv) || lv is not List<object?> list) continue;
            foreach (var o in list.OfType<Dictionary<string, object?>>())
            {
                var ip = o.Str("ip")?.Trim();
                if (string.IsNullOrEmpty(ip)) continue;
                if (!byAddr.TryGetValue(ip, out var s))
                    byAddr[ip] = s = new SavedServer(o.Str("name") is { Length: > 0 } n ? n : ip, ip, o.Str("icon"), new List<string>());
                if (mc.Length > 0 && !s.Versions.Contains(mc)) s.Versions.Add(mc);
            }
        }
        return byAddr.Values.ToList();
    }

    public static (string host, int port) Split(string address)
    {
        var a = address.Trim();
        if (a.StartsWith('[') && a.Contains(']'))
        {
            var end = a.IndexOf(']');
            var host6 = a[1..end];
            return (host6, a.Length > end + 2 && int.TryParse(a[(end + 2)..], out var p6) ? p6 : 25565);
        }
        var i = a.LastIndexOf(':');
        if (i > 0 && a.IndexOf(':') == i && int.TryParse(a[(i + 1)..], out var p)) return (a[..i], p);
        return (a, 25565);
    }

    /// <summary>"host:25565" / "[::1]:25565" name a port; "host", "[::1]" and a bare IPv6 address do not.</summary>
    public static bool HasExplicitPort(string address)
    {
        var a = address.Trim();
        if (a.StartsWith('[')) return a.Contains("]:");
        int i = a.LastIndexOf(':');
        return i > 0 && a.IndexOf(':') == i && int.TryParse(a[(i + 1)..], out _);
    }

    /// <summary>Modern (1.7+) Server List Ping: handshake → status JSON → ping/pong for latency.</summary>
    public static async Task<ServerStatus> PingAsync(string address, CancellationToken ct = default)
    {
        var (host, port) = Split(address);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(TimeSpan.FromSeconds(5));
        try
        {
            // Like the game: an address without a port may name its real host and port in a _minecraft._tcp SRV record
            // (most hosted servers). The handshake still carries the name the player typed.
            var (connHost, connPort) = (host, port);
            if (!HasExplicitPort(address) && !System.Net.IPAddress.TryParse(host, out _)
                && await Task.Run(() => SrvLookup.Resolve("_minecraft._tcp." + host), cts.Token) is { } srv)
                (connHost, connPort) = srv;
            using var tcp = new TcpClient { NoDelay = true };
            await tcp.ConnectAsync(connHost, connPort, cts.Token);
            var ns = tcp.GetStream();

            var hs = new MemoryStream();
            WriteVarInt(hs, 0x00);
            WriteVarInt(hs, 47);
            WriteString(hs, host);
            hs.WriteByte((byte)(port >> 8)); hs.WriteByte((byte)port);
            WriteVarInt(hs, 1);
            await SendPacketAsync(ns, hs.ToArray(), cts.Token);
            var statusSw = Stopwatch.StartNew();
            await SendPacketAsync(ns, new byte[] { 0x00 }, cts.Token);

            var resp = await ReadPacketAsync(ns, cts.Token);
            int statusRtt = (int)statusSw.ElapsedMilliseconds;
            int off = 0;
            ReadVarInt(resp, ref off);                 // packet id
            int len = ReadVarInt(resp, ref off);
            var json = Encoding.UTF8.GetString(resp, off, len);

            var sw = Stopwatch.StartNew();
            var ping = new byte[9]; ping[0] = 0x01;
            BitConverter.GetBytes(DateTime.UtcNow.Ticks).CopyTo(ping, 1);
            await SendPacketAsync(ns, ping, cts.Token);
            // Servers that never answer the ping (some proxies) get the status round trip instead of a 5-second "latency".
            int latency;
            using (var pongCts = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                pongCts.CancelAfter(TimeSpan.FromSeconds(2));
                try { await ReadPacketAsync(ns, pongCts.Token); latency = (int)sw.ElapsedMilliseconds; }
                catch (Exception) when (!ct.IsCancellationRequested) { latency = statusRtt; }
            }

            using var doc = JsonDocument.Parse(json);
            var r = doc.RootElement;
            int online = 0, max = 0;
            if (r.TryGetProperty("players", out var pl))
            {
                online = pl.ValueKind == JsonValueKind.Object && pl.TryGetProperty("online", out var on) && on.ValueKind == JsonValueKind.Number && on.TryGetInt32(out var x) ? x : 0;
                max = pl.ValueKind == JsonValueKind.Object && pl.TryGetProperty("max", out var mx) && mx.ValueKind == JsonValueKind.Number && mx.TryGetInt32(out var y) ? y : 0;
            }
            // Every field is optional and servers send odd types (a number for a version name, an object for a favicon):
            // read defensively so a quirky field never turns a reachable server into "離線".
            var ver = r.TryGetProperty("version", out var v) && v.ValueKind == JsonValueKind.Object
                      && v.TryGetProperty("name", out var vn) && vn.ValueKind == JsonValueKind.String ? vn.GetString() : null;
            var motd = r.TryGetProperty("description", out var d) ? Flatten(d) : "";
            string? fav = null;
            if (r.TryGetProperty("favicon", out var f) && f.ValueKind == JsonValueKind.String && f.GetString() is { } fs && fs.Contains(','))
                fav = fs[(fs.IndexOf(',') + 1)..];
            var cleanMotd = Regex.Replace(StripCodes(motd), @"\s+", " ").Trim();   // MOTDs pad with spaces to centre in-game
            return new ServerStatus(true, online, max, cleanMotd, ver is null ? null : StripCodes(ver), latency, fav);
        }
        catch
        {
            return new ServerStatus(false, 0, 0, "", null, -1, null);
        }
    }

    private static string Flatten(JsonElement e) => e.ValueKind switch
    {
        JsonValueKind.String => e.GetString() ?? "",
        JsonValueKind.Array => string.Concat(e.EnumerateArray().Select(Flatten)),
        JsonValueKind.Object => (e.TryGetProperty("text", out var t) ? Flatten(t) : "")
                                + (e.TryGetProperty("extra", out var x) ? Flatten(x) : ""),
        _ => "",
    };

    private static string StripCodes(string s) => Regex.Replace(s, "§.", "");

    private static void WriteVarInt(Stream s, int value)
    {
        uint v = (uint)value;
        do
        {
            byte b = (byte)(v & 0x7F);
            v >>= 7;
            if (v != 0) b |= 0x80;
            s.WriteByte(b);
        } while (v != 0);
    }

    private static void WriteString(Stream s, string str)
    {
        var b = Encoding.UTF8.GetBytes(str);
        WriteVarInt(s, b.Length);
        s.Write(b);
    }

    private static async Task SendPacketAsync(NetworkStream ns, byte[] body, CancellationToken ct)
    {
        var ms = new MemoryStream();
        WriteVarInt(ms, body.Length);
        ms.Write(body);
        await ns.WriteAsync(ms.ToArray(), ct);
    }

    private static async Task<byte[]> ReadPacketAsync(NetworkStream ns, CancellationToken ct)
    {
        int len = 0, shift = 0;
        while (true)
        {
            var one = new byte[1];
            if (await ns.ReadAsync(one, ct) == 0) throw new EndOfStreamException();
            len |= (one[0] & 0x7F) << shift;
            if ((one[0] & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) throw new InvalidDataException("bad VarInt");
        }
        if (len <= 0 || len > 1 << 21) throw new InvalidDataException("bad packet length");
        var buf = new byte[len];
        int got = 0;
        while (got < len)
        {
            int n = await ns.ReadAsync(buf.AsMemory(got), ct);
            if (n == 0) throw new EndOfStreamException();
            got += n;
        }
        return buf;
    }

    private static int ReadVarInt(byte[] b, ref int off)
    {
        int v = 0, shift = 0;
        while (true)
        {
            var x = b[off++];
            v |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) return v;
            shift += 7;
        }
    }
}

/// <summary>
/// DNS SRV lookup through Windows' own resolver (dnsapi.dll), so "play.example.net" finds the host and port its
/// <c>_minecraft._tcp</c> record points at, exactly as the game does. Returns the lowest-priority / highest-weight
/// target, or null when there is no record (the caller then connects to the name on 25565).
/// </summary>
internal static class SrvLookup
{
    private const ushort DnsTypeSrv = 33;
    private const uint DnsQueryStandard = 0;
    private const int DnsFreeRecordList = 1;

    [System.Runtime.InteropServices.DllImport("dnsapi.dll", EntryPoint = "DnsQuery_W", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private static extern int DnsQuery(string name, ushort type, uint options, IntPtr extra, out IntPtr results, IntPtr reserved);

    [System.Runtime.InteropServices.DllImport("dnsapi.dll")]
    private static extern void DnsRecordListFree(IntPtr list, int freeType);

    // DNS_RECORDW header followed by DNS_SRV_DATAW.
    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential, CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
    private struct SrvRecord
    {
        public IntPtr pNext;
        public IntPtr pName;
        public ushort wType;
        public ushort wDataLength;
        public uint flags;
        public uint dwTtl;
        public uint dwReserved;
        public IntPtr pNameTarget;
        public ushort wPriority;
        public ushort wWeight;
        public ushort wPort;
        public ushort pad;
    }

    public static (string host, int port)? Resolve(string name)
    {
        if (!OperatingSystem.IsWindows()) return null;
        IntPtr list = IntPtr.Zero;
        try
        {
            if (DnsQuery(name, DnsTypeSrv, DnsQueryStandard, IntPtr.Zero, out list, IntPtr.Zero) != 0 || list == IntPtr.Zero) return null;
            (string host, int port, int prio, int weight)? best = null;
            for (var p = list; p != IntPtr.Zero;)
            {
                var rec = System.Runtime.InteropServices.Marshal.PtrToStructure<SrvRecord>(p);
                if (rec.wType == DnsTypeSrv && rec.pNameTarget != IntPtr.Zero)
                {
                    var target = System.Runtime.InteropServices.Marshal.PtrToStringUni(rec.pNameTarget)?.TrimEnd('.');
                    if (!string.IsNullOrEmpty(target) && rec.wPort != 0
                        && (best is null || rec.wPriority < best.Value.prio || (rec.wPriority == best.Value.prio && rec.wWeight > best.Value.weight)))
                        best = (target, rec.wPort, rec.wPriority, rec.wWeight);
                }
                p = rec.pNext;
            }
            return best is { } b ? (b.host, b.port) : null;
        }
        catch { return null; }
        finally
        {
            if (list != IntPtr.Zero) DnsRecordListFree(list, DnsFreeRecordList);
        }
    }
}
