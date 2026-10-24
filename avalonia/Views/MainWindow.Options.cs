using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Path = System.IO.Path;

namespace S1mp1e.Views;

/// <summary>
/// Settings → 「遊戲設定沿用」. A version's first launch already starts from the most recently played version's
/// options.txt (src-tauri/src/options_sync.rs, run by itest at launch); this button copies one version's settings over
/// the version selected on the Start page, through <c>itest options-import</c>, so the rules live in one place.
/// </summary>
public partial class MainWindow
{
    private void OnImportOptionsClick(object? sender, RoutedEventArgs e)
    {
        if (sender is not Control anchor) return;
        var mcDir = EffectiveMcDir();
        var to = CurrentMc();
        var instances = Path.Combine(mcDir, "instances");
        var sources = Directory.Exists(instances)
            ? new DirectoryInfo(instances).GetDirectories()
                .Where(d => d.Name != to && File.Exists(Path.Combine(d.FullName, "options.txt")))
                .Select(d => (name: d.Name, when: File.GetLastWriteTime(Path.Combine(d.FullName, "options.txt"))))
                .OrderByDescending(s => s.when)
                .ToList()
            : new();
        if (sources.Count == 0) { ShowToast("其他版本都還沒有遊戲設定（先開過一次遊戲）", 3200); return; }
        var items = sources.Select(s => $"從 {s.name} 匯入到 {to} · {RelDays(s.when)}").ToArray();
        ShowMenuFor(anchor, items, -1, pick =>
        {
            if (pick >= 0 && pick < sources.Count) _ = ImportOptionsAsync(mcDir, sources[pick].name, to);
        });
    }

    private async Task ImportOptionsAsync(string mcDir, string from, string to)
    {
        // A running game writes its options.txt back when settings change and on exit, which would undo the import.
        if (GameRunning) { ShowToast("遊戲正在執行。請先關閉遊戲再匯入設定", 3600); return; }
        if (!await ConfirmAsync("匯入遊戲設定",
                $"把 {from} 的按鍵、靈敏度、音量、語言等遊戲設定套用到 {to}？\n{to} 原本的設定會備份成 options.txt.bak。",
                "匯入", danger: false))
            return;
        var exe = ResolveItestExe();
        if (!File.Exists(exe)) { ShowToast("找不到啟動器元件 itest.exe，請重新安裝", 4200); return; }
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo(exe)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = System.Text.Encoding.UTF8,
            };
            psi.ArgumentList.Add("options-import");
            psi.ArgumentList.Add(mcDir);
            psi.ArgumentList.Add(from);
            psi.ArgumentList.Add(to);
            using var p = System.Diagnostics.Process.Start(psi)!;
            var outTask = p.StandardOutput.ReadToEndAsync();
            _ = p.StandardError.ReadToEndAsync();
            await p.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(20));
            var line = (await outTask).Trim().Split('\n').LastOrDefault()?.Trim() ?? "";
            if (line.StartsWith("OK ", StringComparison.Ordinal))
                ShowToast(line[3..] == "0" ? $"{to} 的遊戲設定本來就和 {from} 一樣"
                                           : $"已套用 {from} 的遊戲設定：{to} 有 {line[3..]} 項設定改變", 3600);
            else
                ShowToast(line.StartsWith("ERR ", StringComparison.Ordinal) ? line[4..] : "匯入失敗，請稍後再試", 4200);
        }
        catch (TimeoutException) { ShowToast("匯入逾時，請稍後再試", 3600); }
        catch (Exception ex) { LogCrash(ex); ShowToast("匯入失敗，請稍後再試", 3600); }
    }
}
