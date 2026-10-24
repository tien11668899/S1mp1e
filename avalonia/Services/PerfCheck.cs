using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;

namespace S1mp1e.Services;

/// <summary>
/// The performance side of the Play button (Settings → 啟動前效能檢查). On the 4K benchmark machine the GPU is the limit,
/// so whatever else draws on it — a video wallpaper, a browser playing video, a Discord stream — comes straight off the
/// game's frame rate, and borderless fullscreen adds the Windows compositor to every frame (docs/PERFORMANCE.md).
/// Nothing here changes anything outside the game's own options.txt; it only tells the player.
/// </summary>
public static class PerfCheck
{
    /// <summary>Programs using at least this share of the GPU (3D + video decode engines) are reported.</summary>
    private const double HogPercent = 3.0;

    /// <summary>Never reported: the game, this launcher, and Windows' own composition.</summary>
    private static readonly HashSet<string> Ignored = new(StringComparer.OrdinalIgnoreCase)
    {
        "javaw", "java", "S1mp1e", "itest", "dwm", "csrss", "System", "Idle", "explorer",
    };

    private static readonly Dictionary<string, string> Friendly = new(StringComparer.OrdinalIgnoreCase)
    {
        ["wallpaper64"] = "Wallpaper Engine", ["wallpaper32"] = "Wallpaper Engine",
        ["chrome"] = "Chrome", ["msedge"] = "Edge", ["firefox"] = "Firefox", ["opera"] = "Opera", ["brave"] = "Brave",
        ["Discord"] = "Discord", ["obs64"] = "OBS", ["obs32"] = "OBS", ["steamwebhelper"] = "Steam",
        ["Medal"] = "Medal", ["Overwolf"] = "Overwolf", ["NVIDIA Overlay"] = "NVIDIA 覆蓋層", ["claude"] = "Claude",
        ["RobloxPlayerBeta"] = "Roblox", ["Spotify"] = "Spotify",
    };

    /// <summary>
    /// Programs using the GPU right now, by share, measured over about a second through the same per-process
    /// "GPU Engine" counters Task Manager shows. Empty when the counters are unavailable.
    /// </summary>
    public static List<(string name, double percent)> GpuHogs()
    {
        var byPid = new Dictionary<int, double>();
        try
        {
            if (PdhOpenQueryW(null, IntPtr.Zero, out var query) != 0) return new();
            try
            {
                if (PdhAddEnglishCounterW(query, @"\GPU Engine(*)\Utilization Percentage", IntPtr.Zero, out var counter) != 0) return new();
                PdhCollectQueryData(query);
                Thread.Sleep(1000);
                PdhCollectQueryData(query);
                uint size = 0;
                uint status = PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE | PDH_FMT_NOCAP100, ref size, out _, IntPtr.Zero);
                if (status != PDH_MORE_DATA || size == 0) return new();
                var buf = Marshal.AllocHGlobal((int)size);
                try
                {
                    if (PdhGetFormattedCounterArrayW(counter, PDH_FMT_DOUBLE | PDH_FMT_NOCAP100, ref size, out var count, buf) != 0) return new();
                    const int item = 24;   // { LPWSTR szName; PDH_FMT_COUNTERVALUE { DWORD CStatus; (pad) double } }
                    for (int i = 0; i < count; i++)
                    {
                        var name = Marshal.PtrToStringUni(Marshal.ReadIntPtr(buf, i * item)) ?? "";
                        if (Marshal.ReadInt32(buf, i * item + 8) != 0) continue;   // CStatus: not valid
                        if (!name.Contains("engtype_3D", StringComparison.OrdinalIgnoreCase)
                            && !name.Contains("engtype_VideoDecode", StringComparison.OrdinalIgnoreCase)) continue;
                        var m = System.Text.RegularExpressions.Regex.Match(name, @"pid_(\d+)_");
                        if (!m.Success) continue;
                        double v = BitConverter.Int64BitsToDouble(Marshal.ReadInt64(buf, i * item + 16));
                        int pid = int.Parse(m.Groups[1].Value);
                        byPid[pid] = byPid.GetValueOrDefault(pid) + v;
                    }
                }
                finally { Marshal.FreeHGlobal(buf); }
            }
            finally { PdhCloseQuery(query); }
        }
        catch { return new(); }

