using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using Path = System.IO.Path;

namespace S1mp1e.Views;

/// <summary>
/// Settings → 「遊戲設定沿用」. A version's first launch already starts from the most recently played version's
/// options.txt (src-tauri/src/options_sync.rs, run by itest at launch); this button copies one version's whole settings
/// (game options, converted between 1.8.9-era and 1.13+ formats, plus the mod settings both have) over one or every
/// other version, through <c>itest options-import</c>, so the rules live in one place. Two menus: source, then target.
/// </summary>
public partial class MainWindow
{
    private void OnImportOptionsClick(object? sender, RoutedEventArgs e)
    {
        if (sender is not Control anchor) return;
        var mcDir = EffectiveMcDir();
        var instances = Path.Combine(mcDir, "instances");
        var all = Directory.Exists(instances)
            ? new DirectoryInfo(instances).GetDirectories()
                .Select(d => (name: d.Name, opts: Path.Combine(d.FullName, "options.txt")))
                .ToList()
            : new();
        var sources = all.Where(v => File.Exists(v.opts))
            .Select(v => (v.name, when: File.GetLastWriteTime(v.opts)))
            .OrderByDescending(s => s.when)
            .ToList();
        if (sources.Count == 0 || all.Count < 2) { ShowToast("至少要有兩個開過的版本才能搬設定", 3200); return; }
        var current = CurrentMc();
        int sel = sources.FindIndex(s => s.name == current);
        var items = sources.Select(s => $"來源：{s.name} 的設定 · {RelDays(s.when)}").ToArray();
        ShowMenuFor(anchor, items, sel, pick =>
        {
            if (pick < 0 || pick >= sources.Count) return;
            var from = sources[pick].name;
            var targets = all.Select(v => v.name).Where(n => n != from)
                .OrderByDescending(n => n == current).ThenBy(n => n).ToList();
            // the pick runs before this menu closes: open the target menu after it, so it takes over the overlay
            Dispatcher.UIThread.Post(() => PickImportTargets(anchor, mcDir, from, targets));
        });
    }

    private void PickImportTargets(Control anchor, string mcDir, string from, List<string> targets)
    {
        var items = new List<string>();
        if (targets.Count > 1) items.Add($"套用到全部其他版本（{targets.Count} 個）");
        items.AddRange(targets.Select(t => $"套用到 {t}"));
        ShowMenuFor(anchor, items.ToArray(), -1, pick =>
        {
            if (pick < 0 || pick >= items.Count) return;
            var to = targets.Count > 1 ? (pick == 0 ? targets : new List<string> { targets[pick - 1] }) : targets;
            _ = ImportOptionsAsync(mcDir, from, to);
        });
    }

    private async Task ImportOptionsAsync(string mcDir, string from, List<string> targets)
    {
        // A running game writes its options.txt back when settings change and on exit, which would undo the import.
        if (GameRunning) { ShowToast("遊戲正在執行。請先關閉遊戲再匯入設定", 3600); return; }
        var names = string.Join("、", targets);
        if (!await ConfirmAsync("搬移遊戲設定",
                $"把 {from} 的遊戲設定（按鍵、靈敏度、畫面、音量、語言）和模組設定，整份套用到 {names}？\n"
                + "1.8.9 這類舊版本的按鍵格式會自動轉換；原本的設定會備份成 .bak 檔。",
                "套用", danger: false))
            return;
        var exe = ResolveItestExe();
        if (!File.Exists(exe)) { ShowToast("找不到啟動器元件 itest.exe，請重新安裝", 4200); return; }
        int opts = 0, mods = 0, ok = 0;
        var errors = new List<string>();
        foreach (var to in targets)
        {
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
                {
                    var n = line[3..].Split(' ');
                    opts += int.TryParse(n[0], out var a) ? a : 0;
                    mods += n.Length > 1 && int.TryParse(n[1], out var b) ? b : 0;
                    ok++;
                }
                else errors.Add(line.StartsWith("ERR ", StringComparison.Ordinal) ? line[4..] : $"{to}：匯入失敗");
            }
            catch (TimeoutException) { errors.Add($"{to}：逾時"); }
            catch (Exception ex) { LogCrash(ex); errors.Add($"{to}：匯入失敗"); }
        }
        var where = targets.Count == 1 ? targets[0] : $"{ok} 個版本";
        string msg = ok == 0 ? ""
            : opts + mods == 0 ? $"{where} 的設定本來就和 {from} 一樣"
            : $"已套用 {from} 的設定到 {where}：遊戲設定 {opts} 項、模組設定 {mods} 項改變";
        if (errors.Count > 0) msg = (msg.Length > 0 ? msg + "\n" : "") + string.Join("\n", errors);
        ShowToast(msg, errors.Count > 0 ? 5200 : 3600);
    }
}
