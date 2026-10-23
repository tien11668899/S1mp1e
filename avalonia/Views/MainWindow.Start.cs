using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Interactivity;
using S1mp1e.Services;

namespace S1mp1e.Views;

public partial class MainWindow
{
    // ---------------- knife pack update ----------------

    private int? _knifeLatest;
    private bool _knifeChecking;

    private async Task CheckKnifeUpdateAsync()
    {
        if (_knifeChecking || _captureMode) return;
        _knifeChecking = true;
        try
        {
            var m = await KnifePackService.FetchManifestAsync(CancellationToken.None);
            _knifeLatest = m.Version;
            UpdateKnifeRow();
        }
        catch { _knifeLatest = 0; }   // offline: no prompt this session
        finally { _knifeChecking = false; }
    }

    // ---------------- pre-launch health check ----------------

    private bool _healthChecked;

    private bool _healthChecking;

    private async Task HealthCheckThenPlayAsync(object? sender, RoutedEventArgs e)
    {
        if (_healthChecking) return;   // a second Play (e.g. 伺服器「加入」) while the check dialog is up
        _healthChecking = true;
        try { await HealthCheckThenPlayCoreAsync(sender, e); }
        finally { _healthChecking = false; }
    }

    private async Task HealthCheckThenPlayCoreAsync(object? sender, RoutedEventArgs e)
    {
        var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? "26.2" : VersionBox.SelectedText;
        var loader = EffectiveLoader();
        PlayButton.IsEnabled = false;
        bool go;
        try { go = await PreLaunchCheckAsync(mc, loader); }
        catch (Exception ex) { LogCrash(ex); go = true; }   // the check must never block playing
        PlayButton.IsEnabled = true;
        if (!go) { _joinAddress = null; return; }
        _healthChecked = true;
        OnPlay(sender, e);
    }

    /// <summary>Warn (only when something is wrong) before a launch that is likely to fail. Returns false to cancel.</summary>
    private async Task<bool> PreLaunchCheckAsync(string mc, string loader)
    {
        var lines = new List<string>();

        int totalGb = GetTotalRamGb();
        double allocGb = _cfg.Settings.RamMb / 1024.0;
        if (totalGb > 0 && allocGb > totalGb - 2)
            lines.Add($"配置了 {allocGb:0.#} GB 記憶體，但這台電腦只有 {totalGb} GB，遊戲或系統可能會卡死。建議 {Math.Max(2, totalGb / 2)} GB 左右。");

        List<(string dep, List<string> neededBy)> missing = new();
        List<FabricModInfo> incompatible = new();
        List<(string dep, string depName, string need, string have, List<string> neededBy)> outdated = new();
        if (loader == "fabric" && mc != "1.8.9")
        {
            var mcDir = EffectiveMcDir();
            var launchDir = ConflictScanner.LaunchModDir(mcDir, mc);
            var mods = await Task.Run(() => ModCompat.ReadFolder(new[] { launchDir, Path.Combine(launchDir, ".perf") }));
            var provided = new List<string>();
            if (_cfg.Settings.Glass) provided.Add("s1mp1e");
            // The launcher installs Fabric API itself before such a launch (default mods, or because liquid glass needs it).
            bool fabricApiEnsured = _cfg.Settings.DefaultMods || _cfg.Settings.Glass;
            missing = ModCompat.MissingDependencies(mods, provided)
                .Where(m => !(fabricApiEnsured && ModCompat.IsFabricApiModule(m.dep)))
                .ToList();
            incompatible = mods.Where(m => ModCompat.MinecraftMismatch(m, mc) is not null).ToList();
            outdated = ModCompat.UnmetVersions(mods);
            foreach (var (dep, by) in missing)
                lines.Add($"缺少前置「{dep}」（{string.Join("、", by.Distinct().Take(3))} 需要）");
            foreach (var u in outdated)
                lines.Add($"「{string.Join("、", u.neededBy.Distinct().Take(2))}」需要 {u.depName} {u.need}，目前是 {u.have}（遊戲會拒絕啟動）");
            foreach (var m in incompatible.Take(4))
                lines.Add($"「{m.Name}」不支援 {mc}（它要 Minecraft {ModCompat.MinecraftMismatch(m, mc)}）");
            if (incompatible.Count > 4) lines.Add($"…還有 {incompatible.Count - 4} 個不支援 {mc} 的模組");
        }
        if (lines.Count == 0) return true;

        var buttons = new List<(string, ChoiceKind)> { ("取消", ChoiceKind.Secondary), ("仍要啟動", ChoiceKind.Secondary) };
        string? fix = missing.Count > 0 ? "補齊後啟動" : outdated.Count > 0 ? "更新前置後啟動" : incompatible.Count > 0 ? "停用後啟動" : null;
        if (fix is not null) buttons.Add((fix, ChoiceKind.Primary));
        var pick = await ChoiceAsync("啟動前檢查", string.Join("\n", lines), buttons, "health");
        if (pick <= 0) return false;
        if (pick == 2)
        {
            // A fix that did not fully work must not be followed by the launch it was meant to rescue: the player
            // would get the very crash the check warned about. The toast says what is still missing.
            if (missing.Count > 0) return await InstallMissingDepsAsync(mc, missing.Select(m => m.dep).ToList());
            if (outdated.Count > 0)
            {
                List<(string dep, string depName, string need, string have, List<string> neededBy)> still;
                try { still = await EnsureDependencyVersionsAsync(mc, ModLoaderFor(mc, loader)); }
                catch (FileInUseException fe) { ShowToast(fe.Message + "，請先關閉遊戲再試", 4200); return false; }
                if (still.Count == 0) { ShowToast("已更新前置模組"); return true; }
                var u = still[0];
                ShowToast($"找不到符合的 {u.depName}（需要 {u.need}）。可以到「模組」把「{u.neededBy[0]}」退回上一版", 6000);
                return false;
            }
            bool allOff = true;
            foreach (var m in incompatible) allOff &= DisableJar(m.Path, m.Name);
            return allOff;
        }
        return true;
    }
}
