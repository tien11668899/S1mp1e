using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia.Controls;
using Path = System.IO.Path;
using S1mp1e.Services;

namespace S1mp1e.Views;

// 「從其他版本補齊」: the mods another version has and this one lacks, each downloaded in the build Modrinth has for this
// version (with its required dependencies), in one go. Nothing is copied across versions: a jar built for one Minecraft
// version can crash another, so only Modrinth-identified mods are offered, resolved again for this version.
public partial class MainWindow
{
    private Border? _copyBtn;   // the browse toolbar's 從其他版本補齊 button (the capture harness opens its menu)

    /// <summary>One mod of the source version that this version lacks, and what Modrinth has for this version.</summary>
    private sealed record MissingMod(string ProjectId, string Name, ModrinthVersion? Target);

    /// <summary>The other versions whose mod folder has at least one enabled mod jar, with that count.</summary>
    private List<(string mc, int jars)> VersionsWithMods(string mcDir, string current)
    {
        var list = new List<(string, int)>();
        foreach (var v in SupportedVersions)
        {
            if (string.Equals(v, current, StringComparison.OrdinalIgnoreCase)) continue;
            var dir = ConflictScanner.LaunchModDir(mcDir, v);
            if (!Directory.Exists(dir)) continue;
            int n;
            try
            {
                n = Directory.EnumerateFiles(dir, "*.jar")
                    .Count(f => !Path.GetFileName(f).StartsWith("glass-", StringComparison.OrdinalIgnoreCase));
            }
            catch { continue; }
            if (n > 0) list.Add((v, n));
        }
        return list;
    }

    private void ShowCopyFromVersionMenu(Control anchor)
    {
        var mc = CurrentMc();
        var sources = VersionsWithMods(EffectiveMcDir(), mc);
        if (sources.Count == 0) { ShowToast("其他版本還沒有安裝模組"); return; }
        ShowMenuFor(anchor, sources.Select(s => $"從 {s.mc} 補齊（{s.jars} 個模組）").ToArray(), -1,
            pick => { if (pick >= 0 && pick < sources.Count) _ = CopyModsFromVersionAsync(sources[pick].mc); });
    }

