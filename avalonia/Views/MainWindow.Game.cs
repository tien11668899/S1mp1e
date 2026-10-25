using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Input;
using Avalonia.Platform;
using Path = System.IO.Path;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using S1mp1e.Services;

namespace S1mp1e.Views;

public partial class MainWindow
{
    private string _gameTab = "worlds";
    private readonly List<(string key, Border chip, TextBlock label)> _gameChips = new();
    private CancellationTokenSource? _gameCts;
    private string? _joinAddress;

    private static readonly (string key, string name)[] GameTabDefs = { ("worlds", "世界"), ("shots", "截圖"), ("servers", "伺服器") };

    private void BuildGameTabs()
    {
        if (_gameChips.Count > 0) return;
        foreach (var (key, name) in GameTabDefs)
        {
            var label = new TextBlock { Text = name, FontSize = 12, FontWeight = FontWeight.SemiBold,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
            var chip = new Border { CornerRadius = new CornerRadius(8), Padding = new Thickness(13, 5),
                Cursor = new Cursor(StandardCursorType.Hand), Child = label };
            chip.Transitions = new Transitions { new BrushTransition { Property = Border.BackgroundProperty, Duration = TimeSpan.FromMilliseconds(160) } };
            var k = key;
            chip.PointerPressed += (_, _) => { if (_gameTab != k) { _gameTab = k; _ = RefreshGamePageAsync(); } };
            _gameChips.Add((key, chip, label));
            GameTabs.Children.Add(chip);
        }
    }

    private void StyleGameTabs()
    {
        foreach (var (key, chip, label) in _gameChips)
        {
            bool on = key == _gameTab;
            chip.Background = on ? (ThemeBrush("Accent") ?? new SolidColorBrush(Color.Parse("#007AFF")))
                                 : new SolidColorBrush(Color.Parse("#22808080"));
            label.Foreground = on ? Brushes.White : (ThemeBrush("TextMain") ?? Brushes.Gray);
        }
    }

    private Button MiniButton(string text, Action onClick)
    {
        var b = new Button { Content = text, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        b.Classes.Add("mini");
        b.Click += (_, _) => onClick();
        return b;
    }

    private void SetGameToolbar(params Button[] buttons)
    {
        GameToolbar.Children.Clear();
        foreach (var b in buttons) GameToolbar.Children.Add(b);
    }

    private async Task RefreshGamePageAsync()
    {
        BuildGameTabs();
        StyleGameTabs();
        _gameCts?.Cancel();
        _gameCts = new CancellationTokenSource();
        var ct = _gameCts.Token;
        Control target = _gameTab switch { "shots" => ShotsGrid, "servers" => ServersPane, _ => WorldsPane };
        foreach (var pane in new Control[] { WorldsPane, ShotsGrid, ServersPane })
            if (pane != target && pane.IsVisible) { pane.IsVisible = false; pane.Opacity = 0; }
        try
        {
            switch (_gameTab)
            {
                case "shots": await LoadScreenshotsAsync(ct); break;
                case "servers": await LoadServersAsync(ct); break;
                default: await LoadWorldsAsync(ct); break;
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { LogCrash(ex); }
        if (ct.IsCancellationRequested) return;
        await FadeInAsync(target, 180);
    }

    private static async Task FadeInAsync(Control c, int ms)
    {
        if (c.IsVisible && c.Opacity >= 1) return;
        c.Opacity = 1;
        c.IsVisible = true;
        await RunOpacity(c, 0, 1, ms, default);
    }

    private void ShowGameStatus(string? text)
    {
        if (!string.IsNullOrEmpty(text)) GameStatus.Text = text;
        FadeVisible(GameStatus, !string.IsNullOrEmpty(text));
    }

    private Grid CardRow(Control lead, string title, string sub, out TextBlock subText, params Control[] trailing)
    {
        var t = new TextBlock { Text = title, FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis };
        t.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextMain"));
        subText = new TextBlock { Text = sub, FontSize = 11, TextTrimming = TextTrimming.CharacterEllipsis };
        subText.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextSub"));
        var mid = new StackPanel { VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center, Spacing = 2 };
        mid.Children.Add(t); mid.Children.Add(subText);
        var right = new StackPanel { Orientation = Avalonia.Layout.Orientation.Horizontal, Spacing = 8,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
        foreach (var c in trailing) right.Children.Add(c);
        var g = new Grid { Margin = new Thickness(16, 12), ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), ColumnSpacing = 12 };
        Grid.SetColumn(mid, 1); Grid.SetColumn(right, 2);
        g.Children.Add(lead); g.Children.Add(mid); g.Children.Add(right);
        return g;
    }

    private Rectangle RowDivider()
    {
        var r = new Rectangle { Height = 1, Margin = new Thickness(68, 0, 16, 0) };
        r.Bind(Shape.FillProperty, this.GetResourceObservable("Divider"));
        return r;
    }

    private static Border IconTile(Bitmap? bmp, string fallbackIcon, string color, bool pixelated = false)
    {
        Control child;
        if (bmp is not null)
        {
            var img = new Image { Source = bmp, Stretch = Stretch.UniformToFill };
            if (pixelated) RenderOptions.SetBitmapInterpolationMode(img, BitmapInterpolationMode.None);
            child = img;
        }
        else child = new Image { Source = new Bitmap(AssetLoader.Open(new Uri($"avares://S1mp1e/Assets/Icons/{fallbackIcon}.png"))),
                                 Width = 20, Height = 20 };
        return new Border { Width = 40, Height = 40, CornerRadius = new CornerRadius(9), ClipToBounds = true,
            Background = bmp is null ? new SolidColorBrush(Color.Parse(color)) : new SolidColorBrush(Color.Parse("#22000000")),
            Child = child };
    }

    private static Bitmap? TryBitmap(string? path)
    {
        try { return path is not null && File.Exists(path) ? new Bitmap(path) : null; } catch { return null; }
    }

    private static string RelDays(DateTime when)
    {
        var d = DateTime.Now - when;
        if (d.TotalDays >= 365) return $"{(int)(d.TotalDays / 365)} 年前";
        if (d.TotalDays >= 30) return $"{(int)(d.TotalDays / 30)} 個月前";
        if (d.TotalDays >= 1) return $"{(int)d.TotalDays} 天前";
        if (d.TotalHours >= 1) return $"{(int)d.TotalHours} 小時前";
        return d.TotalMinutes >= 1 ? $"{(int)d.TotalMinutes} 分鐘前" : "剛剛";
    }

    private static string Bytes(long n) => n >= 1L << 30 ? $"{n / (double)(1L << 30):0.0} GB"
        : n >= 1 << 20 ? $"{n / (double)(1 << 20):0.#} MB" : $"{Math.Max(1, n / 1024)} KB";

    // ---------------- worlds ----------------

    private async Task LoadWorldsAsync(CancellationToken ct)
    {
        var mcDir = EffectiveMcDir();
        SetGameToolbar(MiniButton("開啟資料夾", () => { Directory.CreateDirectory(WorldService.SavesDir(mcDir)); OpenWithShell(WorldService.SavesDir(mcDir)); }));
        var worlds = await Task.Run(() => WorldService.List(mcDir), ct);
        var backups = await Task.Run(() => WorldService.Backups(mcDir), ct);
        ct.ThrowIfCancellationRequested();

        WorldList.Children.Clear();
        ShowGameStatus(worlds.Count == 0 ? "還沒有世界 — 進遊戲建立一個後會出現在這裡（所有版本共用同一份存檔）" : null);
        WorldList.IsVisible = worlds.Count > 0;
        ((Border)WorldList.Parent!).IsVisible = worlds.Count > 0;
        var sizeJobs = new List<(WorldInfo w, TextBlock sub, string baseSub)>();
        var dupNames = worlds.GroupBy(w => w.Name).Where(g => g.Count() > 1).Select(g => g.Key).ToHashSet();
        foreach (var w in worlds)
        {
            if (WorldList.Children.Count > 0) WorldList.Children.Add(RowDivider());
            var baseSub = string.Join(" · ", new[] { dupNames.Contains(w.Name) ? $"資料夾「{w.Folder}」" : null, w.Mode, w.Version, RelDays(w.LastPlayed) }
                .Where(s => !string.IsNullOrEmpty(s)));
            var wl = w;
            Button? backupBtn = null, convertBtn = null;
            backupBtn = MiniButton("備份", () => _ = BackupWorldAsync(wl, backupBtn!));
            convertBtn = MiniButton("轉換", () => PickConvertTarget(wl, convertBtn!));
            var row = CardRow(IconTile(TryBitmap(w.IconPath), "globe", "#30B0C7", pixelated: true), w.Name, baseSub, out var sub,
                backupBtn, convertBtn, MiniButton("開啟", () => OpenWithShell(wl.Path)));
            WorldList.Children.Add(row);
            sizeJobs.Add((w, sub, baseSub));
        }
        RenderBackups(backups);
        _ = Task.Run(() =>
        {
            foreach (var (w, sub, baseSub) in sizeJobs)
            {
                if (ct.IsCancellationRequested) return;
                var size = WorldService.FolderSize(w.Path);
                Dispatcher.UIThread.Post(() => sub.Text = $"{baseSub} · {Bytes(size)}");
            }
        }, ct);
    }

    private void RenderBackups(List<WorldBackup> backups)
    {
        BackupList.Children.Clear();
        FadeVisible(BackupCaption, backups.Count > 0);
        FadeVisible(BackupCard, backups.Count > 0);
        foreach (var b in backups.Take(30))
        {
            if (BackupList.Children.Count > 0) BackupList.Children.Add(RowDivider());
            var bl = b;
            BackupList.Children.Add(CardRow(IconTile(null, "archivebox.fill", "#8E8E93"), b.World,
                $"{b.Created:yyyy/MM/dd HH:mm} · {Bytes(b.Size)}", out _,
                MiniButton("還原", () => _ = RestoreWorldAsync(bl)),
                MiniButton("顯示", () => RevealInExplorer(bl.Path))));
        }
    }

    private static void RevealInExplorer(string path)
    {
        try { System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{path}\""); } catch (Exception ex) { LogCrash(ex); }
    }

    private async Task BackupWorldAsync(WorldInfo w, Button btn)
    {
        if (!btn.IsEnabled) return;
        btn.IsEnabled = false;   // before the dialog: a second click must not queue a second backup
        // The world itself says whether a game has it open (also one started from another launcher).
        if ((WorldService.InUse(w.Path) || _game is { HasExited: false })
            && !await ConfirmAsync("世界正在遊玩中", "遊戲開著這個世界時備份，可能抓到還沒存檔的狀態。\n建議先回到標題畫面再備份。", "仍要備份", danger: false))
        { btn.IsEnabled = true; return; }
        var prog = new Progress<double>(p => btn.Content = $"{(int)(p * 100)}%");
        try
        {
            await WorldService.BackupAsync(EffectiveMcDir(), w, prog);
            ShowToast($"已備份「{w.Name}」");
            RenderBackups(await Task.Run(() => WorldService.Backups(EffectiveMcDir())));
        }
        catch (Exception ex) { LogCrash(ex); ShowToast("備份失敗：" + ex.Message); }
        finally { btn.Content = "備份"; btn.IsEnabled = true; }
    }

    private bool _restoring;

    private async Task RestoreWorldAsync(WorldBackup b)
    {
        if (_restoring) return;
        if (_game is { HasExited: false }) { ShowToast("請先關閉遊戲再還原世界"); return; }
        if (!await ConfirmAsync("還原世界",
                $"把「{b.World}」還原成 {b.Created:yyyy/MM/dd HH:mm} 的備份？\n現在的同名世界不會被刪除，會改名保留。", "還原", danger: false))
            return;
        _restoring = true;
        ShowToast("還原中…", 60000);
        try
        {
            var folder = await WorldService.RestoreAsync(EffectiveMcDir(), b);
            ShowToast($"已還原「{folder}」");
            await RefreshGamePageAsync();
        }
        catch (FileInUseException) { ShowToast("這個世界正在遊戲中開著，請先離開世界或關閉遊戲再還原", 4200); }
        catch (Exception ex) { LogCrash(ex); ShowToast("還原失敗：" + ex.Message + "（原本的世界沒有變動）", 4200); }
        finally { _restoring = false; }
    }

    // ---------------- screenshots ----------------

    private async Task LoadScreenshotsAsync(CancellationToken ct)
    {
        var mcDir = EffectiveMcDir();
        var shots = await Task.Run(() => WorldService.Screenshots(mcDir), ct);
        ct.ThrowIfCancellationRequested();
        var openDir = shots.FirstOrDefault()?.DirectoryName ?? Path.Combine(mcDir, "screenshots");
        SetGameToolbar(MiniButton("開啟資料夾", () => { Directory.CreateDirectory(openDir); OpenWithShell(openDir); }));
        ShotsGrid.Children.Clear();
        ShowGameStatus(shots.Count == 0 ? "還沒有截圖 — 遊戲中按 F2 拍照，所有版本的截圖都會集中在這裡"
                                        : $"{shots.Count} 張截圖" + (shots.Count > 120 ? "（顯示最新 120 張）" : ""));
        foreach (var f in shots.Take(120))
        {
            var img = new Image { Stretch = Stretch.UniformToFill, Opacity = 0,
                Transitions = new Transitions { new DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(220) } } };
            var tile = new Border { Width = 208, Height = 117, Margin = new Thickness(0, 0, 12, 12), CornerRadius = new CornerRadius(10),
                ClipToBounds = true, Background = new SolidColorBrush(Color.Parse("#22808080")), Cursor = new Cursor(StandardCursorType.Hand),
                Child = img };
            var ver = Path.GetFileName(Path.GetDirectoryName(Path.GetDirectoryName(f.FullName))) ?? "";
            ToolTip.SetTip(tile, $"{f.Name}\n{(ver == "instances" || ver.Length == 0 ? "" : ver + " · ")}{f.LastWriteTime:yyyy/MM/dd HH:mm}");
            var path = f.FullName;
            tile.PointerPressed += (_, e) =>
            {
                if (e.GetCurrentPoint(tile).Properties.IsRightButtonPressed) RevealInExplorer(path);
                else OpenWithShell(path);
            };
            ShotsGrid.Children.Add(tile);
        }
        // Decode small thumbnails off the UI thread, newest first; each fades in as it lands.
        var tiles = ShotsGrid.Children.OfType<Border>().ToList();
        var files = shots.Take(120).ToList();
        _ = Task.Run(async () =>
        {
            for (int i = 0; i < files.Count; i++)
            {
                if (ct.IsCancellationRequested) return;
                Bitmap? bmp = null;
                try { using var s = File.OpenRead(files[i].FullName); bmp = Bitmap.DecodeToWidth(s, 416, BitmapInterpolationMode.MediumQuality); }
                catch { }
                if (bmp is null) continue;
                var idx = i;
                await Dispatcher.UIThread.InvokeAsync(() =>
                {
                    if (tiles[idx].Child is Image im) { im.Source = bmp; im.Opacity = 1; }
                });
            }
        }, ct);
    }

    // ---------------- servers ----------------

    private async Task LoadServersAsync(CancellationToken ct)
    {
        var mcDir = EffectiveMcDir();
        SetGameToolbar(MiniButton("重新整理", () => _ = RefreshGamePageAsync()));
        var servers = await Task.Run(() => ServerListService.LoadAll(mcDir), ct);
        ct.ThrowIfCancellationRequested();
        ServerList.Children.Clear();
        ((Border)ServerList.Parent!).IsVisible = servers.Count > 0;
        ShowGameStatus(servers.Count == 0 ? "還沒有儲存的伺服器 — 在遊戲的「多人遊戲」畫面加入伺服器後會出現在這裡" : null);
        var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? "26.2" : VersionBox.SelectedText;
        foreach (var s in servers)
        {
            if (ServerList.Children.Count > 0) ServerList.Children.Add(RowDivider());
            var sl = s;
            var status = new TextBlock { Text = "連線中…", FontSize = 11, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                Transitions = new Transitions { new DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(180) } } };
            status.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextSub"));
            var join = MiniButton("加入", () => JoinServer(sl, mc));
            ToolTip.SetTip(join, $"用 {mc} 開啟遊戲並直接連進 {sl.Address}");
            var lead = IconTile(DecodeBase64Png(s.IconBase64), "antenna.radiowaves.left.and.right", "#5E5CE6", pixelated: true);
            var row = CardRow(lead, s.Name, s.Address, out var sub, status, join);
            ServerList.Children.Add(row);
            _ = PingIntoRowAsync(s, status, sub, lead, ct);
        }
    }

    // Server icons are 64×64 by spec, but the favicon comes from the server: cap the input and decode at icon size, so
    // a huge image never stalls the page or eats memory.
    private static Bitmap? DecodeBase64Png(string? b64)
    {
        if (string.IsNullOrEmpty(b64) || b64.Length > 512 * 1024) return null;
        try { using var ms = new MemoryStream(Convert.FromBase64String(b64)); return Bitmap.DecodeToWidth(ms, 64, BitmapInterpolationMode.None); }
        catch { return null; }
    }

    private async Task PingIntoRowAsync(SavedServer s, TextBlock status, TextBlock sub, Border lead, CancellationToken ct)
    {
        var st = await ServerListService.PingAsync(s.Address, ct);
        if (ct.IsCancellationRequested) return;
        status.Opacity = 0;
        await Task.Delay(120);
        if (!st.Online)
        {
            status.Text = "離線";
            status.Foreground = new SolidColorBrush(Color.Parse("#FF453A"));
        }
        else
        {
            status.Text = $"{st.Players}/{st.Max} 人 · {st.LatencyMs} ms";
            if (!string.IsNullOrWhiteSpace(st.Motd)) sub.Text = st.Motd.Replace('\n', ' ');
            if (s.IconBase64 is null && DecodeBase64Png(st.FaviconBase64) is { } fav && lead.Child is Image)
            {
                lead.Background = new SolidColorBrush(Color.Parse("#22000000"));
                var img = new Image { Source = fav, Stretch = Stretch.UniformToFill };
                RenderOptions.SetBitmapInterpolationMode(img, BitmapInterpolationMode.None);
                lead.Child = img;
            }
            ToolTip.SetTip(status, st.Version is null ? null : "伺服器版本：" + st.Version);
        }
        status.Opacity = 1;
    }

    private void JoinServer(SavedServer s, string mc)
    {
        if (_game is { HasExited: false }) { ShowToast("遊戲已經在執行"); return; }
        _joinAddress = s.Address;
        NavigateTo(0);
        ShowToast($"用 {mc} 加入「{s.Name}」");
        OnPlay(PlayButton, new Avalonia.Interactivity.RoutedEventArgs());
    }
}
