using System;
using Avalonia;
using LiquidGlassAvaloniaUI;

namespace S1mp1e;

internal sealed class Program
{
    // Initialization code. Don't use any Avalonia, third-party APIs or any
    // SynchronizationContext-reliant code before AppMain is called: things aren't
    // initialized yet and stuff might break.
    [STAThread]
    public static void Main(string[] args)
    {
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            try
            {
                System.IO.File.AppendAllText(
                    System.IO.Path.Combine(System.IO.Path.GetTempPath(), "s1mp1e_crash.log"),
                    DateTime.Now + "\n" + e.ExceptionObject + "\n\n");
            }
            catch { }
        };
        if (args.Length == 0 && RelaunchedOutsideRedirectionGuard()) return;
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    [System.Runtime.InteropServices.DllImport("kernel32.dll")]
    private static extern bool GetProcessMitigationPolicy(IntPtr process, int policy, out uint flags, IntPtr size);
    [System.Runtime.InteropServices.DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();
    private const int ProcessRedirectionTrustPolicy = 16;

    /// <summary>
    /// A process started by an installer that runs with Redirection Guard (Inno Setup does) inherits it, and so does
    /// everything it starts — including the game, which then cannot follow the saves / resourcepacks junctions of its
    /// instance folder and crashes while initializing. When this process has it, start a fresh copy through explorer.exe
    /// (as the Start menu would) and exit. A marker stops a loop if the policy is ever enforced machine-wide.
    /// </summary>
    private static bool RelaunchedOutsideRedirectionGuard()
    {
        try
        {
            if (!OperatingSystem.IsWindows()) return false;
            if (!GetProcessMitigationPolicy(GetCurrentProcess(), ProcessRedirectionTrustPolicy, out var flags, (IntPtr)4)) return false;
            if ((flags & 1) == 0) return false;   // EnforceRedirectionTrust off: nothing to do
            var marker = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "s1mp1e-relaunch.marker");
            if (System.IO.File.Exists(marker) && DateTime.UtcNow - System.IO.File.GetLastWriteTimeUtc(marker) < TimeSpan.FromMinutes(1))
                return false;   // the relaunched copy has it too: run anyway rather than loop
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) return false;
            System.IO.File.WriteAllText(marker, "");
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(
                System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "explorer.exe"), "\"" + exe + "\"")
            { UseShellExecute = false });
            return true;
        }
        catch { return false; }
    }

    // Avalonia configuration, don't remove; also used by the visual designer.
    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .UseLiquidGlassPerformanceDefaults()
            .LogToTrace();
}
