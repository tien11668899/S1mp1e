using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace S1mp1e.Services;

public sealed record WorldInfo(string Folder, string Path, string Name, DateTime LastPlayed, string Mode, string? Version, string? IconPath);

public sealed record WorldBackup(string Path, string World, DateTime Created, long Size);

/// <summary>
/// Worlds live in the shared <c>.minecraft/saves</c> (every instance junctions its saves there). Backups are plain
/// lossless zips under <c>.minecraft/s1mp1e-backups/worlds/</c>, one top-level folder per world inside.
/// </summary>
public static class WorldService
{
    public static string SavesDir(string mcDir) => System.IO.Path.Combine(mcDir, "saves");
    public static string BackupDir(string mcDir) => System.IO.Path.Combine(mcDir, "s1mp1e-backups", "worlds");

    public static List<WorldInfo> List(string mcDir)
    {
        var list = new List<WorldInfo>();
        var saves = SavesDir(mcDir);
        if (!Directory.Exists(saves)) return list;
        foreach (var dir in Directory.EnumerateDirectories(saves))
        {
            var level = System.IO.Path.Combine(dir, "level.dat");
            if (!File.Exists(level)) continue;
            var folder = System.IO.Path.GetFileName(dir);
            if (folder.StartsWith(".s1restore-", StringComparison.Ordinal)) continue;   // a restore still unpacking
            var data = Nbt.ReadFile(level).Compound("Data");   // null for a damaged level.dat: listed by folder name
            var name = data.Str("LevelName") is { Length: > 0 } n ? n : folder;
            var last = data.Long("LastPlayed") is long ms && ms > 0
                ? DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime
                : File.GetLastWriteTime(level);
            bool hardcore = data.Long("hardcore") is 1;
            var mode = hardcore ? "極限" : (data.Long("GameType") ?? 0) switch { 1 => "創造", 2 => "冒險", 3 => "旁觀", _ => "生存" };
            var ver = data.Compound("Version").Str("Name");
            var icon = System.IO.Path.Combine(dir, "icon.png");
            list.Add(new WorldInfo(folder, dir, name, last, mode, ver, File.Exists(icon) ? icon : null));
        }
        return list.OrderByDescending(w => w.LastPlayed).ToList();
    }

    public static long FolderSize(string dir)
    {
        try { return new DirectoryInfo(dir).EnumerateFiles("*", SearchOption.AllDirectories).Sum(f => f.Length); }
        catch { return 0; }
    }

    public static List<WorldBackup> Backups(string mcDir)
    {
        var dir = BackupDir(mcDir);
        if (!Directory.Exists(dir)) return new();
        return new DirectoryInfo(dir).EnumerateFiles("*.zip")
            .Select(f => new WorldBackup(f.FullName, WorldOf(f.Name), f.LastWriteTime, f.Length))
            .OrderByDescending(b => b.Created).ToList();
    }

    // "<folder>_20261022-231500.zip" → "<folder>"
    private static string WorldOf(string zipName)
    {
        var stem = System.IO.Path.GetFileNameWithoutExtension(zipName);
        var i = stem.LastIndexOf('_');
        return i > 0 ? stem[..i] : stem;
    }

    /// <summary>True when the game has this world open right now (Minecraft holds session.lock locked while it plays).</summary>
    public static bool InUse(string worldPath) => FileLocks.IsLocked(System.IO.Path.Combine(worldPath, "session.lock"));