        // one entry per program (a browser or Discord runs several processes)
        var byName = new Dictionary<string, double>();
        int self = Environment.ProcessId;
        foreach (var (pid, v) in byPid)
        {
            if (pid == self) continue;
            string proc;
            try { using var p = Process.GetProcessById(pid); proc = p.ProcessName; } catch { continue; }
            if (Ignored.Contains(proc)) continue;
            string label = Friendly.TryGetValue(proc, out var f) ? f : proc;
            byName[label] = byName.GetValueOrDefault(label) + v;
        }
        return byName.Where(kv => kv.Value >= HogPercent).OrderByDescending(kv => kv.Value)
                     .Select(kv => (kv.Key, kv.Value)).ToList();
    }

    /// <summary>True on a laptop running on battery (Windows then limits CPU and GPU clocks).</summary>
    public static bool OnBattery()
    {
        try
        {
            return GetSystemPowerStatus(out var s) && s.ACLineStatus == 0 && s.BatteryFlag != 128;   // 128 = no battery
        }
        catch { return false; }
    }

    /// <summary>The non-blocking notes shown once the game is running: GPU users and running on battery.</summary>
    public static List<string> BackgroundNotes()
    {
        var notes = new List<string>();
        if (OnBattery()) notes.Add("筆電目前沒有插電：Windows 會限制效能，FPS 會明顯變低");
        var hogs = GpuHogs();
        if (hogs.Count > 0)
            notes.Add("這些程式正在使用顯示卡，遊戲 FPS 會被分走：" +
                      string.Join("、", hogs.Take(4).Select(h => $"{h.name} {h.percent:0}%")));
        return notes;
    }

    /// <summary>
    /// True when the version's options.txt asks for borderless fullscreen: fullscreen on with exclusive fullscreen off
    /// (26.x has both options; versions without "exclusiveFullscreen" are never flagged).
    /// </summary>
    public static bool IsBorderless(string optionsPath)
    {
        try
        {
            if (!File.Exists(optionsPath)) return false;
            bool fullscreen = false, exclusiveOff = false;
            foreach (var line in File.ReadLines(optionsPath))
            {
                if (line.Trim() == "fullscreen:true") fullscreen = true;
                else if (line.Trim() == "exclusiveFullscreen:false") exclusiveOff = true;
            }
            return fullscreen && exclusiveOff;
        }
        catch { return false; }
    }

    /// <summary>Switch that options.txt to exclusive fullscreen (every other line kept). False when it could not write.</summary>
    public static bool MakeExclusive(string optionsPath)
    {
        try
        {
            var text = File.ReadAllText(optionsPath);
            var updated = text.Replace("exclusiveFullscreen:false", "exclusiveFullscreen:true");
            if (updated == text) return false;
            var tmp = optionsPath + ".s1tmp";
            File.WriteAllText(tmp, updated, new System.Text.UTF8Encoding(false));
            File.Move(tmp, optionsPath, overwrite: true);
            return true;
        }
        catch { return false; }
    }

    // ---- Win32 ----

    private const uint PDH_FMT_DOUBLE = 0x00000200, PDH_FMT_NOCAP100 = 0x00008000, PDH_MORE_DATA = 0x800007D2;

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhOpenQueryW(string? dataSource, IntPtr userData, out IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhAddEnglishCounterW(IntPtr query, string fullCounterPath, IntPtr userData, out IntPtr counter);

    [DllImport("pdh.dll")]
    private static extern uint PdhCollectQueryData(IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhGetFormattedCounterArrayW(IntPtr counter, uint format, ref uint bufferSize, out uint itemCount, IntPtr itemBuffer);

    [DllImport("pdh.dll")]
    private static extern uint PdhCloseQuery(IntPtr query);

    [StructLayout(LayoutKind.Sequential)]
    private struct SystemPowerStatus
    {
        public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
        public int BatteryLifeTime, BatteryFullLifeTime;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemPowerStatus(out SystemPowerStatus status);
}
