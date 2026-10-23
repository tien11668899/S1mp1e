using System;
using System.Collections.Generic;
using System.IO;

namespace S1mp1e.Services;

/// <summary>
/// A file another process holds open — on Windows a running Minecraft keeps every loaded mod jar open, so replacing,
/// renaming or deleting it fails. Carries the path so the UI can name the file and tell the player to close the game,
/// instead of the operation failing with only a log line.
/// </summary>
public sealed class FileInUseException : IOException
{
    public string FilePath { get; }

    public FileInUseException(string path, Exception? inner = null)
        : base($"「{Path.GetFileName(path)}」正在被其他程式使用（通常是還開著的遊戲）", inner)
    {
        FilePath = path;
    }
}

/// <summary>Detects files held open by another process, before an operation leaves anything half done.</summary>
public static class FileLocks
{
    private const int ErrorSharingViolation = 32, ErrorLockViolation = 33;

    /// <summary>True when <paramref name="ex"/> is a Windows sharing / lock violation.</summary>
    public static bool IsSharingViolation(IOException ex)
    {
        int code = ex.HResult & 0xFFFF;
        return code is ErrorSharingViolation or ErrorLockViolation;
    }

    /// <summary>
    /// True when the file exists and cannot be opened exclusively (another process has it open). The probe opens and
    /// closes immediately; it never modifies the file. A missing file is "not locked".
    /// </summary>
    public static bool IsLocked(string path)
    {
        if (!File.Exists(path)) return false;
        try
        {
            using var fs = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
            return false;
        }
        catch (IOException ex) when (IsSharingViolation(ex)) { return true; }
        catch (UnauthorizedAccessException) { return false; }   // read-only attribute etc.: not a lock
        catch (IOException) { return false; }
    }

    /// <summary>The first existing, locked path of <paramref name="paths"/>, or null when none is in use.</summary>
    public static string? FirstLocked(IEnumerable<string> paths)
    {
        foreach (var p in paths)
            if (IsLocked(p)) return p;
        return null;
    }
}