    public static async Task<string> BackupAsync(string mcDir, WorldInfo w, IProgress<double>? progress, CancellationToken ct = default)
    {
        var outDir = BackupDir(mcDir);
        Directory.CreateDirectory(outDir);
        var dest = System.IO.Path.Combine(outDir, $"{w.Folder}_{DateTime.Now:yyyyMMdd-HHmmss}.zip");
        for (int i = 2; File.Exists(dest); i++)   // never overwrite an earlier backup made within the same second
            dest = System.IO.Path.Combine(outDir, $"{w.Folder}_{DateTime.Now:yyyyMMdd-HHmmss}-{i}.zip");
        var tmp = dest + ".part";
        try
        {
            await Task.Run(() =>
            {
                var files = new DirectoryInfo(w.Path).EnumerateFiles("*", SearchOption.AllDirectories)
                    .Where(f => !f.Name.Equals("session.lock", StringComparison.OrdinalIgnoreCase)).ToList();
                long total = Math.Max(1, files.Sum(f => f.Length)), done = 0;
                using (var fs = new FileStream(tmp, FileMode.Create))
                using (var zip = new ZipArchive(fs, ZipArchiveMode.Create))
                {
                    foreach (var f in files)
                    {
                        ct.ThrowIfCancellationRequested();
                        var rel = System.IO.Path.GetRelativePath(w.Path, f.FullName).Replace('\\', '/');
                        var entry = zip.CreateEntry(w.Folder + "/" + rel, CompressionLevel.Optimal);
                        entry.LastWriteTime = f.LastWriteTime;
                        using var src = new FileStream(f.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                        using var dst = entry.Open();
                        src.CopyTo(dst);
                        done += f.Length;
                        progress?.Report((double)done / total);
                    }
                }
                File.Move(tmp, dest, overwrite: false);
            }, ct);
        }
        catch
        {
            // A failed or cancelled backup leaves no .part next to the real ones (it would never be listed or removed).
            try { File.Delete(tmp); } catch { }
            throw;
        }
        return dest;
    }

    /// <summary>
    /// Unpack a backup into saves/ and return the restored folder name. The zip is checked first (one world folder,
    /// no path leaving saves/), then unpacked into a hidden staging folder; only a complete unpack takes the world's
    /// name. A world already using that name is kept, renamed "(還原前 …)", and is put back if the final swap fails —
    /// a failed restore never leaves a half-restored world or loses the current one. Refuses while the game has the
    /// world open.
    /// </summary>
    public static async Task<string> RestoreAsync(string mcDir, WorldBackup b, CancellationToken ct = default)
    {
        var saves = SavesDir(mcDir);
        Directory.CreateDirectory(saves);
        return await Task.Run(() =>
        {
            using var zip = ZipFile.OpenRead(b.Path);
            var tops = zip.Entries.Select(e => e.FullName.Replace('\\', '/').Split('/')[0]).Where(s => s.Length > 0)
                          .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            if (tops.Count == 0) throw new InvalidDataException("備份是空的");
            if (tops.Count > 1) throw new InvalidDataException("這不是 S1mp1e 的世界備份（裡面有多個資料夾）");
            var top = tops[0];
            if (top is "." or ".." || top.IndexOfAny(System.IO.Path.GetInvalidFileNameChars()) >= 0)
                throw new InvalidDataException("備份內含不安全的路徑");

            var target = System.IO.Path.Combine(saves, top);
            if (Directory.Exists(target) && InUse(target))
                throw new FileInUseException(System.IO.Path.Combine(target, "session.lock"));

            var staging = System.IO.Path.Combine(saves, $".s1restore-{Guid.NewGuid():N}");
            var stagingRoot = System.IO.Path.GetFullPath(staging) + System.IO.Path.DirectorySeparatorChar;
            try
            {
                Directory.CreateDirectory(staging);
                foreach (var e in zip.Entries)
                {
                    ct.ThrowIfCancellationRequested();
                    var path = System.IO.Path.GetFullPath(System.IO.Path.Combine(staging, e.FullName));
                    if (!path.StartsWith(stagingRoot, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("備份內含不安全的路徑");
                    if (e.FullName.EndsWith('/') || e.FullName.EndsWith('\\')) { Directory.CreateDirectory(path); continue; }
                    Directory.CreateDirectory(System.IO.Path.GetDirectoryName(path)!);
                    e.ExtractToFile(path, true);
                }
                var unpacked = System.IO.Path.Combine(staging, top);
                if (!File.Exists(System.IO.Path.Combine(unpacked, "level.dat")))
                    throw new InvalidDataException("備份裡找不到 level.dat，不是完整的世界");

                string? parked = null;
                if (Directory.Exists(target))
                {
                    parked = System.IO.Path.Combine(saves, $"{top} (還原前 {DateTime.Now:yyyyMMdd-HHmmss})");
                    for (int i = 2; Directory.Exists(parked); i++)
                        parked = System.IO.Path.Combine(saves, $"{top} (還原前 {DateTime.Now:yyyyMMdd-HHmmss}-{i})");
                    Directory.Move(target, parked);
                }
                try { Directory.Move(unpacked, target); }
                catch
                {
                    if (parked is not null) try { Directory.Move(parked, target); } catch { }
                    throw;
                }
                return top;
            }
            finally
            {
                try { if (Directory.Exists(staging)) Directory.Delete(staging, recursive: true); } catch { }
            }
        }, ct);
    }

    /// <summary>Screenshots from every instance plus the shared root, newest first.</summary>
    public static List<FileInfo> Screenshots(string mcDir)
    {
        var dirs = new List<string> { System.IO.Path.Combine(mcDir, "screenshots") };
        var inst = System.IO.Path.Combine(mcDir, "instances");
        if (Directory.Exists(inst))
            dirs.AddRange(Directory.EnumerateDirectories(inst).Select(d => System.IO.Path.Combine(d, "screenshots")));
        return dirs.Where(Directory.Exists)
            .SelectMany(d => new DirectoryInfo(d).EnumerateFiles("*.png"))
            .OrderByDescending(f => f.LastWriteTime).ToList();
    }
}
