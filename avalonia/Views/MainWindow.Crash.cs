using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Threading;
using S1mp1e.Models;
using S1mp1e.Services;

namespace S1mp1e.Views;

public enum ChoiceKind { Secondary, Primary, Danger }

public partial class MainWindow
{
    /// <summary>
    /// Centred glass dialog over a scrim with any number of buttons (left → right). Returns the
    /// clicked button's index, or -1 when dismissed by clicking the scrim.
    /// </summary>
    private Task<int> ChoiceAsync(string title, string message, IReadOnlyList<(string label, ChoiceKind kind)> buttons,
        string shotPrefix = "choice", Func<int>? captureAnswer = null)
    {
        var tcs = new TaskCompletionSource<int>();
        if (this.Content is not Panel root) { tcs.SetResult(-1); return tcs.Task; }

        var scrim = new Border { Background = new SolidColorBrush(Color.FromArgb(120, 0, 0, 0)) };

        var titleT = new TextBlock { Text = title, FontSize = 16, FontWeight = FontWeight.Bold, TextWrapping = TextWrapping.Wrap };
        titleT.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextMain"));
        var msgT = new TextBlock { Text = message, FontSize = 13, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 10, 0, 0), LineHeight = 19 };
        msgT.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextSub"));

        var btnRow = new StackPanel { Orientation = Avalonia.Layout.Orientation.Horizontal, Spacing = 10,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right, Margin = new Thickness(0, 18, 0, 0) };
        var host = new Panel { ZIndex = 400 };
        // Exit: answer at once, then fade + shrink slightly out before the overlay is removed.
        void Close(int result)
        {
            if (!tcs.TrySetResult(result)) return;
            host.IsHitTestVisible = false;
            foreach (var c in host.Children)
            {
                c.Opacity = 0;
                if (c is Border { RenderTransform: not null } b)
                    b.RenderTransform = Avalonia.Media.Transformation.TransformOperations.Parse("scale(0.97)");
            }
            DispatcherTimer.RunOnce(() => { try { root.Children.Remove(host); } catch { } }, TimeSpan.FromMilliseconds(220));
        }

        for (int i = 0; i < buttons.Count; i++)
        {
            var (label, kind) = buttons[i];
            var t = new TextBlock { Text = label, FontSize = 13, FontWeight = FontWeight.SemiBold,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                Foreground = kind == ChoiceKind.Secondary ? (IBrush)(ThemeBrush("TextMain") ?? Brushes.Gray) : Brushes.White };
            var b = new Button { Content = t, Height = 34, MinWidth = 84, CornerRadius = new CornerRadius(9),
                HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center,
                BorderThickness = new Thickness(0), Cursor = new Cursor(StandardCursorType.Hand),
                Padding = new Thickness(14, 0) };
            b.Background = kind switch
            {
                ChoiceKind.Primary => new SolidColorBrush(Color.Parse("#007AFF")),
                ChoiceKind.Danger => new SolidColorBrush(Color.Parse("#FF453A")),
                _ => new SolidColorBrush(Color.Parse("#22808080")),
            };
            var idx = i;
            b.Click += (_, _) => Close(idx);
            btnRow.Children.Add(b);
        }

        var stack = new StackPanel();
        stack.Children.Add(titleT);
        stack.Children.Add(new ScrollViewer { Content = msgT, MaxHeight = 360,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto });
        stack.Children.Add(btnRow);
        var card = new Border
        {
            Width = buttons.Count > 2 ? 440 : 360, Padding = new Thickness(22, 20), CornerRadius = new CornerRadius(16),
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            Child = stack,
            BoxShadow = new BoxShadows(new BoxShadow { Blur = 40, OffsetY = 16, Color = Color.FromArgb(90, 0, 0, 0) }),
        };
        card.Bind(Border.BackgroundProperty, this.GetResourceObservable("DetailBg"));