    /// <summary>
    /// Compare <paramref name="src"/>'s mods with the current version's, list what is missing here and whether Modrinth has
    /// a build of it for this version, and after one confirmation download every available one.
    /// </summary>
    private async Task CopyModsFromVersionAsync(string src)
    {
        var mc = CurrentMc();
        var loader = ModLoaderFor(mc, EffectiveLoader());
        var mcDir = EffectiveMcDir();
        try
        {
            ShowToast($"比對 {src} 和 {mc} 的模組…", 60000);
            // Both folders identified against Modrinth (the scan's hash backfill fills in project ids and slugs).
            await SyncModIndexAsync(src);
            await SyncModIndexAsync(mc);
            var idx = ModIndexFor(mcDir);
            var srcDir = ConflictScanner.LaunchModDir(mcDir, src);
            var dstDir = ConflictScanner.LaunchModDir(mcDir, mc);

            bool OnDisk(string dir, ModRecord r, bool enabledOnly) =>
                File.Exists(Path.Combine(dir, r.File)) || (!enabledOnly && File.Exists(Path.Combine(dir, r.File + ".disabled")));
            bool LauncherManaged(ModRecord r) =>
                r.Source is "default" or "perf"
                || (r.Slug is { Length: > 0 } s && (DefaultModSlugs.Contains(s) || PerfModSlugs.Contains(s)));

            // What this version already has: by Modrinth project, and by mod id for jars Modrinth does not know.
            var dstRecords = idx.ForMc(mc).Where(r => OnDisk(dstDir, r, enabledOnly: false)).ToList();
            var haveProjects = new HashSet<string>(dstRecords.Where(r => !string.IsNullOrEmpty(r.ProjectId)).Select(r => r.ProjectId!),
                StringComparer.OrdinalIgnoreCase);
            var haveModIds = await Task.Run(() => Directory.Exists(dstDir)
                ? Directory.EnumerateFiles(dstDir)
                    .Where(f => f.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase))
                    .SelectMany(f => CrashDoctor.ModIdsOf(f).Select(x => x.id))
                    .ToHashSet(StringComparer.OrdinalIgnoreCase)
                : new HashSet<string>(StringComparer.OrdinalIgnoreCase));

            // What the source has that the player chose themselves: enabled, not a dependency pulled in for another mod
            // (that comes along with its parent), not one the launcher installs and pins per version.
            var srcRecords = idx.ForMc(src)
                .Where(r => OnDisk(srcDir, r, enabledOnly: true) && !r.File.StartsWith("glass-", StringComparison.OrdinalIgnoreCase))
                .ToList();
            var unknown = new List<string>();
            var wanted = new Dictionary<string, ModRecord>(StringComparer.OrdinalIgnoreCase);
            foreach (var r in srcRecords)
            {
                if (LauncherManaged(r) || r.IsDependency) continue;
                var modIds = CrashDoctor.ModIdsOf(Path.Combine(srcDir, r.File)).Select(x => x.id).ToList();
                if (modIds.Any(haveModIds.Contains)) continue;
                if (string.IsNullOrEmpty(r.ProjectId))
                {
                    unknown.Add(r.Name ?? r.File);
                    continue;
                }
                if (haveProjects.Contains(r.ProjectId!)) continue;
                wanted.TryAdd(r.ProjectId!, r);
            }

            if (wanted.Count == 0)
            {
                ShowToast(unknown.Count == 0
                    ? $"{mc} 已經有 {src} 的所有模組"
                    : $"{mc} 已經有 {src} 能從 Modrinth 下載的所有模組（{unknown.Count} 個不在 Modrinth 上，無法自動下載）", 4200);
                return;
            }

            // The build of each one for this version + loader (release first, like a normal download).
            ShowToast($"查詢 {wanted.Count} 個模組的 {mc} 版本…", 60000);
            var names = await ModrinthClient.GetProjectsAsync(wanted.Keys.ToList());
            var missing = new List<MissingMod>();
            foreach (var chunk in wanted.Values.Chunk(6))
            {
                var resolved = await Task.WhenAll(chunk.Select(async r =>
                {
                    ModrinthVersion? v = null;
                    try { v = await ModrinthClient.ResolvePrimaryVersionAsync(r.ProjectId!, mc, loader); }
                    catch (Exception ex) { LogCrash(ex); }
                    var name = names.TryGetValue(r.ProjectId!, out var p) ? p.Title : r.Name ?? r.File;
                    return new MissingMod(r.ProjectId!, name, v is { Files.Length: > 0 } ? v : null);
                }));
                missing.AddRange(resolved);
            }
            HideToast();   // the progress toast must not sit over the dialog
            var available = missing.Where(m => m.Target != null).OrderBy(m => m.Name, StringComparer.OrdinalIgnoreCase).ToList();
            var noBuild = missing.Where(m => m.Target == null).OrderBy(m => m.Name, StringComparer.OrdinalIgnoreCase).ToList();

            static string NameList(IEnumerable<MissingMod> mods) => string.Join("\n", mods.Select(m => "• " + m.Name));
            var msg = $"{src} 有 {missing.Count} 個模組是 {mc} 沒有的。";
            if (available.Count > 0) msg += $"\n\n可以下載（{available.Count}）：\n{NameList(available)}";
            if (noBuild.Count > 0) msg += $"\n\n沒有 {mc} 的版本（{noBuild.Count}）：\n{NameList(noBuild)}";
            if (unknown.Count > 0) msg += $"\n\n不在 Modrinth 上、無法自動下載（{unknown.Count}）：\n"
                                          + string.Join("\n", unknown.OrderBy(n => n, StringComparer.OrdinalIgnoreCase).Select(n => "• " + n));
            msg += available.Count > 0
                ? "\n\n需要的前置模組會一起下載；Fabric API、Sodium 等由啟動器管理的模組不在此列。"
                : "\n\nFabric API、Sodium 等由啟動器管理的模組不在此列。";

            if (available.Count == 0)
            {
                await ChoiceAsync($"從 {src} 補齊模組", msg, new[] { ("知道了", ChoiceKind.Primary) }, "modcopy");
                return;
            }
            if (!await ConfirmAsync($"從 {src} 補齊模組", msg, $"下載 {available.Count} 個", danger: false)) return;

            // One shared set for the whole run, so a dependency several of them need is fetched once.
            var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            int done = 0, ok = 0, inUse = 0;
            var failed = new List<string>();
            foreach (var m in available)
            {
                done++;
                ShowToast($"下載中 {done}/{available.Count}：{m.Name}", 60000);
                try
                {
                    if (await DownloadWithDepsAsync(m.ProjectId, mc, loader, null, visited, 0, exactVersionId: m.Target!.Id)) ok++;
                    else failed.Add(m.Name);
                }
                catch (FileInUseException) { inUse++; }
                catch (Exception ex) { LogCrash(ex); failed.Add(m.Name); }
            }
            // A downloaded mod may need a newer version of a dependency this version already has.
            if (ok > 0)
            {
                try
                {
                    foreach (var u in await EnsureDependencyVersionsAsync(mc, loader))
                        _fileOpNotes.Add($"「{string.Join("、", u.neededBy.Distinct().Take(2))}」需要 {u.depName} {u.need}（目前 {u.have}），沒有找到符合的版本");
                }
                catch (Exception ex) { LogCrash(ex); }
            }
            _indexSyncSig.Clear();

            var summary = $"已從 {src} 補齊 {ok} 個模組到 {mc}";
            if (failed.Count > 0) summary += $"，{failed.Count} 個下載失敗（{string.Join("、", failed.Take(3))}）";
            if (inUse > 0) summary += $"，{inUse} 個因為遊戲正在使用檔案而沒有下載";
            ShowToast(summary, 4200);
            if (_fileOpNotes.Count > 0) { await Task.Delay(4300); FlushFileOpNotes(); }
            UpdateAllButtonState();
            if (_modModeIdx == 1) _ = RunLocalScanAsync();
        }
        catch (Exception ex) { ReportModOpFailure(ex); }
    }
}
