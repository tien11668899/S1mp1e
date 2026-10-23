using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;

namespace S1mp1e.Services;

/// <summary>
/// Minimal read-only NBT (Minecraft's binary tag format): compounds become Dictionary&lt;string, object?&gt;, lists
/// List&lt;object?&gt;, numbers their CLR type, strings string. Enough for level.dat and servers.dat.
/// </summary>
public static class Nbt
{
    /// <summary>Read a file that may be gzip-compressed (level.dat) or raw (servers.dat). Returns the root compound.</summary>
    public static Dictionary<string, object?>? ReadFile(string path)
    {
        try
        {
            var bytes = File.ReadAllBytes(path);
            Stream s = new MemoryStream(bytes);
            if (bytes.Length > 2 && bytes[0] == 0x1F && bytes[1] == 0x8B) s = new GZipStream(s, CompressionMode.Decompress);
            using var r = new BinaryReader(s);
            var type = r.ReadByte();
            if (type != 10) return null;
            ReadString(r);
            return ReadCompound(r, 0);
        }
        catch { return null; }
    }

    private static string ReadString(BinaryReader r)
    {
        int len = ReadU16(r);
        return Encoding.UTF8.GetString(r.ReadBytes(len));
    }

    private static int ReadU16(BinaryReader r) { var b = r.ReadBytes(2); return (b[0] << 8) | b[1]; }
    private static int ReadI32(BinaryReader r) { var b = r.ReadBytes(4); return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]; }
    private static long ReadI64(BinaryReader r) { long hi = (uint)ReadI32(r); long lo = (uint)ReadI32(r); return (hi << 32) | lo; }

    // A corrupt length (negative, or gigabytes) must fail the read, not allocate a huge array first. Real level.dat and
    // servers.dat arrays are far below this.
    private static int ArrayLen(BinaryReader r)
    {
        int n = ReadI32(r);
        if (n < 0 || n > 16_000_000) throw new InvalidDataException("bad NBT array length " + n);
        return n;
    }

    private static Dictionary<string, object?> ReadCompound(BinaryReader r, int depth)
    {
        var d = new Dictionary<string, object?>();
        while (true)
        {
            var t = r.ReadByte();
            if (t == 0) return d;
            var name = ReadString(r);
            d[name] = ReadPayload(r, t, depth + 1);
        }
    }

    private static object? ReadPayload(BinaryReader r, byte t, int depth)
    {
        if (depth > 64) throw new InvalidDataException("NBT nested too deep");
        switch (t)
        {
            case 1: return (sbyte)r.ReadByte();
            case 2: { var b = r.ReadBytes(2); return (short)((b[0] << 8) | b[1]); }
            case 3: return ReadI32(r);
            case 4: return ReadI64(r);
            case 5: return BitConverter.Int32BitsToSingle(ReadI32(r));
            case 6: return BitConverter.Int64BitsToDouble(ReadI64(r));
            case 7: { int n = ArrayLen(r); return r.ReadBytes(n); }
            case 8: return ReadString(r);
            case 9:
            {
                var et = r.ReadByte(); int n = ArrayLen(r);
                var l = new List<object?>(Math.Max(0, Math.Min(n, 4096)));
                for (int i = 0; i < n; i++) l.Add(ReadPayload(r, et, depth + 1));
                return l;
            }
            case 10: return ReadCompound(r, depth);
            case 11: { int n = ArrayLen(r); var a = new int[n]; for (int i = 0; i < n; i++) a[i] = ReadI32(r); return a; }
            case 12: { int n = ArrayLen(r); var a = new long[n]; for (int i = 0; i < n; i++) a[i] = ReadI64(r); return a; }
            default: throw new InvalidDataException("bad NBT tag " + t);
        }
    }

    public static Dictionary<string, object?>? Compound(this Dictionary<string, object?>? d, string key)
        => d is not null && d.TryGetValue(key, out var v) ? v as Dictionary<string, object?> : null;
    public static string? Str(this Dictionary<string, object?>? d, string key)
        => d is not null && d.TryGetValue(key, out var v) ? v as string : null;
    public static long? Long(this Dictionary<string, object?>? d, string key)
        => d is not null && d.TryGetValue(key, out var v) ? v switch { long l => l, int i => i, short s => s, sbyte b => b, _ => null } : null;
}
