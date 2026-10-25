using System;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Threading;
using S1mp1e.Services;
using Path = System.IO.Path;

namespace S1mp1e.Views;

/// <summary>
/// Worlds → 「轉換」: a copy of a world for another game version (docs/WORLD_CONVERT.md), made by
/// <c>itest world-convert</c> (tools/worldconv on the installed 26.3). The original world is never touched; what the
/// target lacks is replaced by its closest equivalent and the original kept inside the copy, so converting the copy
/// back restores it. Afterwards a summary of what changed, with the full report one click away.
/// </summary>
public partial class MainWindow
{
    /// <summary>Versions the converter has registries and reverse steps for, newest first.</summary>
    private static readonly string[] ConvertTargets = { "26.3", "26.2", "1.21.1" };

    private bool _converting;

    private void PickConvertTarget(WorldInfo w, Button anchor)
    {
        var targets = ConvertTargets.Where(v => v != w.Version).ToArray();
        if (targets.Length == 0) { ShowToast("這個世界的版本目前沒有可以轉換的目標", 3200); return; }
        var items = targets.Select(v => $"轉成 {v}" + (IsNewer(v, w.Version) ? "（升版）" : "（降版）")).ToArray();
        ShowMenuFor(anchor, items, -1, pick =>
        {
            if (pick >= 0 && pick < targets.Length) _ = ConvertWorldAsync(w, targets[pick], anchor);
        });
    }

    private static bool IsNewer(string a, string? b)
    {
        static int[] Parts(string? v) => (v ?? "0").Split('.').Select(p => int.TryParse(p, out var n) ? n : 0).ToArray();
        var x = Parts(a);
        var y = Parts(b);
        for (int i = 0; i < Math.Max(x.Length, y.Length); i++)
        {
            int p = i < x.Length ? x[i] : 0, q = i < y.Length ? y[i] : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private async Task ConvertWorldAsync(WorldInfo w, string target, Button btn)
    {
        if (_converting) return;
        if (WorldService.InUse(w.Path)) { ShowToast("這個世界正在遊戲中開著，請先離開世界再轉換", 4200); return; }
        bool down = !IsNewer(target, w.Version);
        var body = $"把「{w.Name}」（{w.Version ?? "未知版本"}）轉成 {target}？\n"
                   + $"會另外建立一份「{w.Folder} ({target})」，原本的世界完全不會動。\n"
                   + (down
                       ? $"{target} 沒有的方塊、物品、生物會換成最接近的東西，原物保存在新世界裡；之後把它轉回新版會還原。"
                       : "用新版遊戲本身的升級程式轉換；之前降版時保存的原物也會一起還原。");
        if (!await ConfirmAsync("轉換世界版本", body, "轉換", danger: false)) return;
        var exe = ResolveItestExe();
        if (!File.Exists(exe)) { ShowToast("找不到啟動器元件 itest.exe，請重新安裝", 4200); return; }
        _converting = true;
        btn.IsEnabled = false;
        var original = btn.Content;
        btn.Content = "0%";
        string? outFolder = null, reportDir = null, error = null;
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo(exe)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = System.Text.Encoding.UTF8,
            };
            psi.ArgumentList.Add("world-convert");
            psi.ArgumentList.Add(EffectiveMcDir());
            psi.ArgumentList.Add(w.Folder);
            psi.ArgumentList.Add(target);
            using var p = System.Diagnostics.Process.Start(psi)!;
            _ = p.StandardError.ReadToEndAsync();
            string? line;
            while ((line = await p.StandardOutput.ReadLineAsync()) != null)
            {
                var parts = line.Split(' ');
                if (parts[0] == "PROGRESS" && parts.Length >= 3 && int.TryParse(parts[1], out var d) && int.TryParse(parts[2], out var t) && t > 0)
                {
                    var pct = (int)(100.0 * d / t);
                    Dispatcher.UIThread.Post(() => btn.Content = $"{pct}%");
                }
                else if (line.StartsWith("OUT ", StringComparison.Ordinal)) outFolder = line[4..];
                else if (line.StartsWith("REPORT ", StringComparison.Ordinal)) reportDir = line[7..];
                else if (line.StartsWith("ERR ", StringComparison.Ordinal)) error = line[4..];
            }
            await p.WaitForExitAsync();
        }
        catch (Exception ex) { LogCrash(ex); error = ex.Message; }
        finally
        {
            btn.Content = original;
            btn.IsEnabled = true;
            _converting = false;
        }
        if (outFolder == null)
        {
            ShowToast("轉換失敗：" + (error ?? "未知錯誤") + "（原本的世界沒有變動）", 5200);
            return;
        }
        await RefreshGamePageAsync();
        var summary = reportDir != null ? Summarize(Path.Combine(reportDir, "report.json"), down) : "";
        if (await ConfirmAsync($"已轉換成「{outFolder}」", summary + "\n\n原本的世界沒有變動。", "看完整報告", danger: false)
            && reportDir != null)
        {
            OpenWithShell(Path.Combine(reportDir, "report.txt"));
        }
    }

    /// <summary>A few lines on what the conversion changed, from the converter's report.json.</summary>
    private static string Summarize(string reportJson, bool down)
    {
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(reportJson));
            var groups = doc.RootElement.GetProperty("changes").EnumerateArray()
                .GroupBy(c => c.GetProperty("kind").GetString() ?? "")
                .Select(g => (kind: g.Key, kinds: g.Count(), total: g.Sum(c => c.GetProperty("count").GetInt64())))
                .ToList();
            if (groups.Count == 0) return down ? "沒有需要替換的東西，世界完整轉換。" : "完整轉換，沒有需要還原的東西。";
            static string Label(string k) => k switch
            {
                "block" => "方塊", "biome" => "生態域", "item" => "物品", "component" => "物品屬性", "entity" => "生物/實體",
                "block_entity" => "方塊資料", "structure" => "結構", "advancement" => "進度", "recipe" => "配方", "statistic" => "統計",
                "map_decoration" => "地圖標記", "poi" => "興趣點",
                "restored_block" => "還原方塊", "restored_biome" => "還原生態域", "restored_item" => "還原物品",
                "restored_component" => "還原物品屬性", "restored_entity" => "還原生物", "restored_block_entity" => "還原方塊資料",
                "restored_structure" => "還原結構", "restored_recipes" => "還原配方", "restored_advancements" => "還原進度",
                "restored_statistics" => "還原統計", "changed_in_old_version" => "舊版改過（保留你的改動）",
                "detail" => "細節（舊版不認得的欄位）", "gamerule" => "遊戲規則", "restored_details" => "還原細節",
                _ => k.StartsWith("unmapped_") ? "未對照（換成通用替代品）" : k,
            };
            var lines = groups.OrderByDescending(g => g.total).Select(g => $"・{Label(g.kind)}：{g.kinds} 種，共 {g.total} 個");
            return (down ? "舊版沒有的東西已換成最接近的替代品，原物都保存著：\n" : "已還原：\n") + string.Join("\n", lines);
        }
        catch (Exception ex)
        {
            LogCrash(ex);
            return "（報告讀取失敗，可以從完整報告查看）";
        }
    }
}