        // Enter: scrim fades up while the card fades in and settles from 94 % scale (Apple-style ease, no bounce).
        var ease = new Avalonia.Animation.Easings.SplineEasing(0.32, 0.72, 0, 1);
        scrim.Opacity = 0;
        scrim.Transitions = new Avalonia.Animation.Transitions
            { new Avalonia.Animation.DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(200), Easing = ease } };
        card.Opacity = 0;
        card.RenderTransformOrigin = RelativePoint.Center;
        card.RenderTransform = Avalonia.Media.Transformation.TransformOperations.Parse("scale(0.94)");
        card.Transitions = new Avalonia.Animation.Transitions
        {
            new Avalonia.Animation.DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(200), Easing = ease },
            new Avalonia.Animation.TransformOperationsTransition { Property = Visual.RenderTransformProperty, Duration = TimeSpan.FromMilliseconds(280), Easing = ease },
        };
        host.Children.Add(scrim);
        host.Children.Add(card);
        root.Children.Add(host);
        DispatcherTimer.RunOnce(() =>
        {
            scrim.Opacity = 1;
            card.Opacity = 1;
            card.RenderTransform = Avalonia.Media.Transformation.TransformOperations.Parse("scale(1)");
        }, TimeSpan.FromMilliseconds(16));
        scrim.PointerPressed += (_, _) => Close(-1);

        // Sealed capture run: screenshot the dialog as evidence, then auto-answer.
        if (_captureMode && !string.IsNullOrEmpty(_captureOutDir))
        {
            _ = Dispatcher.UIThread.InvokeAsync(async () =>
            {
                var n = ++_confirmShots;
                await Task.Delay(70);
                SaveWindowPng(Path.Combine(_captureOutDir!, $"{shotPrefix}-{n}-enter.png"));   // mid-transition evidence
                await Task.Delay(330); await NextFrameAsync();
                SaveWindowPng(Path.Combine(_captureOutDir!, $"{shotPrefix}-{n}.png"));
                if (int.TryParse(Environment.GetEnvironmentVariable("S1MP1E_CONFIRM_HOLD"), out var holdMs) && holdMs > 0)
                    await Task.Delay(holdMs);
                Close(captureAnswer?.Invoke()
                      ?? (int.TryParse(Environment.GetEnvironmentVariable("S1MP1E_CHOICE"), out var c) ? c : -1));
            });
        }
        return tcs.Task;
    }

    // ---------------- crash diagnosis ----------------

    private DateTime _launchStartedUtc;
    private string? _crashReportHint;

    private static string? ModrinthSlugForId(string modId) => modId.ToLowerInvariant() switch
    {
        var id when ModCompat.IsFabricApiModule(id) => "fabric-api",   // fabric-rendering-v1 etc. ship inside Fabric API
        "cloth_config" or "cloth-config2" => "cloth-config",
        "yet_another_config_lib_v3" or "yacl" => "yacl",
        "fabric-language-kotlin" or "fabric_language_kotlin" => "fabric-language-kotlin",
        "architectury" => "architectury-api",
        "itemscroller" => "item-scroller",
        _ => modId,
    };

    private async Task DiagnoseCrashAsync(string mc, DateTime startedUtc, string? reportHint, int exitCode)
    {
        try
        {
            var mcDir = EffectiveMcDir();
            var gameDir = Path.Combine(mcDir, "instances", mc);
            var dirs = new List<string>
            {
                ConflictScanner.LaunchModDir(mcDir, mc),
                Path.Combine(mcDir, "s1mp1e-mods", mc),
                Path.Combine(gameDir, "mods"),
            }.Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            var dx = await Task.Run(() => CrashDoctor.Analyze(gameDir, dirs, startedUtc, reportHint));
            if (dx is null) return;
            PlayLabel.Text = "遊戲崩潰";
            ToolTip.SetTip(PlayButton, dx.Headline);
            await ShowCrashDialogAsync(mc, dx);
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // The fix button names the mod it acts on (several suspects may be listed above it).
    private static string ShortName(string name) => name.Length <= 14 ? name : name[..13] + "…";

    private async Task ShowCrashDialogAsync(string mc, CrashDiagnosis dx)
    {
        var lines = new List<string>();
        if (dx.MissingDeps.Count > 0)
            lines.Add("缺少：" + string.Join("、", dx.MissingDeps));
        foreach (var s in dx.Suspects.Take(4))
            lines.Add($"• {s.ModName ?? s.ModId}：{s.Evidence}");
        if (dx.Suspects.Count > 4) lines.Add($"…還有 {dx.Suspects.Count - 4} 個");
        if (dx.GlassSuspected)
            lines.Add(dx.Suspects.Count == 0
                ? "崩潰來自 S1mp1e 液態玻璃模組。可以先關掉液態玻璃再玩，並把崩潰報告傳給我們。"
                : "液態玻璃模組也出現在錯誤裡，多半是被上面的模組牽連。");
        if (dx.OutOfMemory)
            lines.Add($"Minecraft 用完了配置的記憶體（目前 {_cfg.Settings.RamMb / 1024.0:0.#} GB），可以到設定調高。");
        if (dx.DriverHint is { Length: > 0 } vendor)
            lines.Add($"崩潰發生在 {vendor} 顯示卡驅動裡，請更新顯示卡驅動後再試。");
        else if (dx.NativeCrash)
            lines.Add("Java 本身崩潰了，多半是顯示卡驅動或硬體問題，請更新顯示卡驅動後再試。");
        if (lines.Count == 0)
            lines.Add("沒有找到明確的元兇模組，可以開啟崩潰報告查看細節。");

        // The one action that most likely fixes it.
        var top = dx.Suspects.FirstOrDefault(s => s.JarPath is not null);
        ModRecord? topRec = null;
        if (top is not null)
        {
            ModIndexFor(EffectiveMcDir());
            topRec = _modIndex.Get(mc, Path.GetFileName(top.JarPath!));
        }
        string? actionLabel = null;
        Func<Task>? action = null;
        if (dx.MissingDeps.Count > 0)
        {
            actionLabel = "補齊前置";
            action = () => InstallMissingDepsAsync(mc, dx.MissingDeps);
        }
        else if (top is not null && topRec is not null && _modIndex!.CanRollback(EffectiveMcDir(), topRec))
        {
            actionLabel = $"退回「{ShortName(top.ModName ?? top.ModId)}」上一版";
            action = () => RollbackRecordAsync(mc, topRec, top.ModName ?? top.ModId);
        }
        else if (top is not null)
        {
            actionLabel = $"停用「{ShortName(top.ModName ?? top.ModId)}」";
            action = () => { DisableJar(top.JarPath!, top.ModName ?? top.ModId); return Task.CompletedTask; };
        }
        else if (dx.GlassSuspected && _cfg.Settings.Glass)
        {
            actionLabel = "關閉液態玻璃";
            action = () => { SetGlassEnabled(false); ShowToast("已關閉液態玻璃，再按 Play 試試"); return Task.CompletedTask; };
        }
        else if (dx.OutOfMemory)
        {
            actionLabel = "調整記憶體";
            action = () => { NavigateTo(PageIdxSettings); return Task.CompletedTask; };
        }

        var buttons = new List<(string, ChoiceKind)> { ("關閉", ChoiceKind.Secondary) };
        bool hasReport = dx.ReportPath is not null || dx.LogPath is not null;
        if (hasReport) buttons.Add(("開啟報告", ChoiceKind.Secondary));
        if (actionLabel is not null) buttons.Add((actionLabel, ChoiceKind.Primary));

        var pick = await ChoiceAsync(dx.Headline, string.Join("\n", lines), buttons, "crash");
        if (pick == 1 && hasReport) OpenWithShell(dx.ReportPath ?? dx.LogPath!);
        else if (pick == buttons.Count - 1 && action is not null) await action();
    }

    private void NavigateTo(int idx)
    {
        _selected = idx;
        MovePill(idx, animate: true);
        UpdateNavWeights(idx);
        ShowPage(idx);
    }

    private static void OpenWithShell(string path)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = true }); }
        catch (Exception ex) { LogCrash(ex); }
    }

    private bool DisableJar(string jarPath, string name)
    {
        try
        {
            LocalModScanner.SetEnabled(jarPath, false);
            ShowToast($"已停用「{name}」，再按 Play 試試");
            _indexSyncSig.Clear();
            return true;
        }
        catch (Exception ex) { LogCrash(ex); ShowToast($"停用「{name}」失敗：" + ex.Message); return false; }
    }

    private void SetGlassEnabled(bool on)
    {
        _cfg.Settings.Glass = on;
        SaveCfg();
        _hydrating = true;
        try { if (GlassToggle is not null) GlassToggle.IsChecked = on; } finally { _hydrating = false; }
    }

    /// <summary>Download the projects behind these missing dependency ids. True when every one was installed.</summary>
    private async Task<bool> InstallMissingDepsAsync(string mc, List<string> deps)
    {
        var loader = ModLoaderFor(mc, EffectiveLoader());
        int ok = 0;
        var failed = new List<string>();
        // Several missing ids can be one project (fabric-rendering-v1 + fabric-networking-api-v1 → Fabric API): fetch it once.
        foreach (var group in deps.GroupBy(d => ModrinthSlugForId(d) ?? d, StringComparer.OrdinalIgnoreCase))
        {
            try { if (await DownloadOneVersionAsync(group.Key, mc, loader)) { ok++; continue; } }
            catch (Exception ex) { LogCrash(ex); if (ex is FileInUseException) { failed.Add(group.Key + "（檔案使用中）"); continue; } }
            failed.Add(group.Key);
        }
        _indexSyncSig.Clear();
        ShowToast(failed.Count == 0
            ? $"已補齊 {ok} 個前置模組"
            : $"補齊 {ok} 個；無法取得：{string.Join("、", failed)}", 4200);
        return failed.Count == 0;
    }

    // ---------------- update with changelog ----------------

    private bool _changelogOpen;

    private async Task ConfirmUpdateWithChangelogAsync(LocalModVm vm)
    {
        if (_changelogOpen) return;   // a second click while the changelog loads or shows must not stack a second update
        _changelogOpen = true;
        try { await ConfirmUpdateWithChangelogCoreAsync(vm); }
        finally { _changelogOpen = false; }
    }

    private async Task ConfirmUpdateWithChangelogCoreAsync(LocalModVm vm)
    {
        string? log = null;
        if (!string.IsNullOrEmpty(vm.UpdateVersionId))
            log = await ModrinthClient.GetChangelogAsync(vm.UpdateVersionId!);
        var from = LocalModVm.PrettyVersion(vm.Version);
        var to = LocalModVm.PrettyVersion(vm.UpdateVersion);
        var head = from.Length > 0 && to.Length > 0 ? $"v{from} → v{to}" : to.Length > 0 ? $"→ v{to}" : "有新版本";
        var body = string.IsNullOrWhiteSpace(log) ? "這個版本沒有寫更新內容。" : PlainChangelog(log!);
        const string tail = "\n\n更新後舊版會保留一份，不合用可以按 ↩ 退回。";
        var pick = await ChoiceAsync($"更新「{vm.Name}」", head + "\n\n" + body + tail,
            new[] { ("取消", ChoiceKind.Secondary), ("更新", ChoiceKind.Primary) }, "changelog");
        if (pick == 1) await UpdateOneLocalModAsync(vm);
    }

    /// <summary>Markdown → readable plain text for the dialog (headings, emphasis, links, bullets), capped in length.</summary>
    private static string PlainChangelog(string md)
    {
        var s = md.Replace("\r\n", "\n");
        s = System.Text.RegularExpressions.Regex.Replace(s, @"!\[[^\]]*\]\([^)]*\)", "");
        s = System.Text.RegularExpressions.Regex.Replace(s, @"\[([^\]]+)\]\([^)]*\)", "$1");
        s = System.Text.RegularExpressions.Regex.Replace(s, @"<[^>]+>", "");
        s = System.Text.RegularExpressions.Regex.Replace(s, @"^\s{0,3}#{1,6}\s*", "", System.Text.RegularExpressions.RegexOptions.Multiline);
        s = System.Text.RegularExpressions.Regex.Replace(s, @"^\s*[-*+]\s+", "• ", System.Text.RegularExpressions.RegexOptions.Multiline);
        s = s.Replace("**", "").Replace("__", "").Replace("`", "");
        s = System.Text.RegularExpressions.Regex.Replace(s, @"\n{3,}", "\n\n").Trim();
        return s.Length > 1500 ? s[..1500].TrimEnd() + "…" : s;
    }

    // ---------------- rollback ----------------

    private async Task RollbackLocalModAsync(LocalModVm vm)
    {
        var rec = RecordOf(vm);
        if (rec is null) return;
        await RollbackRecordAsync(rec.Mc, rec, vm.Name);
        if (_modModeIdx == 1) _ = RunLocalScanAsync();
    }

    /// <summary>Swap the current jar for the kept previous version, and lock it so 全部更新 doesn't re-apply the bad update.</summary>
    private Task RollbackRecordAsync(string mc, ModRecord rec, string displayName)
    {
        try
        {
            var mcDir = EffectiveMcDir();
            var src = Path.Combine(ModIndex.RollbackDir(mcDir, rec.Mc), rec.PrevFile!);
            if (!File.Exists(src)) { ShowToast("找不到上一版的檔案"); return Task.CompletedTask; }
            var dir = Path.Combine(mcDir, "s1mp1e-mods", rec.Mc);
            var cur = Path.Combine(dir, rec.File);
            var dest = Path.Combine(dir, rec.PrevFile!);
            // Check before touching anything: recycling the current jar and then failing to put the old one back would
            // leave the player with neither version.
            var current = new[] { cur, cur + ".disabled" }.Where(File.Exists).ToList();
            if (FileLocks.FirstLocked(current.Append(src).Append(dest).Where(File.Exists)) is { } locked)
                throw new FileInUseException(locked);
            // The old version goes in under a temp name first, so a failure while recycling leaves the current jar working.
            var staged = dest + ".s1rollback";
            File.Move(src, staged, overwrite: true);
            try
            {
                foreach (var cand in current) RecycleFile(cand);
            }
            catch
            {
                // Nothing recycled yet → put the old version back in the rollback folder; otherwise finish the swap so
                // one version is always in place.
                try { File.Move(staged, current.All(File.Exists) ? src : dest, overwrite: true); } catch { }
                throw;
            }
            File.Move(staged, dest, overwrite: true);

            var badVersion = rec.Version;
            rec.File = rec.PrevFile!;
            rec.Version = rec.PrevVersion;
            rec.VersionId = rec.PrevVersionId;
            rec.Sha512 = rec.PrevSha512;
            rec.PrevFile = rec.PrevVersion = rec.PrevVersionId = rec.PrevSha512 = null;
            rec.Locked = true;
            rec.InstalledAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            _modIndex?.Save();
            if (!string.IsNullOrEmpty(rec.ProjectId))
            {
                _cfg.DownloadedModFiles[$"{rec.ProjectId}@{rec.Mc}"] = rec.File;
                SaveCfg();
            }
            _indexSyncSig.Clear();
            var pv = LocalModVm.PrettyVersion(rec.Version);
            ShowToast($"「{displayName}」已退回{(pv.Length > 0 ? " v" + pv : "上一版")}並鎖定版本"
                      + (string.IsNullOrEmpty(badVersion) ? "" : $"（不會再自動更新到 {LocalModVm.PrettyVersion(badVersion)}）"), 3600);
        }
        catch (Exception ex) { LogCrash(ex); ShowToast("退回失敗：" + ex.Message); }
        return Task.CompletedTask;
    }
}
