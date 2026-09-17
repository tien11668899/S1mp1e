using System;
using System.Linq;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Input.Platform;
using Avalonia.Layout;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Media.Transformation;
using Avalonia.Platform;
using Avalonia.Styling;
using Avalonia.VisualTree;
using S1mp1e.Controls;
using S1mp1e.Models;
using S1mp1e.Services;
using Avalonia.Platform.Storage;
using Avalonia.Data;
using Avalonia.Controls.Templates;
using Avalonia.Threading;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace S1mp1e.Views;

public partial class MainWindow : Window
{
    private const double RowStride = 44.0;   // 40 height + 4 spacing
    private int _selected = 0;

    // Persisted launcher preferences shared with the Rust core via
    // %APPDATA%\S1mp1e\config.json. Loaded on Opened, saved on every change.
    private LauncherConfig _cfg = new();
    private bool _hydrating;   // suppress save-back while we're applying loaded values

    // Accent label → CSS hex (must match the values macOS System Settings uses).
    private static readonly (string label, string hex)[] Accents =
    {
        ("藍色",   "#0a84ff"),
        ("紫色",   "#af52de"),
        ("粉紅色", "#ff2d55"),
        ("橙色",   "#ff9500"),
        ("綠色",   "#30d158"),
        ("石墨",   "#8e8e93"),
    };

    // Physical RAM in whole GB — used as the ceiling of the RAM-allocation slider
    // so the user can allocate anything from 1 GB up to their machine's max.
    // Windows: GlobalMemoryStatusEx via Marshal-ing MEMORYSTATUSEX. Falls back to
    // GC.GetGCMemoryInfo().TotalAvailableMemoryBytes if that ever fails.
    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential, CharSet = System.Runtime.InteropServices.CharSet.Auto)]
    private struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }
    [System.Runtime.InteropServices.DllImport("kernel32.dll", CharSet = System.Runtime.InteropServices.CharSet.Auto, SetLastError = true)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);
    private static int GetTotalRamGb()
    {
        try
        {
            var m = new MEMORYSTATUSEX { dwLength = (uint)System.Runtime.InteropServices.Marshal.SizeOf<MEMORYSTATUSEX>() };
            if (GlobalMemoryStatusEx(ref m))
                return (int)Math.Max(1, m.ullTotalPhys / (1024UL * 1024 * 1024));
        }
        catch { }
        try
        {
            var bytes = GC.GetGCMemoryInfo().TotalAvailableMemoryBytes;
            return (int)Math.Max(1, bytes / (1024L * 1024 * 1024));
        }
        catch { return 16; }
    }

    private static string ResolveItestExe()
    {
        // Prefer the exe bundled next to the launcher (release layout);
        // fall back to the debug build path when running from the IDE.
        var here = System.IO.Path.GetDirectoryName(
            System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName ?? "") ?? "";
        var side = System.IO.Path.Combine(here, "itest.exe");
        if (System.IO.File.Exists(side)) return side;
        // Dev fallback: the release CLI built from this repo (the old
        // source/repos path was removed).
        return @"C:\Users\Administrator\source\S1mp1e\src-tauri\target\release\itest.exe";
    }

    private void SaveCfg()
    {
        if (_hydrating) return;
        // Settings only — never write our in-memory account over what the itest CLI
        // put on disk (see ConfigStore.SaveUiOwned).
        ConfigStore.SaveUiOwned(_cfg);
    }

    public MainWindow()
    {
        InitializeComponent();

        // App / taskbar icon: the glass "S".
        Icon = new WindowIcon(new Bitmap(AssetLoader.Open(new Uri("avares://S1mp1e/Assets/s1mp1e.png"))));

        // Smooth morph/slide for the liquid-glass selection pill.
        SelPill.Transitions = new Transitions
        {
            new DoubleTransition
            {
                Property = Canvas.TopProperty,
                Duration = TimeSpan.FromMilliseconds(340),
                Easing = new CubicEaseOut()
            }
        };

        // Sidebar hover highlight: quick slide between rows + a fade on enter/leave.
        HoverPill.Transitions = new Transitions
        {
            new DoubleTransition { Property = Canvas.TopProperty, Duration = TimeSpan.FromMilliseconds(200), Easing = new CubicEaseOut() },
            new DoubleTransition { Property = OpacityProperty,    Duration = TimeSpan.FromMilliseconds(140), Easing = new CubicEaseOut() },
        };

        Opened += (_, _) =>
        {
            // Hydrate settings from disk before any handler can fire back.
            _cfg = ConfigStore.Load();
            ApplyLoadedConfig();

            // Intercept mouse wheel BEFORE ScrollViewer's default snap-scroll.
            // AddHandler with Tunnel + handledEventsToo runs at the root of the
            // route (before the ScrollViewer's own Bubble handler), so we can
            // cancel the default and drive smooth exp-decay scroll ourselves.
            DetailScroller?.AddHandler(InputElement.PointerWheelChangedEvent,
                OnDetailScrollWheel,
                RoutingStrategies.Tunnel | RoutingStrategies.Bubble,
                handledEventsToo: true);
            if (DetailScroller is not null)
            {
                DetailScroller.ScrollChanged += (_, _) => UpdateEdgeScrims();
                UpdateEdgeScrims();
            }

            // Edge-blur snapshot refresh is driven ONLY by the scroll tween tick
            // (see OnScrollTick). Hooking PropertyChanged/LayoutUpdated causes
            // a snapshot-triggers-layout-triggers-snapshot loop that stops the
            // ScrollViewer from ever computing its true extent — bottom rows
            // become unreachable. Static edge blur when idle is dropped;
            // effect only shows during active motion.

            // (no transitions on ModSourcePill — the segmented switch animates it
            // via a keyframe morph so it can grow into a glass blob mid-flight.)

            // Dev hook: S1MP1E_PAGE=0..3 opens straight to a page (for screenshots). No-op otherwise.
            if (int.TryParse(Environment.GetEnvironmentVariable("S1MP1E_PAGE"), out var p) && p >= 0 && p <= 3)
                _selected = p;

            MovePill(_selected, animate: false);
            UpdateNavWeights(_selected);
            ShowPage(_selected);

            WireSidebarHover();

            // Show the real running version on the 關於 card (was hardcoded "1.0.0").
            AboutVersionText.Text = UpdateChecker.CurrentVersion();

            // Best-effort launcher update check — fire-and-forget so a slow or dead
            // network never delays the window. Shows the bottom banner if newer.
            CheckForUpdatesAsync();

            // Every pop-up button opens the ONE shared liquid-glass menu, anchored to itself.
            foreach (var gs in this.GetVisualDescendants().OfType<GlassSelect>())
                gs.OpenRequested += (_, src) => ShowGlassMenu(src);

            // Dormant dev hook: set S1MP1E_DEMO=1 to auto-flip a toggle on a loop so the
            // liquid morph can be screen-recorded without synthetic clicks. No-op otherwise.
            if (Environment.GetEnvironmentVariable("S1MP1E_DEMO") == "1")
            {
                var t = new Avalonia.Threading.DispatcherTimer
                { Interval = TimeSpan.FromMilliseconds(1300) };
                t.Tick += (_, _) => DemoToggle.IsChecked = !(DemoToggle.IsChecked == true);
                t.Start();
            }
            if (Environment.GetEnvironmentVariable("S1MP1E_DEMO_LIGHT") == "1" && Application.Current is { } app)
                app.RequestedThemeVariant = ThemeVariant.Light;
            if (Environment.GetEnvironmentVariable("S1MP1E_DEMO_DD") == "1")
            {
                var t = new Avalonia.Threading.DispatcherTimer
                { Interval = TimeSpan.FromMilliseconds(2600) };
                t.Tick += (_, _) =>
                {
                    if (OverlayHost.IsVisible) CloseGlassMenu();
                    else ShowGlassMenu(VersionBox);
                };
                t.Start();
            }
        };
    }

    private static readonly string[] PageTitles = { "開始遊戲", "模組", "帳號", "設定" };

    private int _currentPage = -1;
    private StackPanel PageOf(int i) => i switch
    {
        0 => PageStart, 1 => PageMods, 2 => PageAccount, 3 => PageSettings, _ => PageStart,
    };

    // Swap the detail pane to the selected page + retitle; Play shows only on the
    // launch page. Cross-fade the OLD page + big title out, retitle, then fade the
    // NEW page + title in — soft handoff for both the content and the header.
    private async void ShowPage(int index)
    {
        PlayButton.IsVisible = index == 0;
        // First entry into Mods page → kick off a default search (empty query = trending).
        if (index == 1 && _mods.Count == 0) _ = RunSearchAsync();
        if (index == 1) UpdateAllButtonState();

        // First-time or same page: snap.
        if (_currentPage < 0 || _currentPage == index)
        {
            PageTitle.Text = PageTitles[index];
            PageTitle.Opacity = 1;
            for (int i = 0; i < 4; i++) { var p = PageOf(i); p.IsVisible = i == index; p.Opacity = 1; }
            _currentPage = index;
            return;
        }

        var oldPage = PageOf(_currentPage);
        var newPage = PageOf(index);
        _currentPage = index;

        try
        {
            var dur = TimeSpan.FromMilliseconds(180);
            var easeOut = new CubicEaseOut();
            var easeIn  = new CubicEaseIn();

            var fadeOut = new Animation
            {
                Duration = dur, Easing = easeIn, FillMode = FillMode.Forward,
                Children = {
                    new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(OpacityProperty, 1d) } },
                    new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(OpacityProperty, 0d) } },
                }
            };
            // Fade OLD page + title out IN PARALLEL
            _ = fadeOut.RunAsync(PageTitle);
            await fadeOut.RunAsync(oldPage);
            oldPage.IsVisible = false;

            // Swap the title text while it's invisible
            PageTitle.Text = PageTitles[index];

            newPage.Opacity = 0;
            newPage.IsVisible = true;
            var fadeIn = new Animation
            {
                Duration = dur, Easing = easeOut, FillMode = FillMode.Forward,
                Children = {
                    new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(OpacityProperty, 0d) } },
                    new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(OpacityProperty, 1d) } },
                }
            };
            // Fade NEW page + title in IN PARALLEL
            _ = fadeIn.RunAsync(PageTitle);
            await fadeIn.RunAsync(newPage);
            newPage.Opacity = 1;
            PageTitle.Opacity = 1;
        }
        catch (Exception ex)
        {
            LogCrash(ex);
            PageTitle.Text = PageTitles[index];
            PageTitle.Opacity = 1;
            for (int i = 0; i < 4; i++) { var p = PageOf(i); p.IsVisible = i == index; p.Opacity = 1; }
        }
    }

    // ---- window chrome ----
    private void OnClose(object? sender, TappedEventArgs e) => Close();

    private void OnMinimize(object? sender, TappedEventArgs e) => WindowState = WindowState.Minimized;

    private void OnMaximize(object? sender, TappedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void OnTitleBarPressed(object? sender, PointerPressedEventArgs e)
    {
        // Don't start a window drag when interacting with the traffic-light cluster.
        if (Traffic.IsPointerOver)
            return;
        if (e.GetCurrentPoint(this).Properties.IsLeftButtonPressed)
            BeginMoveDrag(e);
    }

    // ---- sidebar nav ----
    private void OnNavRowPressed(object? sender, PointerPressedEventArgs e)
    {
        if (sender is Border b && b.Tag is string tag && int.TryParse(tag, out var idx))
        {
            _selected = idx;
            MovePill(idx, animate: true);
            UpdateNavWeights(idx);
            ShowPage(idx);
        }
    }

    // ---- PLAY: spawn the Rust launcher CLI (itest play <mc> <loader>) ----
    private System.Diagnostics.Process? _game;
    private volatile string? _lastLaunchError;   // last meaningful stderr line
    private volatile bool _authExpired;          // itest emitted AUTH_EXPIRED (session dead)
    private volatile bool _reachedRunning;       // game actually started rendering

    private void OnPlay(object? sender, RoutedEventArgs e)
    {
        if (_game is { HasExited: false })
            return;

        _lastLaunchError = null;
        _authExpired = false;
        _reachedRunning = false;
        ToolTip.SetTip(PlayButton, null);

        var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? "26.2" : VersionBox.SelectedText;
        var loader = EffectiveLoader();

        var exe = ResolveItestExe();
        if (!System.IO.File.Exists(exe))
        {
            PlayLabel.Text = "找不到啟動器";
            return;
        }

        PlayButton.IsEnabled = false;
        PlayLabel.Text = "啟動中…";

        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo(exe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            psi.ArgumentList.Add("play");
            psi.ArgumentList.Add(mc);
            psi.ArgumentList.Add(loader);
            // pass mc dir + offline name from the persisted settings
            psi.ArgumentList.Add(string.IsNullOrEmpty(_cfg.Settings.McPath)
                ? System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft")
                : _cfg.Settings.McPath);
            psi.ArgumentList.Add(string.IsNullOrEmpty(_cfg.Settings.OfflineName) ? "Player" : _cfg.Settings.OfflineName);

            var proc = new System.Diagnostics.Process { StartInfo = psi, EnableRaisingEvents = true };
            proc.OutputDataReceived += (_, ev) =>
            {
                if (ev.Data is null) return;
                // itest prints AUTH_EXPIRED\t<name> when the saved MSA session is dead:
                // the game will launch OFFLINE, so warn instead of silently failing MP.
                if (ev.Data.StartsWith("AUTH_EXPIRED", StringComparison.Ordinal))
                {
                    _authExpired = true;
                    return;
                }
                if (ev.Data.Contains("LWJGL") || ev.Data.Contains("Setting user") || ev.Data.Contains("LAUNCHING"))
                {
                    _reachedRunning = true;
                    Avalonia.Threading.Dispatcher.UIThread.Post(() =>
                    {
                        PlayLabel.Text = "遊戲執行中";
                        // Apply user's post-launch preference
                        switch (_cfg.Settings.AfterLaunch)
                        {
                            case "hide":  WindowState = WindowState.Minimized; break;
                            case "close": Close(); break;
                            case "keep":
                            default: break;
                        }
                    });
                }
            };
            proc.ErrorDataReceived += (_, ev) =>
            {
                // Keep the last meaningful stderr line so a failed launch can show WHY
                // instead of silently snapping back to "Play". Prefer error-looking lines.
                if (string.IsNullOrWhiteSpace(ev.Data)) return;
                var line = ev.Data.Trim();
                if (line.StartsWith("[", StringComparison.Ordinal)) return; // skip MC log spam
                _lastLaunchError = line;
            };
            proc.Exited += (_, _) =>
            {
                int code = -1;
                try { code = proc.ExitCode; } catch { }
                bool expired = _authExpired;
                bool ranOk = _reachedRunning && code == 0;
                string? err = _lastLaunchError;
                Avalonia.Threading.Dispatcher.UIThread.Post(() =>
                {
                    PlayButton.IsEnabled = true;
                    _game = null;
                    if (expired)
                    {
                        // Session was dead → launched offline. Nudge the user to re-login.
                        PlayLabel.Text = "登入已過期，請重新登入";
                        ToolTip.SetTip(PlayButton, "你的 Microsoft 登入已過期，剛才以離線身分啟動，無法進多人伺服器。請到「帳號」重新登入。");
                        if (NavAccountSub is not null) NavAccountSub.Text = "登入已過期";
                    }
                    else if (ranOk || code == 0)
                    {
                        PlayLabel.Text = "Play";
                    }
                    else
                    {
                        // Non-zero exit that isn't a normal quit: a real launch/crash error.
                        PlayLabel.Text = "啟動失敗";
                        ToolTip.SetTip(PlayButton, string.IsNullOrEmpty(err)
                            ? $"啟動器結束碼 {code}（無錯誤輸出）"
                            : $"錯誤：{err}");
                    }
                });
            };
            proc.Start();
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            _game = proc;
        }
        catch
        {
            PlayButton.IsEnabled = true;
            PlayLabel.Text = "啟動失敗";
        }
    }

    // ---- shared LIQUID-GLASS pop-up menu (real refraction: lives in the window tree) ----
    private S1mp1e.Controls.GlassSelect? _openSel;

    private void ShowGlassMenu(S1mp1e.Controls.GlassSelect src)
    {
        try { ShowGlassMenuCore(src); }
        catch (Exception ex) { LogCrash(ex); OverlayHost.IsVisible = false; }
    }

    // The containing card = closest ancestor Border that has our card look
    // (CornerRadius 12 or Classes="card"). Falls back to null → we keep the button anchor.
    private static Border? FindAncestorCard(Control from)
    {
        for (var v = from.Parent; v is not null; v = v.Parent)
            if (v is Border b && (b.Classes.Contains("card") || b.CornerRadius.TopLeft >= 11))
                return b;
        return null;
    }

    private static void LogCrash(Exception ex)
    {
        try
        {
            System.IO.File.AppendAllText(
                System.IO.Path.Combine(System.IO.Path.GetTempPath(), "s1mp1e_crash.log"),
                DateTime.Now + " [menu]\n" + ex + "\n\n");
        }
        catch { }
    }

    private void ShowGlassMenuCore(S1mp1e.Controls.GlassSelect src)
    {
        _openSel = src;
        // Compute per-item disable mask: Forge on a Fabric-only MC (>=1.13 or 26.2)
        // is not supported by this launcher, so the row shows greyed and no-op.
        bool[]? dis = null;
        if (ReferenceEquals(src, LoaderBox))
        {
            var v = VersionBox?.SelectedText ?? "";
            // Grey every loader the version can't use (Forge on ≥1.13/26.2;
            // Fabric on 1.8.9/1.12.2).
            dis = new bool[src.Items.Length];
            bool any = false;
            for (int i = 0; i < src.Items.Length; i++)
            {
                dis[i] = !LoaderAllowed(v, src.Items[i]);
                any |= dis[i];
            }
            if (!any) dis = null;
        }
        ShowMenuFor(src, src.Items, src.SelectedIndex, dis, pick =>
        {
            try { src.SelectedIndex = pick; } catch (Exception ex) { LogCrash(ex); }
        }, padRight: src.Padding.Right);
    }

    /// General "open the shared liquid-glass menu under any control" — used by the
    /// GlassSelect popover AND by the sidebar chip's + button (adds/logout menu),
    /// account switcher, etc. Positions the menu right-aligned to the trigger's
    /// text edge (padRight lets the caller compensate for the trigger's own padding).
    /// Long lists (>= 8 items) auto-lay-out into a 3-column grid so a big version
    /// picker doesn't turn into a scroll-wall.
    private void ShowMenuFor(Control anchor, string[] items, int selected, Action<int> onPick, double padRight = 0)
        => ShowMenuFor(anchor, items, selected, disabled: null, onPick, padRight);
    private void ShowMenuFor(Control anchor, string[] items, int selected, bool[]? disabled, Action<int> onPick, double padRight = 0)
    {
        bool IsDisabled(int i) => disabled is not null && i < disabled.Length && disabled[i];
        MenuItems.Children.Clear();
        int cols = items.Length >= 8 ? 3 : 1;
        if (cols > 1)
        {
            // Multi-column layout — UniformGrid handles equal-width cells better than
            // WrapPanel here (WrapPanel with a hard Width refuses to stretch children).
            int rowsCount = (items.Length + cols - 1) / cols;
            const double cellW = 106;
            var grid = new Avalonia.Controls.Primitives.UniformGrid
            {
                Columns = cols, Rows = rowsCount,
                Width = cellW * cols,
            };
            for (int i = 0; i < items.Length; i++)
            {
                int idx = i;
                bool dis = IsDisabled(i);
                var row = BuildMenuRow(items[i], i == selected, dis);
                row.Tapped += (_, _) =>
                {
                    if (dis) return;   // disabled → swallow click, leave menu open
                    try { onPick(idx); } catch (Exception ex) { LogCrash(ex); }
                    CloseGlassMenu();
                };
                grid.Children.Add(row);
            }
            MenuItems.Children.Add(grid);
        }
        else
        {
            for (int i = 0; i < items.Length; i++)
            {
                int idx = i;
                bool dis = IsDisabled(i);
                var row = BuildMenuRow(items[i], i == selected, dis);
                row.Tapped += (_, _) =>
                {
                    if (dis) return;
                    try { onPick(idx); } catch (Exception ex) { LogCrash(ex); }
                    CloseGlassMenu();
                };
                MenuItems.Children.Add(row);
            }
        }

        OverlayHost.IsVisible = true;
        // Ensure any previously-opened sheet/gallery is out of the way and the
        // MenuRoot panel is the visible one — ShowLocalModDetailAsync flips
        // MenuRoot to invisible, and if we don't flip it back the next menu opens
        // into an invisible panel.
        MenuRoot.IsVisible = true;
        if (SheetRoot is not null) SheetRoot.IsVisible = false;
        if (SkinGalleryRoot is not null) SkinGalleryRoot.IsVisible = false;
        // Plain dropdown menus don't get the frosted sheet backdrop — they should
        // feel lightweight, not modal.
        MenuItems.Measure(Size.Infinity);
        var ds = MenuItems.DesiredSize;
        double w = Math.Max(ds.Width, anchor.Bounds.Width);
        double h = ds.Height;
        MenuRoot.Width = w;
        MenuRoot.Height = h;

        var pr = anchor.TranslatePoint(new Point(anchor.Bounds.Width, anchor.Bounds.Height), OverlayHost)
                 ?? new Point(0, 0);
        var pt = anchor.TranslatePoint(new Point(0, 0), OverlayHost) ?? new Point(0, 0);
        double anchorRight = pr.X - padRight;
        _menuLeft = Math.Max(8, anchorRight - w);
        // If the menu wouldn't fit below the anchor, flip it ABOVE it (macOS pop-up
        // behaviour when the trigger is near the window bottom, like the sidebar
        // account chip). Origin also flips to the bottom-right so the droplet
        // grows upward.
        double availBelow = OverlayHost.Bounds.Height - pr.Y - 8;
        bool flipUp = availBelow < h + 4;
        _menuTop = flipUp ? Math.Max(8, pt.Y - h - 4) : pr.Y + 4;
        _menuW = w;
        _menuH = h;
        _btnCX = pr.X - anchor.Bounds.Width / 2;
        _btnW = anchor.Bounds.Width;
        _menuFlipUp = flipUp;
        Canvas.SetLeft(MenuRoot, _menuLeft);
        Canvas.SetTop(MenuRoot, _menuTop);

        AnimateMenu(open: true);
    }

    private async void CloseGlassMenu()
    {
        _openSel = null;
        try
        {
            if (SheetRoot.IsVisible)
            {
                await CollapseSheetToOriginAsync(SheetRoot, _sheetOrigin, _sheetTargetL, _sheetTargetT, _sheetTargetW, _sheetTargetH);
            }
            else if (SkinGalleryRoot.IsVisible)
            {
                // Skin gallery has no origin — plain fade-out is fine.
                var fade = new Animation
                {
                    Duration = TimeSpan.FromMilliseconds(160), Easing = new CubicEaseIn(),
                    FillMode = FillMode.Forward,
                    Children = {
                        new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(OpacityProperty, 1d) } },
                        new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(OpacityProperty, 0d) } },
                    }
                };
                await fade.RunAsync(SkinGalleryRoot);
                SkinGalleryRoot.IsVisible = false;
            }
            else await AnimateMenu(open: false);
        }
        catch (Exception ex) { LogCrash(ex); }
        OverlayHost.IsVisible = false;
    }

    private void OnMenuDismiss(object? sender, PointerPressedEventArgs e) => CloseGlassMenu();

    private double _menuLeft, _menuTop, _menuW, _menuH, _btnCX, _btnW;
    private bool _menuFlipUp;   // popover above anchor instead of below

    // Apple droplet morph: a small glass blob appears DIRECTLY UNDER the button's
    // text (centred on it, about as wide as the text) and GROWS down/left into the
    // panel with a slight overshoot; the item labels fade in only once the panel is
    // nearly full-size. Closing shrinks it back under the text the same way. Only
    // double-typed properties are animated (Width/Height/Canvas.Left/Opacity) —
    // RenderTransform has NO keyframe animator in Avalonia 12 and throws.
    private System.Threading.Tasks.Task AnimateMenu(bool open)
    {
        double smallW = Math.Min(_btnW, _menuW * 0.7);
        double smallH = _menuH * 0.22;
        double smallL = Math.Min(Math.Max(8, _btnCX - smallW / 2), _menuLeft + _menuW - smallW);
        double overW = _menuW * 1.03, overH = _menuH * 1.02;
        double right = _menuLeft + _menuW;

        // When popping UP (flipUp), the panel's BOTTOM edge is fixed under the anchor,
        // so its Top must slide upward as Height grows. Compute keyframe Tops that keep
        // the bottom edge at _menuTop+_menuH regardless of the current Height.
        double baseTop = _menuTop;
        double bottomFixed = _menuTop + _menuH;
        double smallTop = _menuFlipUp ? bottomFixed - smallH : baseTop;
        double overTop  = _menuFlipUp ? bottomFixed - overH  : baseTop;

        var panel = new Animation
        {
            Duration = TimeSpan.FromMilliseconds(open ? 340 : 240),
            Easing = open ? new CubicEaseOut() : new CubicEaseIn(),
            FillMode = FillMode.Forward,
        };
        if (open)
        {
            panel.Children.Add(new KeyFrame { Cue = new Cue(0d), Setters = {
                new Setter(Canvas.LeftProperty, smallL),
                new Setter(Canvas.TopProperty,  smallTop),
                new Setter(WidthProperty, smallW), new Setter(HeightProperty, smallH),
                new Setter(OpacityProperty, 0d) } });
            panel.Children.Add(new KeyFrame { Cue = new Cue(0.22d), Setters = {
                new Setter(OpacityProperty, 0.55d) } });
            panel.Children.Add(new KeyFrame { Cue = new Cue(0.5d), Setters = {
                new Setter(OpacityProperty, 1d) } });
            panel.Children.Add(new KeyFrame { Cue = new Cue(0.66d), Setters = {
                new Setter(Canvas.LeftProperty, right - overW),
                new Setter(Canvas.TopProperty,  overTop),
                new Setter(WidthProperty, overW), new Setter(HeightProperty, overH) } });
            panel.Children.Add(new KeyFrame { Cue = new Cue(1d), Setters = {
                new Setter(Canvas.LeftProperty, _menuLeft),
                new Setter(Canvas.TopProperty,  baseTop),
                new Setter(WidthProperty, _menuW), new Setter(HeightProperty, _menuH),
                new Setter(OpacityProperty, 1d) } });
        }
        else
        {
            panel.Children.Add(new KeyFrame { Cue = new Cue(0d), Setters = {
                new Setter(Canvas.LeftProperty, _menuLeft),
                new Setter(Canvas.TopProperty,  baseTop),
                new Setter(WidthProperty, _menuW), new Setter(HeightProperty, _menuH),
                new Setter(OpacityProperty, 1d) } });
            panel.Children.Add(new KeyFrame { Cue = new Cue(0.35d), Setters = {
                new Setter(OpacityProperty, 0.75d) } });
            panel.Children.Add(new KeyFrame { Cue = new Cue(1d), Setters = {
                new Setter(Canvas.LeftProperty, smallL),
                new Setter(Canvas.TopProperty,  smallTop),
                new Setter(WidthProperty, smallW), new Setter(HeightProperty, smallH),
                new Setter(OpacityProperty, 0d) } });
        }

        var items = new Animation
        {
            Duration = panel.Duration,
            Easing = open ? new CubicEaseOut() : new CubicEaseIn(),
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters =
                    { new Setter(OpacityProperty, open ? 0d : 1d) } },
                new KeyFrame { Cue = new Cue(open ? 0.45d : 0.5d), Setters =
                    { new Setter(OpacityProperty, open ? 0.08d : 0d) } },
                new KeyFrame { Cue = new Cue(1d), Setters =
                    { new Setter(OpacityProperty, open ? 1d : 0d) } },
            }
        };

        _ = items.RunAsync(MenuItems);
        return panel.RunAsync(MenuRoot);
    }

    // one menu row: [✓ | label], rounded hover pill (Border.menurow)
    private Border BuildMenuRow(string text, bool selected) => BuildMenuRow(text, selected, disabled: false);
    private Border BuildMenuRow(string text, bool selected, bool disabled)
    {
        // Convention: an item whose label starts with "＋ " or "+ " is an "add" action —
        // the ＋ glyph moves from the left of the text to the right (col-1) slot so
        // it visually matches the check-column of the other rows.
        var isPlus = text.StartsWith("＋ ") || text.StartsWith("+ ");
        var displayText = isPlus ? text.Substring(2).TrimStart() : text;

        Control right;
        if (isPlus)
        {
            var plus = new TextBlock
            {
                Text = "＋", FontSize = 15,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            };
            plus.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextMain"));
            right = plus;
        }
        else
        {
            var check = new Avalonia.Controls.Shapes.Path
            {
                Width = 12, Height = 10, Stretch = Stretch.Uniform,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Right,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                StrokeThickness = 1.8, StrokeLineCap = PenLineCap.Round, StrokeJoin = PenLineJoin.Round,
                Data = Geometry.Parse("M0,3.2 L3.4,6.6 L9,0"),
                Opacity = selected ? 1 : 0,
            };
            check.Bind(Avalonia.Controls.Shapes.Path.StrokeProperty, this.GetResourceObservable("TextMain"));
            right = check;
        }
        Grid.SetColumn(right, 1);

        var label = new TextBlock { Text = displayText, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center, FontSize = 13 };
        // Disabled rows render in the secondary/muted colour and don't get the hover
        // cursor so they read as "greyed out and non-interactive" at a glance.
        label.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable(disabled ? "TextSub" : "TextMain"));
        Grid.SetColumn(label, 0);

        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,20") };
        grid.Children.Add(right);
        grid.Children.Add(label);

        var row = new Border
        {
            CornerRadius = new CornerRadius(10),
            Margin = new Thickness(4, 0),
            Padding = new Thickness(8, 4),
            Background = Brushes.Transparent,
            Cursor = new Avalonia.Input.Cursor(disabled ? Avalonia.Input.StandardCursorType.Arrow : Avalonia.Input.StandardCursorType.Hand),
            Child = grid,
            Opacity = disabled ? 0.55 : 1,
        };
        row.Classes.Add(disabled ? "menurow-disabled" : "menurow");
        return row;
    }

    // ---- settings: live theme switch (自動 / 淺色 / 深色) ----

    // ---- hydrate every persisted control from _cfg.Settings ----
    private void ApplyLoadedConfig()
    {
        _hydrating = true;
        try
        {
            var s = _cfg.Settings;

            // start page — remember last version + loader (Fabric-forced if MC is 1.13+)
            SetGlassSelect(VersionBox, s.Version);
            var loaderLabel = s.Loader.ToLowerInvariant() switch
            {
                "forge" => "Forge",
                _ => "Fabric",
            };
            if (!LoaderAllowed(s.Version, loaderLabel)) loaderLabel = DefaultLoader(s.Version);
            SetGlassSelect(LoaderBox, loaderLabel);
            UpdateStartNavSub();

            // account page
            if (OfflineNameBox is not null) OfflineNameBox.Text = s.OfflineName;

            // settings page — general
            if (RamSlider is not null)
            {
                // Ceiling = total physical RAM in GB (dynamic). Falls back to 16 GB
                // if we can't read it. Clamp saved value into [1, ceiling].
                var maxGb = GetTotalRamGb();
                RamSlider.Maximum = maxGb;
                RamSlider.TickFrequency = maxGb <= 32 ? 1 : 2;
                RamSlider.Value = Math.Clamp(s.RamMb / 1024.0, 1, maxGb);
            }
            if (McPathLabel is not null)
                McPathLabel.Text = string.IsNullOrEmpty(s.McPath) ? @"%APPDATA%\.minecraft" : s.McPath;
            SetGlassSelect(AfterLaunchBox, s.AfterLaunch switch { "keep" => "保持開啟", "close" => "關閉啟動器", _ => "隱藏啟動器" });

            // settings page — advanced (JVM args, resolution, custom Java)
            if (JvmArgsBox is not null) JvmArgsBox.Text = s.JvmArgs;
            if (ResWidthBox is not null)  ResWidthBox.Text  = s.ResWidth  > 0 ? s.ResWidth.ToString()  : "";
            if (ResHeightBox is not null) ResHeightBox.Text = s.ResHeight > 0 ? s.ResHeight.ToString() : "";
            if (JavaPathLabel is not null) JavaPathLabel.Text = string.IsNullOrEmpty(s.JavaPath) ? "自動（依版本選擇）" : s.JavaPath;

            // settings page — appearance (accent + toggles + theme)
            var accIdx = Array.FindIndex(Accents, a => string.Equals(a.hex, s.Accent, StringComparison.OrdinalIgnoreCase));
            if (accIdx >= 0 && AccentBox is not null) AccentBox.SelectedIndex = accIdx;
            ApplyAccent(s.Accent);
            if (GlassToggle is not null) GlassToggle.IsChecked = s.Glass;
            if (DemoToggle is not null) DemoToggle.IsChecked = s.ReduceTransparency;
            // Theme: restore the saved 自動/淺色/深色 choice (was previously never
            // persisted, so it reset to 自動 on every launch).
            if (ThemeBox is not null)
                ThemeBox.SelectedIndex = s.Theme switch { "light" => 1, "dark" => 2, _ => 0 };
            if (MenuKeyBox is not null)
                SetGlassSelect(MenuKeyBox, S1mp1eModConfig.LabelFor(s.MenuKey));
            if (Application.Current is not null)
                Application.Current.RequestedThemeVariant = s.Theme switch
                {
                    "light" => ThemeVariant.Light,
                    "dark"  => ThemeVariant.Dark,
                    _       => ThemeVariant.Default,
                };
        }
        finally { _hydrating = false; }
        ApplyGlassPref();
        RefreshAccountChip();
        _ = RefreshInstalledVersionsAsync();
        UpdateInstallState();
    }

    // The MC versions S1mp1e has a verified liquid-glass port for (see
    // project_s1mp1e_glass_versions memory). Newest first — pick 26.2 by default.
    // Anything not on this list is either unsupported (1.18-1.21 not ported yet) or
    // unreleased. `itest install` will still fetch on demand.
    // Ordered newest → oldest so 26.2 sits top-left of the 3-column picker.
    // Each entry has a corresponding source/repos/S1mp1e/versions/mc<...> port
    // (15+ mixins each).
    // Each entry MUST exactly match the MC version the corresponding
    // `.minecraft/s1mp1e-mods/glass-<mc>.jar` was built against — `pick_glass_jar`
    // in launch.rs does EXACT-match only (a 1.21.1 build's mixin injections would
    // crash on 1.21.8). Use "1.18.2"/"1.19.2" not "1.18"/"1.19" for that reason.
    // 1.21.8 dropped until mc1218/build/libs/ has a jar.
    private static readonly string[] SupportedVersions =
    {
        "26.2",   "1.21.1",  "1.20.1",
        "1.19.2", "1.18.2",  "1.17.1",
        "1.16.5", "1.15.2",  "1.14.4",
        "1.13.2", "1.12.2",  "1.8.9",
    };

    private System.Threading.Tasks.Task RefreshInstalledVersionsAsync()
    {
        _hydrating = true;
        try
        {
            VersionBox.Options = string.Join("|", SupportedVersions);
            SetGlassSelect(VersionBox, _cfg.Settings.Version);
        }
        finally { _hydrating = false; }
        return System.Threading.Tasks.Task.CompletedTask;
    }

    private static void SetGlassSelect(GlassSelect? gs, string value)
    {
        if (gs is null) return;
        var items = gs.Items;
        var i = Array.FindIndex(items, s => string.Equals(s, value, StringComparison.OrdinalIgnoreCase));
        if (i >= 0) gs.SelectedIndex = i;
    }

    private void ApplyAccent(string hex)
    {
        try
        {
            var app = Application.Current;
            if (app is null) return;
            var c = Color.Parse(hex);
            var brush = new SolidColorBrush(c);
            // Derived shades so hover/pressed/accented controls track the chosen accent
            // instead of the old hardcoded blues.
            var hover = new SolidColorBrush(Lighten(c, 0.12));
            var pressed = new SolidColorBrush(Darken(c, 0.16));
            // "Accent" is defined inside ThemeDictionaries (Light/Dark). Setting the plain
            // Application resource gets SHADOWED by the active theme dictionary, so the
            // colour picker did nothing. Write the override into BOTH theme dictionaries
            // and into the window's own resources so it wins whatever the variant is.
            foreach (var variant in new[] { Avalonia.Styling.ThemeVariant.Light, Avalonia.Styling.ThemeVariant.Dark })
            {
                if (app.Resources.ThemeDictionaries.TryGetValue(variant, out var prov)
                    && prov is ResourceDictionary rd)
                {
                    rd["Accent"] = brush;
                    rd["AccentHover"] = hover;
                    rd["AccentPressed"] = pressed;
                }
            }
            this.Resources["Accent"] = brush;
            this.Resources["AccentHover"] = hover;
            this.Resources["AccentPressed"] = pressed;
        }
        catch { }
    }

    private static Color Lighten(Color c, double f) => Color.FromArgb(c.A,
        (byte)Math.Clamp(c.R + (255 - c.R) * f, 0, 255),
        (byte)Math.Clamp(c.G + (255 - c.G) * f, 0, 255),
        (byte)Math.Clamp(c.B + (255 - c.B) * f, 0, 255));

    private static Color Darken(Color c, double f) => Color.FromArgb(c.A,
        (byte)Math.Clamp(c.R * (1 - f), 0, 255),
        (byte)Math.Clamp(c.G * (1 - f), 0, 255),
        (byte)Math.Clamp(c.B * (1 - f), 0, 255));

    // ---- settings: live theme switch (自動 / 淺色 / 深色) ----
    private void OnThemeChanged(object? sender, EventArgs e)
    {
        if (Application.Current is null || sender is not GlassSelect box) return;
        Application.Current.RequestedThemeVariant = box.SelectedIndex switch
        {
            1 => ThemeVariant.Light,
            2 => ThemeVariant.Dark,
            _ => ThemeVariant.Default,
        };
        // Re-apply the glass/transparency tint after the variant actually settles, so
        // the reduce-transparency shade doesn't lag a live theme switch.
        Avalonia.Threading.Dispatcher.UIThread.Post(ApplyGlassPref);
        if (_hydrating) return;   // don't persist while ApplyLoadedConfig is populating
        _cfg.Settings.Theme = box.SelectedIndex switch { 1 => "light", 2 => "dark", _ => "auto" };
        SaveCfg();
    }

    // The in-game config-GUI open key (1.8.9 client). Writes launcher settings AND the
    // per-instance modules.json the mod reads (LWJGL keycode). The in-game rebind writes
    // the same field, so either path works.
    private void OnMenuKeyChanged(object? sender, EventArgs e)
    {
        if (_hydrating) return;
        var label = (sender as GlassSelect)?.SelectedText ?? MenuKeyBox.SelectedText ?? "Right Shift";
        int code = 54;
        foreach (var c in S1mp1eModConfig.KeyChoices) if (c.Label == label) { code = c.Code; break; }
        _cfg.Settings.MenuKey = code;
        SaveCfg();
        try { S1mp1eModConfig.WriteMenuKey(CurrentInstanceDir(), code); } catch (Exception ex) { LogCrash(ex); }
    }

    // MC versions >= 1.13 only work through Fabric in this launcher (Forge modding
    // beyond 1.12.2 needs its own bootstrapper we don't ship). "26.2" is our internal
    // Fabric-only fork and also counts as ForceFabric.
    private static bool IsFabricOnly(string mc)
    {
        if (string.IsNullOrEmpty(mc)) return true;
        if (mc == "26.2") return true;
        // parse "1.X[.Y]" — return true iff X >= 13
        var parts = mc.Split('.');
        if (parts.Length >= 2 && parts[0] == "1" && int.TryParse(parts[1], out var x))
            return x >= 13;
        return false;
    }

    // The inverse: pre-1.13 MC (1.8.9, 1.12.2) — our liquid glass there is a FORGE
    // coremod (Fabric didn't exist / isn't wired), so Fabric is greyed and the loader
    // auto-switches to Forge, mirroring how 26.2 forces Fabric.
    private static bool IsForgeOnly(string mc)
    {
        if (string.IsNullOrEmpty(mc) || mc == "26.2") return false;
        var parts = mc.Split('.');
        if (parts.Length >= 2 && parts[0] == "1" && int.TryParse(parts[1], out var x))
            return x < 13;
        return false;
    }

    // Which loaders a version can actually use: 1.8.9/1.12.2 = Forge only; 26.2 fork =
    // Fabric only; 1.13+ = Fabric. Forge is greyed on everything ≥1.13.
    private static bool LoaderAllowed(string mc, string loader)
    {
        loader = (loader ?? "").ToLowerInvariant();
        if (IsForgeOnly(mc)) return loader == "forge";
        if (mc == "26.2")   return loader == "fabric";
        if (IsFabricOnly(mc)) return loader == "fabric";
        return true;
    }

    // The loader to fall back to when the current pick isn't allowed for a version.
    private static string DefaultLoader(string mc) => IsForgeOnly(mc) ? "Forge" : "Fabric";

    // The loader to actually launch/install with for the current selection.
    private string EffectiveLoader()
    {
        var mc = string.IsNullOrEmpty(VersionBox?.SelectedText) ? "26.2" : VersionBox.SelectedText;
        var sel = LoaderBox?.SelectedText ?? "Fabric";
        return (LoaderAllowed(mc, sel) ? sel : DefaultLoader(mc)).ToLowerInvariant();
    }

    private void UpdateStartNavSub()
    {
        if (StartNavSub is null) return;
        var mc = string.IsNullOrEmpty(VersionBox?.SelectedText) ? "26.2" : VersionBox.SelectedText;
        var sel = string.IsNullOrEmpty(LoaderBox?.SelectedText) ? "Fabric" : LoaderBox.SelectedText;
        var ldr = LoaderAllowed(mc, sel) ? sel : DefaultLoader(mc);
        StartNavSub.Text = $"{mc} · {ldr}";
    }

    // ---- start page: remember MC version + loader on change ----
    private void OnVersionChanged(object? sender, EventArgs e)
    {
        if (_hydrating) return;
        _cfg.Settings.Version = VersionBox.SelectedText;
        // If the current loader isn't valid for this version, switch to the default one
        // (Forge for 1.8.9/1.12.2, Fabric otherwise).
        var mc = VersionBox.SelectedText;
        if (!LoaderAllowed(mc, LoaderBox.SelectedText))
        {
            var def = DefaultLoader(mc);
            int di = System.Array.FindIndex(LoaderBox.Items,
                        s => s.Equals(def, StringComparison.OrdinalIgnoreCase));
            if (di >= 0 && LoaderBox.SelectedIndex != di)
            {
                LoaderBox.SelectedIndex = di;
                _cfg.Settings.Loader = def.ToLowerInvariant();
            }
        }
        UpdateStartNavSub();
        SaveCfg();
        // Browse pane is version-scoped (scans s1mp1e-mods/<mc>/) — refresh it
        // if the user's currently viewing local mods.
        if (_modModeIdx == 1) _ = RunLocalScanAsync();
        UpdateInstallState();
    }
    private void OnLoaderChanged(object? sender, EventArgs e)
    {
        if (_hydrating) return;
        _cfg.Settings.Loader = LoaderBox.SelectedText.ToLowerInvariant();
        UpdateStartNavSub();
        SaveCfg();
        UpdateInstallState();
    }

    // ---- start page: install-state of the selected version + on-demand download ----

    // Is a launchable profile for this MC+loader already on disk? Fabric = a
    // `fabric-loader-…-<mc>` profile; Forge = a `<mc>-forge…` profile. (PLAY auto-
    // installs missing Fabric, but Forge — 1.8.9/1.12.2 — must be installed first,
    // which is exactly what the 下載 button is for.)
    private bool IsVersionInstalled(string mc, string loader)
    {
        try
        {
            var dir = System.IO.Path.Combine(EffectiveMcDir(), "versions");
            if (!System.IO.Directory.Exists(dir)) return false;
            foreach (var d in System.IO.Directory.GetDirectories(dir))
            {
                var id = System.IO.Path.GetFileName(d);
                if (!System.IO.File.Exists(System.IO.Path.Combine(d, id + ".json"))) continue;
                if (loader == "forge")
                {
                    if (id.StartsWith(mc + "-forge", StringComparison.OrdinalIgnoreCase)) return true;
                }
                else
                {
                    if (id.StartsWith("fabric-loader", StringComparison.OrdinalIgnoreCase)
                        && id.EndsWith("-" + mc, StringComparison.OrdinalIgnoreCase)) return true;
                }
            }
        }
        catch { }
        return false;
    }

    // Refresh the version card's 安裝狀態 line + 下載 button for the current selection.
    private void UpdateInstallState()
    {
        if (InstallStatus is null || InstallBtn is null || VersionBox is null) return;
        var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? "26.2" : VersionBox.SelectedText;
        var loader = EffectiveLoader();
        if (IsVersionInstalled(mc, loader))
        {
            InstallStatus.Text = "已安裝，可直接遊玩";
            InstallBtn.IsVisible = false;
        }
        else
        {
            InstallStatus.Text = loader == "forge"
                ? "尚未安裝 — Forge 版本需先下載才能遊玩"
                : "尚未安裝 — 按「開始」會自動下載，或先手動下載";
            InstallBtn.Content = "下載此版本";
            InstallBtn.IsEnabled = true;
            InstallBtn.Tag = mc;
            InstallBtn.IsVisible = true;
        }
    }

    // Download+install the SELECTED version via `itest install <fabric|forge> <mc>`,
    // streaming the % onto the button. On success, re-check install state.
    private async void OnInstallSelectedVersion(object? sender, RoutedEventArgs e)
    {
        if (sender is not Button btn) return;
        var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? "26.2" : VersionBox.SelectedText;
        var loader = EffectiveLoader();
        var exe = ResolveItestExe();
        if (!System.IO.File.Exists(exe)) { btn.Content = "缺 CLI"; return; }
        btn.IsEnabled = false;
        btn.Content = "安裝中…";
        ToolTip.SetTip(btn, null);
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo(exe)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
            };
            psi.ArgumentList.Add("install");
            psi.ArgumentList.Add(loader);
            psi.ArgumentList.Add(mc);
            if (!string.IsNullOrEmpty(_cfg.Settings.McPath))
                psi.ArgumentList.Add(_cfg.Settings.McPath);

            string? lastErr = null;
            var proc = new System.Diagnostics.Process { StartInfo = psi, EnableRaisingEvents = true };
            proc.ErrorDataReceived += (_, ev) =>
            {
                if (ev.Data is null) return;
                var m = System.Text.RegularExpressions.Regex.Match(ev.Data, @"(\d{1,3})%");
                if (m.Success)
                    Avalonia.Threading.Dispatcher.UIThread.Post(() => btn.Content = $"{m.Groups[1].Value}%");
                else if (!string.IsNullOrWhiteSpace(ev.Data) && !ev.Data.StartsWith("["))
                    lastErr = ev.Data.Trim();
            };
            proc.OutputDataReceived += (_, _) => { };
            proc.Start();
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            await proc.WaitForExitAsync();
            int code = proc.ExitCode;
            Avalonia.Threading.Dispatcher.UIThread.Post(() =>
            {
                if (code == 0) UpdateInstallState();
                else
                {
                    btn.Content = "重試";
                    btn.IsEnabled = true;
                    ToolTip.SetTip(btn, string.IsNullOrEmpty(lastErr) ? $"安裝失敗（碼 {code}）" : $"安裝失敗：{lastErr}");
                }
            });
        }
        catch (Exception ex)
        {
            LogCrash(ex);
            btn.Content = "重試";
            btn.IsEnabled = true;
        }
    }

    // iOS-26 scroll-edge fade: the top/bottom scrims dissolve content into the pane,
    // fading in with how far the detail list is scrolled from each edge.
    private void UpdateEdgeScrims()
    {
        if (DetailScroller is null) return;
        var y = DetailScroller.Offset.Y;
        var max = DetailScroller.ScrollBarMaximum.Y;
        const double ramp = 26;
        if (ScrimTop is not null) ScrimTop.Opacity = Math.Clamp(y / ramp, 0, 1);
        if (ScrimBottom is not null) ScrimBottom.Opacity = max <= 0.5 ? 0 : Math.Clamp((max - y) / ramp, 0, 1);
    }

    // ---- settings: general ----
    private void OnRamChanged(object? sender, EventArgs e)
    {
        if (_hydrating) return;
        if (sender is GlassSlider gs)
        {
            _cfg.Settings.RamMb = (int)Math.Round(gs.Value) * 1024;
            SaveCfg();
        }
    }
    private async void OnPickMcDir(object? sender, RoutedEventArgs e)
    {
        var top = TopLevel.GetTopLevel(this);
        if (top is null) return;
        var picked = await top.StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            Title = "選擇 .minecraft 目錄",
            AllowMultiple = false,
        });
        var f = picked.FirstOrDefault();
        if (f is null) return;
        var path = f.Path.LocalPath;
        _cfg.Settings.McPath = path;
        McPathLabel.Text = path;
        SaveCfg();
    }
    private void OnAfterLaunchChanged(object? sender, EventArgs e)
    {
        if (_hydrating) return;
        _cfg.Settings.AfterLaunch = AfterLaunchBox.SelectedIndex switch { 1 => "keep", 2 => "close", _ => "hide" };
        SaveCfg();
    }

    // ---- settings: advanced (JVM args / resolution / custom Java) ----
    private void OnJvmArgsChanged(object? sender, RoutedEventArgs e)
    {
        if (_hydrating || JvmArgsBox is null) return;
        _cfg.Settings.JvmArgs = JvmArgsBox.Text?.Trim() ?? "";
        SaveCfg();
    }

    private void OnResChanged(object? sender, RoutedEventArgs e)
    {
        if (_hydrating) return;
        int.TryParse(ResWidthBox?.Text?.Trim(),  out var w);
        int.TryParse(ResHeightBox?.Text?.Trim(), out var h);
        _cfg.Settings.ResWidth  = w > 0 ? w : 0;
        _cfg.Settings.ResHeight = h > 0 ? h : 0;
        SaveCfg();
    }

    private async void OnPickJava(object? sender, RoutedEventArgs e)
    {
        var top = TopLevel.GetTopLevel(this);
        if (top is null) return;
        var picked = await top.StorageProvider.OpenFilePickerAsync(new Avalonia.Platform.Storage.FilePickerOpenOptions
        {
            Title = "選擇 Java 執行檔（javaw.exe / java.exe）",
            AllowMultiple = false,
            FileTypeFilter = new[]
            {
                new Avalonia.Platform.Storage.FilePickerFileType("Java")
                {
                    Patterns = new[] { "javaw.exe", "java.exe", "java", "javaw" }
                }
            },
        });
        var f = picked.FirstOrDefault();
        if (f is null) return;
        var path = f.Path.LocalPath;
        _cfg.Settings.JavaPath = path;
        if (JavaPathLabel is not null) JavaPathLabel.Text = path;
        SaveCfg();
    }

    private void OnClearJava(object? sender, RoutedEventArgs e)
    {
        _cfg.Settings.JavaPath = "";
        if (JavaPathLabel is not null) JavaPathLabel.Text = "自動（依版本選擇）";
        SaveCfg();
    }

    // ---- settings: appearance ----
    private void OnAccentChanged(object? sender, EventArgs e)
    {
        if (_hydrating) return;
        var idx = Math.Clamp(AccentBox.SelectedIndex, 0, Accents.Length - 1);
        var hex = Accents[idx].hex;
        _cfg.Settings.Accent = hex;
        ApplyAccent(hex);
        SaveCfg();
    }
    private void OnGlassToggleChanged(object? sender, RoutedEventArgs e)
    {
        _cfg.Settings.Glass = GlassToggle.IsChecked == true;
        ApplyGlassPref();
        SaveCfg();
    }
    private void OnReduceToggleChanged(object? sender, RoutedEventArgs e)
    {
        _cfg.Settings.ReduceTransparency = DemoToggle.IsChecked == true;
        ApplyGlassPref();
        SaveCfg();
    }

    // Hide the selection pill's glass effect when 液態玻璃 is off. Bump acrylic opacity
    // when 減少透明度 is on (both sidebar and detail material use a shared MaterialOpacity).
    private void ApplyGlassPref()
    {
        try
        {
            // Kill/restore the pill's glass refraction+highlight (leaving a plain rounded fill).
            if (SelPill is not null) SelPill.IsVisible = _cfg.Settings.Glass;

            // Bump the tint alpha in the shared acrylic materials for the "reduce transparency" mode.
            // Both sidebar and detail borders sample DynamicResource SidebarTint/DetailTint colors —
            // swap them to more opaque values when reduce is on.
            if (Application.Current is null) return;
            if (_cfg.Settings.ReduceTransparency)
            {
                var app = Application.Current;
                var isDark = ActualThemeVariant == ThemeVariant.Dark;
                app.Resources["SidebarTint"] = isDark ? Color.Parse("#1C1C1E") : Color.Parse("#F0F2F5");
                app.Resources["DetailTint"]  = isDark ? Color.Parse("#1E1E1E") : Color.Parse("#FBFBFD");
            }
            else
            {
                var app = Application.Current;
                var isDark = ActualThemeVariant == ThemeVariant.Dark;
                app.Resources["SidebarTint"] = isDark ? Color.Parse("#1A1A1C") : Color.Parse("#EDEDED");
                app.Resources["DetailTint"]  = isDark ? Color.Parse("#242426") : Color.Parse("#F5F5F7");
            }
        }
        catch { }
    }

    // ---- account ----
    private void OnOfflineNameChanged(object? sender, RoutedEventArgs e)
    {
        if (_hydrating) return;
        _cfg.Settings.OfflineName = string.IsNullOrWhiteSpace(OfflineNameBox.Text) ? "Player" : OfflineNameBox.Text.Trim();
        SaveCfg();
    }
    // Microsoft device-code sign-in: spawn `itest login <client_id>`. The CLI prints
    // "CODE <user_code>\t<verification_uri>" as soon as it has one — we show the code
    // ON the button and open the verification page in the user's browser. When the CLI
    // finishes with "DONE <name>\t<uuid>", the account is saved in config.json.
    private async void OnLogin(object? sender, RoutedEventArgs e)
    {
        if (sender is not Button btn) return;
        // Empty ClientId is fine — the Rust CLI falls back to the bundled Azure app
        // so a first-time user can sign in without any Azure setup.
        var clientId = (_cfg.ClientId ?? "").Trim();
        var exe = ResolveItestExe();
        if (!System.IO.File.Exists(exe)) { btn.Content = "缺 CLI"; return; }

        btn.IsEnabled = false;
        btn.Content = "取得授權碼…";
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo(exe)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
            };
            psi.ArgumentList.Add("login");
            psi.ArgumentList.Add(clientId);

            var proc = new System.Diagnostics.Process { StartInfo = psi, EnableRaisingEvents = true };
            string lastErr = "";
            proc.OutputDataReceived += (_, ev) =>
            {
                if (ev.Data is null) return;
                // Device-code flow: "CODE <8-char>\t<verification_uri>" — show code on
                // the button so the user can read it, and open the verify page in the browser.
                if (ev.Data.StartsWith("CODE "))
                {
                    var parts = ev.Data.Substring(5).Split('\t');
                    var code = parts.Length > 0 ? parts[0] : "";
                    var url  = parts.Length > 1 ? parts[1] : "https://microsoft.com/link";
                    Avalonia.Threading.Dispatcher.UIThread.Post(async () =>
                    {
                        // Put the 8-char device code on the clipboard so the user can just
                        // Ctrl+V into Microsoft's page — no typing.
                        try
                        {
                            var top = TopLevel.GetTopLevel(this);
                            if (top?.Clipboard is { } cb) await cb.SetTextAsync(code);
                        }
                        catch { }
                        btn.Content = $"{code} 已複製 · 貼上";
                        ToolTip.SetTip(btn, $"授權碼 {code} 已複製到剪貼簿,在瀏覽器貼上");
                        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true }); }
                        catch { }
                    });
                }
                else if (ev.Data.StartsWith("DONE "))
                {
                    var name = ev.Data.Substring(5).Split('\t').FirstOrDefault() ?? "已登入";
                    Avalonia.Threading.Dispatcher.UIThread.Post(() =>
                    {
                        btn.Content = name;
                        btn.IsEnabled = false;   // logged in, disable re-login for now
                        _cfg = ConfigStore.Load();   // pick up the saved account
                        RefreshAccountChip();
                    });
                }
            };
            // Capture the last stderr line so a failure can show WHY, not just "登入失敗".
            proc.ErrorDataReceived += (_, ev) =>
            {
                if (!string.IsNullOrWhiteSpace(ev.Data)) lastErr = ev.Data;
            };
            proc.Start();
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            await proc.WaitForExitAsync();
            if (proc.ExitCode != 0)
            {
                Avalonia.Threading.Dispatcher.UIThread.Post(() =>
                {
                    // "LOGIN_ERR 使用者拒絕授權" → 使用者拒絕授權
                    var msg = lastErr.StartsWith("LOGIN_ERR ") ? lastErr.Substring(10) : "登入失敗";
                    btn.Content = msg.Length > 12 ? msg.Substring(0, 12) + "…" : msg;
                    ToolTip.SetTip(btn, lastErr);
                    btn.IsEnabled = true;
                });
            }
        }
        catch
        {
            btn.Content = "登入";
            btn.IsEnabled = true;
        }
    }

    // ---- sidebar account chip ----
    // Click the chip row → open switcher (list all + 登入新帳號 + 登出).
    // Click the small + on the chip → 添加 / 登出 popup.
    // Click the big blue CTA (logged-out state) → jump to 帳號 page.
    private void OnAccountChipPressed(object? sender, PointerPressedEventArgs e)
    {
        _cfg = ConfigStore.Load();
        ShowAccountSwitcher();
    }
    private void OnAccountSwitchClick(object? sender, RoutedEventArgs e)
    {
        _cfg = ConfigStore.Load();
        ShowAccountSwitcher();
    }
    // Full-body skin render for the 皮膚 card. mc-heads has a body render endpoint;
    // fallback to head if the body render fails.
    private static readonly Dictionary<string, Bitmap> _bodyCache = new();
    private async System.Threading.Tasks.Task LoadSkinBodyAsync(string uuid)
    {
        if (string.IsNullOrWhiteSpace(uuid) || SkinBodyImage is null) return;
        try
        {
            if (!_bodyCache.TryGetValue(uuid, out var bmp))
            {
                byte[]? bytes = null;
                foreach (var url in new[] {
                    $"https://mc-heads.net/body/{uuid}/120",
                    $"https://mc-heads.net/avatar/{uuid}/64",
                })
                {
                    try
                    {
                        using var resp = await _avatarHttp.GetAsync(url).ConfigureAwait(false);
                        if (resp.IsSuccessStatusCode)
                        {
                            bytes = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                            break;
                        }
                    }
                    catch { }
                }
                if (bytes is null || bytes.Length < 200) return;
                await Dispatcher.UIThread.InvokeAsync(() =>
                {
                    using var ms = new MemoryStream(bytes);
                    bmp = new Bitmap(ms);
                    _bodyCache[uuid] = bmp;
                });
            }
            if (bmp is not null)
                await Dispatcher.UIThread.InvokeAsync(() => SkinBodyImage.Source = bmp);
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // Smooth-scroll wheel handler (Avalonia 12 doesn't do this natively — its
    // IsScrollInertiaEnabled is touch-only, and .Offset snaps on each wheel notch).
    //
    // Algorithm: exponential decay with real elapsed time. On each wheel event we
    // add the delta to a running `_scrollTarget`. A timer runs at ~120Hz and each
    // tick moves the actual offset toward the target by  `1 - exp(-decay * dt)`
    // — critical-damping shape, framerate-independent (unlike a naive per-frame
    // lerp which snaps when the tick rate wobbles). Stops when within 0.3px of
    // target. Same feel as macOS System Settings.
    private double _scrollTarget = double.NaN;
    private DispatcherTimer? _scrollTimer;
    private readonly System.Diagnostics.Stopwatch _scrollSw = new();
    private const double ScrollStep = 110;   // pixels per wheel notch
    private const double ScrollDecay = 14.0; // higher = snappier settle

    private void OnDetailScrollWheel(object? sender, PointerWheelEventArgs e)
    {
        // Mark handled FIRST so ScrollViewer's own OnPointerWheelChanged skips
        // its snap-scroll — otherwise it competes with our tween on the same tick.
        e.Handled = true;
        if (DetailScroller is null) return;
        // ScrollBarMaximum.Y is what Avalonia's ScrollViewer itself uses as the
        // upper offset bound (accounts for padding + any layout quirks the raw
        // Extent-Viewport subtraction misses).
        var extentY = DetailScroller.ScrollBarMaximum.Y;
        if (extentY <= 0) return;

        if (double.IsNaN(_scrollTarget)) _scrollTarget = DetailScroller.Offset.Y;
        _scrollTarget -= e.Delta.Y * ScrollStep;
        _scrollTarget = Math.Max(0, Math.Min(extentY, _scrollTarget));

        // 3ms → ~333 Hz cap, well above 240 fps target on high-refresh monitors.
        // Render-priority so the dispatcher schedules us right before each frame.
        if (_scrollTimer is null)
            _scrollTimer = new DispatcherTimer(TimeSpan.FromMilliseconds(3),
                DispatcherPriority.Render, OnScrollTick);
        if (!_scrollTimer.IsEnabled) { _scrollSw.Restart(); _scrollTimer.Start(); }
    }

    private void OnScrollTick(object? sender, EventArgs e)
    {
        if (DetailScroller is null) { _scrollTimer?.Stop(); return; }
        var dt = _scrollSw.Elapsed.TotalSeconds;
        _scrollSw.Restart();
        if (dt > 0.05) dt = 0.05;

        // Re-clamp target each tick — Extent may grow as items/layout resolve.
        var extentY = DetailScroller.ScrollBarMaximum.Y;
        if (extentY > 0) _scrollTarget = Math.Max(0, Math.Min(extentY, _scrollTarget));

        var cur = DetailScroller.Offset.Y;
        var diff = _scrollTarget - cur;
        if (Math.Abs(diff) < 0.3)
        {
            DetailScroller.Offset = new Vector(DetailScroller.Offset.X, _scrollTarget);
            _scrollTimer?.Stop();
            _scrollTarget = double.NaN;
            return;
        }
        var t = 1.0 - Math.Exp(-ScrollDecay * dt);
        var step = diff * t;
        var next = cur + step;
        DetailScroller.Offset = new Vector(DetailScroller.Offset.X, next);
    }

    // 皮膚 card 切換 → menu: 尋找 (mineskin gallery) / 導入 (file picker)
    private void OnSkinChangeClick(object? sender, RoutedEventArgs e)
    {
        ShowMenuFor(SkinChangeBtn,
            new[] { "尋找", "導入" }, -1,
            disabled: new[] { false, false },
            pick =>
            {
                if (pick == 0) _ = OpenSkinGalleryAsync();
                else if (pick == 1) _ = ImportSkinFromFileAsync();
            });
    }

    // 導入 — open a file picker starting in Downloads, upload the chosen PNG to Mojang.
    private async System.Threading.Tasks.Task ImportSkinFromFileAsync()
    {
        var acc = _cfg.Account;
        if (acc is null || string.IsNullOrEmpty(acc.McToken))
        {
            LogCrash(new InvalidOperationException("未登入")); return;
        }
        var top = TopLevel.GetTopLevel(this);
        if (top is null) return;
        var downloads = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var downloadsFolder = Path.Combine(downloads, "Downloads");
        IStorageFolder? start = null;
        try { start = await top.StorageProvider.TryGetFolderFromPathAsync(downloadsFolder); }
        catch { }
        var files = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "選擇皮膚 PNG",
            AllowMultiple = false,
            SuggestedStartLocation = start,
            FileTypeFilter = new[] { new FilePickerFileType("PNG 圖片") { Patterns = new[] { "*.png" } } },
        });
        if (files is null || files.Count == 0) return;
        try
        {
            await using var s = await files[0].OpenReadAsync();
            using var ms = new MemoryStream();
            await s.CopyToAsync(ms);
            await SkinService.ApplySkinAsync(acc.McToken, ms.ToArray());
            // Invalidate our head/body caches so the new skin shows on next reload.
            _avatarCache.Remove(acc.Uuid); _pageSkinCache.Remove(acc.Uuid); _bodyCache.Remove(acc.Uuid);
            _ = LoadAvatarAsync(acc.Uuid);
            _ = LoadPageSkinAsync(acc.Uuid);
            _ = LoadSkinBodyAsync(acc.Uuid);
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // 尋找 — fetch mineskin trending, show a WrapPanel of clickable tiles.
    private async System.Threading.Tasks.Task OpenSkinGalleryAsync()
    {
        if (SkinGalleryRoot is null || SkinGrid is null) return;
        OverlayHost.IsVisible = true;
        MenuRoot.IsVisible = false;
        SheetRoot.IsVisible = false;
        SkinGalleryRoot.IsVisible = true;
        SkinGalleryRoot.Opacity = 0;
        SkinGalleryRoot.Width = 560; SkinGalleryRoot.Height = 460;
        Canvas.SetLeft(SkinGalleryRoot, (OverlayHost.Bounds.Width - 560) / 2);
        Canvas.SetTop(SkinGalleryRoot, (OverlayHost.Bounds.Height - 460) / 2);
        var appear = new Animation
        {
            Duration = TimeSpan.FromMilliseconds(180), Easing = new CubicEaseOut(),
            FillMode = FillMode.Forward,
            Children = {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(OpacityProperty, 0d) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(OpacityProperty, 1d) } },
            }
        };
        _ = appear.RunAsync(SkinGalleryRoot);

        SkinGrid.Items.Clear();
        SkinGalleryStatus.Text = "從 mineskin.org 載入中…";
        try
        {
            var trending = await SkinService.FetchTrendingAsync(28);
            SkinGalleryStatus.Text = trending.Count == 0 ? "沒有結果" : $"{trending.Count} 個皮膚 · 點擊套用";
            foreach (var sk in trending) SkinGrid.Items.Add(BuildSkinTile(sk));
        }
        catch (Exception ex) { LogCrash(ex); SkinGalleryStatus.Text = "載入失敗"; }
    }

    private Control BuildSkinTile(TrendingSkin sk)
    {
        var img = new Image { Stretch = Stretch.Uniform, Width = 84, Height = 84 };
        // Preview by piping the raw texture URL through mc-heads' body renderer.
        _ = LoadSkinTilePreviewAsync(img, sk.TextureUrl);
        var tile = new Border
        {
            Width = 96, Height = 116, Margin = new Thickness(8),
            CornerRadius = new CornerRadius(10),
            Background = new SolidColorBrush(Color.Parse("#12FFFFFF")),
            Cursor = new Cursor(StandardCursorType.Hand),
            Padding = new Thickness(8),
            Child = img,
        };
        tile.PointerPressed += async (_, __) =>
        {
            try
            {
                var acc = _cfg.Account;
                if (acc is null || string.IsNullOrEmpty(acc.McToken)) return;
                SkinGalleryStatus.Text = "套用中…";
                var png = await SkinService.DownloadPngAsync(sk.TextureUrl);
                if (png is null) { SkinGalleryStatus.Text = "下載失敗"; return; }
                await SkinService.ApplySkinAsync(acc.McToken, png);
                SkinGalleryStatus.Text = "已套用 ✓";
                _avatarCache.Remove(acc.Uuid); _pageSkinCache.Remove(acc.Uuid); _bodyCache.Remove(acc.Uuid);
                _ = LoadAvatarAsync(acc.Uuid);
                _ = LoadPageSkinAsync(acc.Uuid);
                _ = LoadSkinBodyAsync(acc.Uuid);
            }
            catch (Exception ex) { LogCrash(ex); SkinGalleryStatus.Text = "套用失敗"; }
        };
        return tile;
    }

    private static async System.Threading.Tasks.Task LoadSkinTilePreviewAsync(Image target, string textureUrl)
    {
        try
        {
            // Render the raw skin texture as its face crop. mc-heads accepts a
            // textures.minecraft.net URL directly via ?url= query.
            var previewUrl = "https://mc-heads.net/avatar?url=" + Uri.EscapeDataString(textureUrl) + "&size=84";
            byte[]? bytes = null;
            try
            {
                using var resp = await _avatarHttp.GetAsync(previewUrl).ConfigureAwait(false);
                if (resp.IsSuccessStatusCode) bytes = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
            }
            catch { }
            // Fallback: just show the raw 64×64 texture (looks weirder but always works)
            if (bytes is null)
            {
                using var resp = await _avatarHttp.GetAsync(textureUrl).ConfigureAwait(false);
                if (resp.IsSuccessStatusCode) bytes = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
            }
            if (bytes is null || bytes.Length < 100) return;
            await Dispatcher.UIThread.InvokeAsync(() =>
            {
                using var ms = new MemoryStream(bytes);
                target.Source = new Bitmap(ms);
            });
        }
        catch { }
    }

    private void OnAccountLogoutClick(object? sender, RoutedEventArgs e)
    {
        _cfg = ConfigStore.Load();
        if (_cfg.Account is { } cur)
            _cfg.Accounts.RemoveAll(a => a.Uuid == cur.Uuid);
        _cfg.Account = _cfg.Accounts.Count > 0 ? _cfg.Accounts[0] : null;
        ConfigStore.Save(_cfg);
        RefreshAccountChip();
    }
    private void OnChipPlusClick(object? sender, RoutedEventArgs e)
    {
        _cfg = ConfigStore.Load();
        ShowChipPlusMenu();
    }
    private void OnLoginCtaClick(object? sender, RoutedEventArgs e)
    {
        _selected = 2;
        MovePill(2, animate: true);
        UpdateNavWeights(2);
        ShowPage(2);
    }

    // Sign out = clear active account (keep the tokens in accounts[] so switching back
    // doesn't need re-auth). Removing the last account returns to the CTA state.
    private void SignOutActive()
    {
        // Re-read first: the CLI may have refreshed the account since we loaded, and
        // accounts[] must not be rolled back to a stale in-memory copy.
        _cfg = ConfigStore.Load();
        _cfg.Account = null;
        ConfigStore.Save(_cfg);
        RefreshAccountChip();
    }

    // Two-state sidebar-bottom UI:
    //  - No active account → big blue LoginCta on the left, chip hidden.
    //  - Signed in         → chip visible, CTA hidden.
    // First transition into signed-in state is animated (crossfade + the chip slides
    // in from the left — so the plus visually "flies" from the CTA position to the
    // small + at the chip's right side).
    private bool _accountUiInitialised;
    private void RefreshAccountChip()
    {
        try
        {
            var live = ConfigStore.Load();
            _cfg.Account = live.Account;
            _cfg.Accounts = live.Accounts ?? new();

            bool signedIn = live.Account is { } a && !string.IsNullOrEmpty(a.Name);

            if (signedIn)
            {
                var acc = live.Account!;
                if (ChipName is not null) ChipName.Text = acc.Name;
                if (ChipSub  is not null) ChipSub.Text  = _cfg.Accounts.Count > 1
                    ? $"Microsoft · {_cfg.Accounts.Count} 個帳號"
                    : "Microsoft 帳號";
                // Sidebar nav row's "未登入" subtitle mirrors the current MSA name.
                if (NavAccountSub is not null) NavAccountSub.Text = acc.Name;
                _ = LoadAvatarAsync(acc.Uuid);

                // 帳號 page: swap the login prompt for the signed-in identity card.
                if (SignedInCard    is not null) SignedInCard.IsVisible    = true;
                if (LoginPromptCard is not null) LoginPromptCard.IsVisible = false;
                if (SkinCard        is not null) SkinCard.IsVisible        = true;
                if (OfflineCaption  is not null) OfflineCaption.IsVisible  = false;
                if (OfflineCard     is not null) OfflineCard.IsVisible     = false;
                if (PageAccountName is not null) PageAccountName.Text = acc.Name;
                _ = LoadPageSkinAsync(acc.Uuid);
                _ = LoadSkinBodyAsync(acc.Uuid);
            }
            else
            {
                if (ChipName is not null) ChipName.Text = string.IsNullOrEmpty(_cfg.Settings.OfflineName) ? "尚未登入" : _cfg.Settings.OfflineName;
                if (ChipSub  is not null) ChipSub.Text  = "離線模式";
                if (NavAccountSub is not null) NavAccountSub.Text = "未登入";
                if (SignedInCard    is not null) SignedInCard.IsVisible    = false;
                if (LoginPromptCard is not null) LoginPromptCard.IsVisible = true;
                if (SkinCard        is not null) SkinCard.IsVisible        = false;
                if (OfflineCaption  is not null) OfflineCaption.IsVisible  = true;
                if (OfflineCard     is not null) OfflineCard.IsVisible     = true;
            }

            SetAccountView(signedIn, animate: _accountUiInitialised);
            _accountUiInitialised = true;
        }
        catch { }
    }

    // Fetch the Minecraft head skin from crafatar and paint it onto the chip's
    // circle. Cached in-process by UUID so switching accounts doesn't re-download.
    private static readonly System.Net.Http.HttpClient _avatarHttp = new()
        { Timeout = TimeSpan.FromSeconds(6) };
    private static readonly Dictionary<string, Bitmap> _avatarCache = new();
    private async System.Threading.Tasks.Task LoadAvatarAsync(string uuid)
    {
        if (string.IsNullOrWhiteSpace(uuid) || ChipAvatar is null) return;
        void ApplyBitmap(Bitmap b) => ChipAvatar.Fill = new ImageBrush(b) { Stretch = Stretch.UniformToFill };
        try
        {
            Bitmap? bmp;
            if (!_avatarCache.TryGetValue(uuid, out bmp))
            {
                // mc-heads.net serves the head skin (with hat overlay baked in) as PNG.
                // Cloudflare-fronted, works with dashed OR undashed UUIDs. crafatar was
                // returning 500 for some UUIDs so we prefer mc-heads first, minotar.net
                // second.
                byte[]? bytes = null;
                foreach (var url in new[] {
                    $"https://mc-heads.net/avatar/{uuid}/64",
                    $"https://minotar.net/helm/{uuid}/64.png",
                })
                {
                    try
                    {
                        using var resp = await _avatarHttp.GetAsync(url).ConfigureAwait(false);
                        if (resp.IsSuccessStatusCode)
                        {
                            bytes = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                            break;
                        }
                    }
                    catch { }
                }
                if (bytes is null || bytes.Length < 200) return;
                await Dispatcher.UIThread.InvokeAsync(() =>
                {
                    using var ms = new MemoryStream(bytes);
                    bmp = new Bitmap(ms);
                    _avatarCache[uuid] = bmp;
                });
            }
            if (bmp is not null)
                await Dispatcher.UIThread.InvokeAsync(() => ApplyBitmap(bmp));
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // Bigger head tile on the 帳號 page (128px source shrunk into a 56×56 rounded
    // tile). Same fallback chain as the chip.
    private static readonly Dictionary<string, Bitmap> _pageSkinCache = new();
    private async System.Threading.Tasks.Task LoadPageSkinAsync(string uuid)
    {
        if (string.IsNullOrWhiteSpace(uuid) || PageSkinImage is null) return;
        try
        {
            if (!_pageSkinCache.TryGetValue(uuid, out var bmp))
            {
                byte[]? bytes = null;
                foreach (var url in new[] {
                    $"https://mc-heads.net/avatar/{uuid}/128",
                    $"https://minotar.net/helm/{uuid}/128.png",
                })
                {
                    try
                    {
                        using var resp = await _avatarHttp.GetAsync(url).ConfigureAwait(false);
                        if (resp.IsSuccessStatusCode)
                        {
                            bytes = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                            break;
                        }
                    }
                    catch { }
                }
                if (bytes is null || bytes.Length < 200) return;
                await Dispatcher.UIThread.InvokeAsync(() =>
                {
                    using var ms = new MemoryStream(bytes);
                    bmp = new Bitmap(ms);
                    _pageSkinCache[uuid] = bmp;
                });
            }
            if (bmp is not null)
                await Dispatcher.UIThread.InvokeAsync(() => PageSkinImage.Source = bmp);
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // Crossfade + slide between the CTA (logged-out, big blue + left) and the chip
    // (logged-in, avatar + name + small + right).
    private async void SetAccountView(bool signedIn, bool animate)
    {
        if (LoginCta is null || AccountChip is null) return;
        LoginCta.IsVisible = true; AccountChip.IsVisible = true;
        if (!animate)
        {
            LoginCta.Opacity    = signedIn ? 0 : 1;
            AccountChip.Opacity = signedIn ? 1 : 0;
            AccountChip.Margin  = new Thickness(0, 8, 0, 0);
            LoginCta.IsVisible    = !signedIn;
            AccountChip.IsVisible = signedIn;
            return;
        }
        // Animate ~360ms crossfade. On sign-in, the chip enters with a small left→right
        // margin slide so the small + moves from the CTA position toward the right.
        var dur = TimeSpan.FromMilliseconds(360);
        var ease = new CubicEaseOut();

        var fadeOut = new Animation
        {
            Duration = dur, Easing = ease, FillMode = FillMode.Forward,
            Children = {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(OpacityProperty, 1d) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(OpacityProperty, 0d) } },
            }
        };
        var fadeIn = new Animation
        {
            Duration = dur, Easing = ease, FillMode = FillMode.Forward,
            Children = {
                new KeyFrame { Cue = new Cue(0d), Setters = {
                    new Setter(OpacityProperty, 0d),
                    new Setter(MarginProperty, signedIn ? new Thickness(-40, 8, 0, 0) : new Thickness(0, 8, 0, 0)),
                }},
                new KeyFrame { Cue = new Cue(1d), Setters = {
                    new Setter(OpacityProperty, 1d),
                    new Setter(MarginProperty, new Thickness(0, 8, 0, 0)),
                }},
            }
        };
        try
        {
            if (signedIn) { _ = fadeOut.RunAsync(LoginCta);    await fadeIn.RunAsync(AccountChip); LoginCta.IsVisible = false; }
            else          { _ = fadeOut.RunAsync(AccountChip); await fadeIn.RunAsync(LoginCta);    AccountChip.IsVisible = false; }
        }
        catch { }
    }

    // Small + on the chip → 添加新帳號 / 登出當前帳號 popup, anchored under the +.
    private void ShowChipPlusMenu()
    {
        ShowMenuFor(ChipAdd, new[] { "＋ 添加帳號", "登出" }, -1, pick =>
        {
            if (pick == 0) OnLogin(LoginBtn, new RoutedEventArgs());
            else if (pick == 1) SignOutActive();
        });
    }

    // Popup with every saved account (✓ on the active one). Anchored under the chip.
    // 添加/登出 lives on the + button instead — the chip is purely for SWITCHING.
    private void ShowAccountSwitcher()
    {
        if (_cfg.Accounts.Count == 0) return;
        var items = _cfg.Accounts.Select(a => a.Name).ToArray();
        int activeIdx = _cfg.Account is { } cur
            ? _cfg.Accounts.FindIndex(a => a.Uuid == cur.Uuid) : -1;

        ShowMenuFor(AccountChip, items, activeIdx, pick =>
        {
            if (pick < 0 || pick >= _cfg.Accounts.Count) return;
            if (_cfg.Account?.Uuid == _cfg.Accounts[pick].Uuid) return;
            _cfg.Account = _cfg.Accounts[pick];
            ConfigStore.Save(_cfg);
            RefreshAccountChip();
        });
    }

    // Mods page has two modes: 0 = query (Modrinth search), 1 = browse (installed jars).
    // The toggle button's label shows the OPPOSITE mode so it reads as an action —
    // "click 瀏覽模組" to enter browse, "click 查詢模組" to go back.
    private int _modModeIdx = 0;   // start in query mode

    private void OnModModeToggle(object? sender, PointerPressedEventArgs e)
    {
        SetModMode(_modModeIdx == 0 ? 1 : 0);
    }

    private void SetModMode(int idx)
    {
        if (idx == _modModeIdx) return;
        var goingBrowse = idx == 1;
        ModModeToggleLabel.Text = goingBrowse ? "查詢模組" : "瀏覽模組";
        // Sort dropdown is only meaningful for Modrinth's `index=downloads/updated/...`.
        ModSortBox.IsVisible = !goingBrowse;
        if (ModCategoryScroller is not null) ModCategoryScroller.IsVisible = !goingBrowse;
        ModSearchBox.PlaceholderText = goingBrowse ? "篩選本地模組" : "搜尋 Modrinth 模組";
        // Always re-scan on entering browse mode so newly-dropped jars, filename
        // changes and downloads made in a different mode all show up immediately.
        if (goingBrowse) _ = RunLocalScanAsync();
        _ = CrossfadeModPane(idx);
        _ = AnimateSidebarForMode(goingBrowse);
        _modModeIdx = idx;
    }

    // Crossfade ModrinthPane ↔ LocalBrowsePane — same 180ms pattern as ShowPage.
    private async System.Threading.Tasks.Task CrossfadeModPane(int idx)
    {
        var old = _modModeIdx == 0 ? (Control)ModrinthPane : LocalBrowsePane;
        var neu = idx == 0 ? (Control)ModrinthPane : LocalBrowsePane;
        try
        {
            var dur = TimeSpan.FromMilliseconds(180);
            var fadeOut = new Animation
            {
                Duration = dur, Easing = new CubicEaseIn(), FillMode = FillMode.Forward,
                Children = {
                    new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(OpacityProperty, 1d) } },
                    new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(OpacityProperty, 0d) } },
                }
            };
            await fadeOut.RunAsync(old);
            old.IsVisible = false;

            neu.Opacity = 0; neu.IsVisible = true;
            var fadeIn = new Animation
            {
                Duration = dur, Easing = new CubicEaseOut(), FillMode = FillMode.Forward,
                Children = {
                    new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(OpacityProperty, 0d) } },
                    new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(OpacityProperty, 1d) } },
                }
            };
            await fadeIn.RunAsync(neu);
            neu.Opacity = 1;
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // Kept as a no-op — earlier iterations tried fading the sidebar (whole thing,
    // then just the content, then just the acrylic) on browse mode, and every
    // variant either killed the frosted glass or read as an empty block. Keeping
    // the sidebar fully visible in both modes is what actually looks right.
    private System.Threading.Tasks.Task AnimateSidebarForMode(bool hide)
        => System.Threading.Tasks.Task.CompletedTask;

    // ---- Modrinth search + install ----
    private readonly ObservableCollection<ModHitVm> _mods = new();
    private readonly Dictionary<string, Bitmap> _iconCache = new();
    private DispatcherTimer? _modSearchDebounce;
    private CancellationTokenSource? _searchCts;

    private void InitModsPage()
    {
        if (ModList.ItemsSource is not null) return;
        // Row template built in code — matches the sidebar/menu hand-built pattern.
        ModList.ItemTemplate = new FuncDataTemplate<ModHitVm>((vm, _) =>
        {
            var iconTile = new Border
            {
                Width = 40, Height = 40, CornerRadius = new CornerRadius(10),
                Background = new SolidColorBrush(Color.Parse("#22000000")),
                ClipToBounds = true,
            };
            var img = new Image { Stretch = Stretch.UniformToFill };
            img.Bind(Image.SourceProperty, new Avalonia.Data.Binding(nameof(ModHitVm.Icon)));
            iconTile.Child = img;
            Grid.SetColumn(iconTile, 0);

            var title = new TextBlock { FontSize = 13.5, FontWeight = FontWeight.SemiBold };
            title.Bind(TextBlock.TextProperty, new Avalonia.Data.Binding(nameof(ModHitVm.Title)));
            title.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextMain"));
            var sub = new TextBlock { FontSize = 11, TextTrimming = TextTrimming.CharacterEllipsis };
            sub.Bind(TextBlock.TextProperty, new Avalonia.Data.Binding(nameof(ModHitVm.Subtitle)));
            sub.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextSub"));
            var text = new StackPanel { VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center };
            text.Children.Add(title); text.Children.Add(sub);
            Grid.SetColumn(text, 1);

            var btn = new Button();
            btn.Classes.Add("mini");
            btn.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
            btn.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
            btn.HorizontalContentAlignment = Avalonia.Layout.HorizontalAlignment.Center;
            btn.VerticalContentAlignment = Avalonia.Layout.VerticalAlignment.Center;
            btn.Padding = new Thickness(0);          // let the fixed 72×28 dictate layout
            btn.Width = 72; btn.Height = 28;
            btn.Bind(ContentControl.ContentProperty, new Avalonia.Data.Binding(nameof(ModHitVm.ButtonLabel)));
            btn.Bind(IsEnabledProperty, new Avalonia.Data.Binding(nameof(ModHitVm.ButtonEnabled)));
            btn.Click += (_, __) =>
            {
                if (btn.DataContext is not ModHitVm h) return;
                // Menu: 0 = current MC version only, 1 = every SupportedVersions entry.
                ShowMenuFor(btn, new[] { "下載", "下載到所有版本" }, -1, pick =>
                {
                    if (pick == 0) _ = DownloadModAsync(h);
                    else           _ = DownloadModAllVersionsAsync(h);
                });
            };

            // Progress ring overlay — sits ON TOP of the button. StrokeDashArray is
            // set so exactly one "dash" spans the full perimeter; StrokeDashOffset
            // (bound to VM.DashOffset) reveals the ring as Progress grows 0→1.
            var ring = new Avalonia.Controls.Shapes.Rectangle
            {
                Width = 72, Height = 28,
                RadiusX = 14, RadiusY = 14,                // match the button's pill shape
                StrokeThickness = 2,
                Fill = null,
                IsHitTestVisible = false,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                StrokeDashArray = new Avalonia.Collections.AvaloniaList<double> { ModHitVm.RingPerimeter, ModHitVm.RingPerimeter },
            };
            ring.Bind(Avalonia.Controls.Shapes.Shape.StrokeProperty, this.GetResourceObservable("Accent"));
            ring.Bind(Avalonia.Controls.Shapes.Shape.StrokeDashOffsetProperty,
                new Avalonia.Data.Binding(nameof(ModHitVm.DashOffset)));
            ring.Bind(Avalonia.Controls.Shapes.Shape.IsVisibleProperty,
                new Avalonia.Data.Binding(nameof(ModHitVm.IsDownloading)));
            ring.Bind(Avalonia.Controls.Shapes.Shape.OpacityProperty,
                new Avalonia.Data.Binding(nameof(ModHitVm.RingOpacity)));

            var btnCell = new Grid();
            btnCell.Children.Add(btn);
            btnCell.Children.Add(ring);
            Grid.SetColumn(btnCell, 2);

            var grid = new Grid
            {
                ColumnDefinitions = new ColumnDefinitions("40,*,Auto"),
                ColumnSpacing = 12,
                Margin = new Thickness(0),
            };
            grid.Children.Add(iconTile);
            grid.Children.Add(text);
            grid.Children.Add(btnCell);
            var row = new Border
            {
                Padding = new Thickness(16, 8),
                CornerRadius = new CornerRadius(10),
                Margin = new Thickness(0, 4),
                // BrushTransition needs a starting brush to interpolate FROM — null
                // (Avalonia's default) skips the animation and snaps. Transparent
                // gives it a real value so hover fades in.
                Background = Avalonia.Media.Brushes.Transparent,
                Child = grid,
            };
            row.Classes.Add("modrow");
            return row;
        }, supportsRecycling: true);
        ModList.ItemsSource = _mods;
        BuildCategoryChips();
    }

    // ---- content-category filter chips (Modrinth facets) ----
    private static readonly (string Label, string? Id)[] ModCategories =
    {
        ("全部", null), ("最佳化", "optimization"), ("工具", "utility"),
        ("冒險", "adventure"), ("裝飾", "decoration"), ("魔法", "magic"),
        ("科技", "technology"), ("世界生成", "worldgen"), ("生物", "mobs"),
        ("儲存", "storage"), ("食物", "food"), ("社交", "social"),
    };
    private string? _modCategory;                       // null = 全部
    private readonly List<Border> _categoryChips = new();

    private void BuildCategoryChips()
    {
        if (_categoryChips.Count > 0) return;           // build once
        foreach (var (label, id) in ModCategories)
        {
            var t = new TextBlock
            {
                Text = label, FontSize = 12.5, FontWeight = FontWeight.SemiBold,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
            };
            var chip = new Border
            {
                Height = 28, Padding = new Thickness(12, 0), CornerRadius = new CornerRadius(10),
                Cursor = new Cursor(StandardCursorType.Hand), Child = t, Tag = id,
            };
            chip.PointerPressed += (_, __) => SelectCategory(id);
            _categoryChips.Add(chip);
            ModCategoryChips.Children.Add(chip);
        }
        StyleCategoryChips();
    }

    private void SelectCategory(string? id)
    {
        if (_modCategory == id) return;
        _modCategory = id;
        StyleCategoryChips();
        _ = RunSearchAsync();
    }

    // Accent fill + white text on the selected chip; muted field otherwise. Re-read
    // the theme brushes each call so an accent/theme change repaints correctly.
    private void StyleCategoryChips()
    {
        IBrush? Res(string k) => this.TryFindResource(k, out var v) ? v as IBrush : null;
        var accent = Res("Accent");
        var field = Res("FieldBg");
        var sub = Res("TextSub");
        foreach (var chip in _categoryChips)
        {
            bool sel = (chip.Tag as string) == _modCategory;
            chip.Background = sel ? accent : field;
            if (chip.Child is TextBlock t) t.Foreground = sel ? Brushes.White : sub;
        }
    }

    private void OnModSearchTextChanged(object? sender, TextChangedEventArgs e)
    {
        if (_hydrating) return;
        if (_modModeIdx == 1)
        {
            // Local filter is a pure in-memory scan — no need to debounce.
            InitLocalBrowsePage();
            ApplyLocalFilter();
            return;
        }
        InitModsPage();
        _modSearchDebounce ??= new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(300) };
        _modSearchDebounce.Stop();
        _modSearchDebounce.Tick -= OnModSearchDebounceTick;
        _modSearchDebounce.Tick += OnModSearchDebounceTick;
        _modSearchDebounce.Start();
    }

    private void OnModSearchDebounceTick(object? sender, EventArgs e)
    {
        _modSearchDebounce?.Stop();
        _ = RunSearchAsync();
    }

    private void OnModSortChanged(object? sender, EventArgs e)
    {
        if (_hydrating) return;
        _ = RunSearchAsync();
    }

    private async System.Threading.Tasks.Task RunSearchAsync()
    {
        InitModsPage();
        var q = (ModSearchBox.Text ?? "").Trim();
        var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? "1.21.1" : VersionBox.SelectedText;
        var loader = (LoaderBox.SelectedText ?? "Fabric").ToLowerInvariant();
        var sort = (ModSortBox.SelectedIndex) switch
        {
            0 => ModSort.Downloads,
            1 => ModSort.Updated,
            _ => ModSort.Relevance,
        };

        // Early out if user's currently-selected MC isn't in Modrinth's tag list.
        if (!ModrinthClient.McSupported(mc))
        {
            _mods.Clear();
            ModStatus.Text = $"Modrinth 沒有 {mc} 的分類 · 切換到 1.21.1 等公開版本再搜尋";
            return;
        }
        ModStatus.Text = q.Length == 0 ? "搜尋熱門模組…" : $"搜尋「{q}」…";

        _searchCts?.Cancel();
        _searchCts = new CancellationTokenSource();
        var ct = _searchCts.Token;
        try
        {
            var hits = await ModrinthClient.SearchAsync(q, mc, loader, sort, limit: 40, offset: 0, ct, category: _modCategory);
            if (ct.IsCancellationRequested) return;
            _mods.Clear();
            foreach (var h in hits)
            {
                var vm = new ModHitVm
                {
                    ProjectId = h.ProjectId,
                    Title = h.Title,
                    Subtitle = FormatSubtitle(h.Author, h.Downloads, h.Description),
                    IconUrl = h.IconUrl,
                    Loaders = h.Loaders,
                };
                // Already downloaded for the currently-selected MC? Offer 更新 (re-fetch
                // the latest release; the old jar is removed so versions don't clash).
                if (_cfg.DownloadedMods.TryGetValue(h.ProjectId, out var mcs) && mcs.Contains(mc))
                {
                    vm.ButtonLabel = "更新";
                    vm.ButtonEnabled = true;
                }
                if (h.IconUrl is not null && _iconCache.TryGetValue(h.IconUrl, out var cached)) vm.Icon = cached;
                _mods.Add(vm);
            }
            ModStatus.Text = hits.Count == 0
                ? (mc == "26.2"
                    ? "26.2 是開發預覽版，Modrinth 尚無對應版本的模組（液態玻璃已內建，無需另裝）"
                    : "沒有結果")
                : $"{hits.Count} 個結果 · {mc} · {char.ToUpper(loader[0]) + loader.Substring(1)}";
            _ = LoadIconsAsync(_mods.ToList(), ct);
        }
        catch (Exception ex) { LogCrash(ex); ModStatus.Text = "搜尋失敗"; }
    }

    private static string FormatDownloads(int n) =>
        n >= 1_000_000 ? $"{n / 1_000_000.0:0.#}M" :
        n >= 1_000 ? $"{n / 1_000.0:0.#}K" : n.ToString();

    private static string FormatSubtitle(string author, int downloads, string desc)
    {
        return string.IsNullOrEmpty(author) ? "" : $"by {author}";
    }

    private async System.Threading.Tasks.Task LoadIconsAsync(List<ModHitVm> rows, CancellationToken ct)
    {
        await Parallel.ForEachAsync(rows,
            new ParallelOptions { MaxDegreeOfParallelism = 6, CancellationToken = ct },
            async (row, tk) =>
            {
                if (row.Icon is not null || row.IconUrl is null) return;
                var bytes = await ModrinthClient.GetIconBytesAsync(row.IconUrl, tk).ConfigureAwait(false);
                if (bytes is null || tk.IsCancellationRequested) return;
                try
                {
                    using var ms = new MemoryStream(bytes);
                    var bmp = new Bitmap(ms);
                    await Dispatcher.UIThread.InvokeAsync(() =>
                    {
                        row.Icon = bmp;
                        if (row.IconUrl is not null) _iconCache[row.IconUrl] = bmp;
                    });
                }
                catch { /* bad PNG → leave the placeholder tile */ }
            });
    }

    // On delete, walk s1mp1e-mods/<mc>/ and drop the projectId entry whose jar
    // matches this filename. No filename→projectId map, so we just check whether
    // the file still exists under that MC; if not, remove <mc> from every list.
    private void ForgetDownloadedByFilename(string filename, string mc)
    {
        try
        {
            var perMc = Path.Combine(EffectiveMcDir(), "s1mp1e-mods", mc);
            var stillThere = File.Exists(Path.Combine(perMc, filename));
            if (stillThere) return;
            var toClean = _cfg.DownloadedMods.Keys.ToArray();
            bool changed = false;
            foreach (var pid in toClean)
            {
                var list = _cfg.DownloadedMods[pid];
                // If the mc entry exists but the file no longer does, drop it.
                if (list.Remove(mc)) changed = true;
                if (list.Count == 0) { _cfg.DownloadedMods.Remove(pid); changed = true; }
            }
            if (changed) SaveCfg();
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // ---- launcher auto-update ----
    private UpdateInfo? _pendingUpdate;

    // Query GitHub for a newer release; reveal the bottom banner if one exists.
    private async void CheckForUpdatesAsync()
    {
        try
        {
            var info = await UpdateChecker.CheckAsync();
            if (info is null) return;
            _pendingUpdate = info;
            Dispatcher.UIThread.Post(() =>
            {
                UpdateBannerText.Text = $"有新版本 v{info.Version}";
                if (!string.IsNullOrWhiteSpace(info.Notes)) ToolTip.SetTip(UpdateBanner, info.Notes);
                UpdateBanner.IsVisible = true;
            });
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // "更新": download the installer and run it, then quit so it can replace files
    // in place and relaunch. No installer asset → just open the release page.
    private async void OnUpdateClick(object? sender, RoutedEventArgs e)
    {
        var info = _pendingUpdate;
        if (info is null) return;

        if (string.IsNullOrEmpty(info.SetupUrl))
        {
            try
            {
                System.Diagnostics.Process.Start(
                    new System.Diagnostics.ProcessStartInfo(info.HtmlUrl) { UseShellExecute = true });
            }
            catch (Exception ex) { LogCrash(ex); }
            return;
        }

        UpdateBannerBtn.IsEnabled = false;
        UpdateBannerText.Text = $"下載更新 v{info.Version}…";
        try
        {
            var setup = await UpdateChecker.DownloadSetupAsync(info.SetupUrl, info.Version);
            if (setup is null)
            {
                UpdateBannerText.Text = "下載失敗,點更新開啟網頁";
                _pendingUpdate = info with { SetupUrl = null };   // fall back to the page next click
                UpdateBannerBtn.IsEnabled = true;
                return;
            }
            // Silent per-user reinstall over the running app; Inno's Restart Manager
            // closes us, upgrades, then the .iss [Run] entry relaunches. We also quit
            // ourselves so nothing holds the exe locked.
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(setup)
            {
                UseShellExecute = true,
                Arguments = "/SILENT /NOCANCEL",
            });
            (Application.Current?.ApplicationLifetime
                as Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime)?.Shutdown();
        }
        catch (Exception ex)
        {
            LogCrash(ex);
            UpdateBannerText.Text = "更新失敗";
            UpdateBannerBtn.IsEnabled = true;
        }
    }

    private void OnUpdateDismiss(object? sender, PointerPressedEventArgs e)
        => UpdateBanner.IsVisible = false;

    // 關於頁「檢查更新」— manual check with inline feedback on the label itself.
    private async void OnCheckUpdateClick(object? sender, PointerPressedEventArgs e)
    {
        CheckUpdateBtn.Text = "檢查中…";
        try
        {
            var info = await UpdateChecker.CheckAsync();
            if (info is null) { CheckUpdateBtn.Text = "已是最新"; return; }
            _pendingUpdate = info;
            CheckUpdateBtn.Text = $"有新版 v{info.Version}";
            UpdateBannerText.Text = $"有新版本 v{info.Version}";
            if (!string.IsNullOrWhiteSpace(info.Notes)) ToolTip.SetTip(UpdateBanner, info.Notes);
            UpdateBanner.IsVisible = true;
        }
        catch (Exception ex) { LogCrash(ex); CheckUpdateBtn.Text = "檢查失敗"; }
    }

    // The current version's per-instance game directory (.minecraft/instances/<mc>).
    private string CurrentInstanceDir()
    {
        var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? _cfg.Settings.Version : VersionBox.SelectedText;
        return System.IO.Path.Combine(EffectiveMcDir(), "instances", mc);
    }

    private void OnOpenGameFolder(object? sender, PointerPressedEventArgs e)
    {
        try
        {
            var dir = CurrentInstanceDir();
            System.IO.Directory.CreateDirectory(dir);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(dir) { UseShellExecute = true });
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    private void OnOpenLog(object? sender, PointerPressedEventArgs e)
    {
        try
        {
            var logs = System.IO.Path.Combine(CurrentInstanceDir(), "logs");
            var latest = System.IO.Path.Combine(logs, "latest.log");
            // Open latest.log directly if it exists; otherwise open the logs folder
            // (nothing has launched this version yet), creating it so Explorer opens.
            var target = System.IO.File.Exists(latest) ? latest : logs;
            System.IO.Directory.CreateDirectory(logs);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(target) { UseShellExecute = true });
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // Show 全部更新 only when the current MC actually has downloaded mods; label the count.
    private void UpdateAllButtonState()
    {
        try
        {
            if (UpdateAllBtn is null) return;
            var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? _cfg.Settings.Version : VersionBox.SelectedText;
            int n = _cfg.DownloadedMods.Count(kv => kv.Value.Contains(mc));
            UpdateAllLabel.Text = $"全部更新 · {n}";
            UpdateAllBtn.IsVisible = n > 0;
        }
        catch { }
    }

    // Re-fetch the latest Modrinth build of every mod downloaded for the current MC.
    // DownloadOneVersionAsync re-resolves the newest file and deletes the stale jar,
    // so this is a true "update all" — same path a single row's 更新 button takes.
    private async void OnUpdateAllMods(object? sender, PointerPressedEventArgs e)
    {
        var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? _cfg.Settings.Version : VersionBox.SelectedText;
        var loader = EffectiveLoader();
        var ids = _cfg.DownloadedMods.Where(kv => kv.Value.Contains(mc)).Select(kv => kv.Key).ToList();
        if (ids.Count == 0) return;
        UpdateAllBtn.IsHitTestVisible = false;
        int done = 0, ok = 0;
        foreach (var pid in ids)
        {
            done++;
            UpdateAllLabel.Text = $"更新中 {done}/{ids.Count}";
            try { if (await DownloadOneVersionAsync(pid, mc, loader)) ok++; }
            catch (Exception ex) { LogCrash(ex); }
        }
        UpdateAllLabel.Text = $"已更新 {ok}/{ids.Count}";
        await Task.Delay(1600);
        UpdateAllBtn.IsHitTestVisible = true;
        UpdateAllButtonState();
        if (_modModeIdx == 1) _ = RunLocalScanAsync();
    }

    private string EffectiveMcDir() =>
        string.IsNullOrEmpty(_cfg.Settings.McPath)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft")
            : _cfg.Settings.McPath;

    // Download one mod into s1mp1e-mods/<mc>/, THEN recursively pull its REQUIRED
    // Modrinth dependencies (Fabric API, prerequisite libraries). Returns true on
    // success. Reports the PRIMARY file's progress via <paramref name="onProgress"/>;
    // caller drives UI text.
    private Task<bool> DownloadOneVersionAsync(string projectId, string mc, string loader, Action<double>? onProgress = null)
        => DownloadWithDepsAsync(projectId, mc, loader, onProgress,
                                 new HashSet<string>(StringComparer.OrdinalIgnoreCase), 0);

    // The recursive worker. Downloads projectId's primary file, records bookkeeping,
    // then best-effort downloads every REQUIRED dependency so the mod actually loads
    // in-game (a mod that needs Fabric API otherwise fails silently on a missing dep —
    // the exact class of bug the glass mod hit). `visited` guards cycles/duplicates;
    // `depth` caps the transitive chain. A dep that can't be resolved never fails the
    // primary download — it's logged and skipped.
    private async Task<bool> DownloadWithDepsAsync(string projectId, string mc, string loader,
        Action<double>? onProgress, HashSet<string> visited, int depth)
    {
        if (!visited.Add(projectId)) return true;   // already handled in this chain
        var ver = await ModrinthClient.ResolvePrimaryVersionAsync(projectId, mc, loader);
        if (ver is null || ver.Files.Length == 0) return false;
        var file = Array.Find(ver.Files, f => f.Primary) ?? ver.Files[0];
        var dstDir = Path.Combine(EffectiveMcDir(), "s1mp1e-mods", mc);
        Directory.CreateDirectory(dstDir);
        var dst = Path.Combine(dstDir, file.Filename);
        // Update path: if we previously wrote a DIFFERENT filename for this mod+MC,
        // delete that stale jar first so the new version doesn't double-load alongside it.
        var fileKey = $"{projectId}@{mc}";
        if (_cfg.DownloadedModFiles.TryGetValue(fileKey, out var oldName)
            && !string.Equals(oldName, file.Filename, StringComparison.OrdinalIgnoreCase))
        {
            var oldEnabled = Path.Combine(dstDir, oldName);
            try { if (File.Exists(oldEnabled)) File.Delete(oldEnabled); } catch { }
            try { if (File.Exists(oldEnabled + ".disabled")) File.Delete(oldEnabled + ".disabled"); } catch { }
        }
        var prog = onProgress is null ? null : new Progress<double>(onProgress);
        await ModrinthClient.DownloadFileAsync(file.Url, dst, prog);
        // Bookkeeping: remember what we've downloaded so the search can render
        // rows as "已下載"/"更新" up-front (and Fabric doesn't double-load duplicates).
        if (!_cfg.DownloadedMods.TryGetValue(projectId, out var mcs))
            _cfg.DownloadedMods[projectId] = mcs = new List<string>();
        if (!mcs.Contains(mc)) mcs.Add(mc);
        _cfg.DownloadedModFiles[fileKey] = file.Filename;
        SaveCfg();

        // Pull required dependencies (Fabric API, libraries) so the mod loads.
        if (depth < 4)
        {
            foreach (var dep in ver.Dependencies)
            {
                if (!string.Equals(dep.DependencyType, "required", StringComparison.OrdinalIgnoreCase)) continue;
                if (string.IsNullOrEmpty(dep.ProjectId)) continue;               // version-pinned deps unsupported
                if (visited.Contains(dep.ProjectId)) continue;
                if (_cfg.DownloadedMods.TryGetValue(dep.ProjectId, out var have) && have.Contains(mc))
                {
                    visited.Add(dep.ProjectId);                                   // already installed for this MC
                    continue;
                }
                try { await DownloadWithDepsAsync(dep.ProjectId, mc, loader, null, visited, depth + 1); }
                catch (Exception ex) { LogCrash(ex); }                           // best-effort: never fail the primary
            }
        }
        return true;
    }

    // Does this mod support the given loader? An empty Loaders list means we
    // couldn't determine it from search metadata, so we don't block — let the
    // resolve step be the final arbiter.
    private static bool ModSupportsLoader(ModHitVm row, string loader)
        => row.Loaders.Length == 0
        || row.Loaders.Any(l => string.Equals(l, loader, StringComparison.OrdinalIgnoreCase));

    private async System.Threading.Tasks.Task DownloadModAsync(ModHitVm row)
    {
        row.ButtonEnabled = false;
        var mc = string.IsNullOrEmpty(VersionBox.SelectedText) ? "1.21.1" : VersionBox.SelectedText;
        var loader = (LoaderBox.SelectedText ?? "Fabric").ToLowerInvariant();
        if (!ModSupportsLoader(row, loader))
        {
            var supported = row.Loaders.Length == 0 ? "?" : string.Join("/", row.Loaders);
            row.ButtonLabel = $"非 {supported.ToUpperInvariant()} 模組";
            row.ButtonEnabled = true;
            return;
        }
        // Kick off the progress ring — label stays "下載" so the ring reads as
        // the whole feedback surface until it's full.
        row.RingOpacity = 1;
        row.Progress = 0;
        row.IsDownloading = true;
        try
        {
            var ok = await DownloadOneVersionAsync(row.ProjectId, mc, loader,
                p => row.Progress = p);
            if (ok)
            {
                row.Progress = 1;                // guarantee the ring closes fully
                await FadeRingOutAsync(row);
                row.ButtonLabel = "更新";        // stays clickable → re-fetch latest anytime
                row.ButtonEnabled = true;
                UpdateAllButtonState();          // reveal/count 全部更新 without needing a page re-nav
            }
            else
            {
                row.IsDownloading = false;
                row.ButtonLabel = "無相容版本";
                row.ButtonEnabled = true;
            }
        }
        catch (Exception ex)
        {
            LogCrash(ex);
            row.IsDownloading = false;
            row.ButtonLabel = "重試";
            row.ButtonEnabled = true;
        }
    }

    // Fade the progress ring to 0 opacity after completion, then hide it. Runs on
    // the UI dispatcher — VM property setters trip Avalonia bindings immediately.
    private static async System.Threading.Tasks.Task FadeRingOutAsync(ModHitVm row)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        const double dur = 320;
        while (sw.ElapsedMilliseconds < dur)
        {
            var t = sw.ElapsedMilliseconds / dur;
            row.RingOpacity = 1 - t;             // linear fade — subtle
            await System.Threading.Tasks.Task.Delay(16);
        }
        row.RingOpacity = 0;
        row.IsDownloading = false;
    }

    // "下載到所有版本" — for each supported MC, download the build for THAT version's
    // one launch loader (Forge for 1.8.9/1.12.2, Fabric otherwise — DefaultLoader).
    // Previously this iterated the mod's own declared loaders and took the first that
    // resolved, so a mod with only a Forge build for, say, 1.14.4 dropped a Forge jar
    // into a Fabric version's folder — which the launcher then fed to Fabric addMods
    // and crashed. Downloading strictly the version's loader keeps each per-MC folder
    // single-loader at the source.
    private async System.Threading.Tasks.Task DownloadModAllVersionsAsync(ModHitVm row)
    {
        row.ButtonEnabled = false;
        var targets = SupportedVersions.Where(ModrinthClient.McSupported).ToArray();
        int ok = 0, done = 0;
        try
        {
            foreach (var mc in targets)
            {
                done++;
                row.ButtonLabel = $"{done}/{targets.Length}";
                var loader = DefaultLoader(mc).ToLowerInvariant();
                // Skip versions this mod has no build for on their loader — no point
                // resolving, and it avoids a misleading failure count.
                if (row.Loaders.Length > 0 &&
                    !row.Loaders.Any(l => string.Equals(l, loader, StringComparison.OrdinalIgnoreCase)))
                    continue;
                try
                {
                    if (await DownloadOneVersionAsync(row.ProjectId, mc, loader)) ok++;
                }
                catch (Exception ex) { LogCrash(ex); }
            }
            row.ButtonLabel = ok == 0 ? "全部失敗" : $"{ok} 版已下載";
            row.ButtonEnabled = ok == 0;
            UpdateAllButtonState();
        }
        catch (Exception ex) { LogCrash(ex); row.ButtonLabel = "重試"; row.ButtonEnabled = true; }
    }

    // ---- Local mods browse pane ----
    private readonly ObservableCollection<LocalModVm> _localMods = new();
    private List<LocalModVm> _localModsAll = new();   // pre-filter cache, for the text filter
    private FileSystemWatcher? _modsWatcher;
    private DispatcherTimer? _modsRescanDebounce;

    // Watch the mods folders so a jar dropped in / renamed / deleted while the
    // browse pane is open shows up without the user having to switch tabs. Debounce
    // rescan to a single call ≥250ms after the last filesystem event — big archives
    // land in bursts of hundreds of events.
    private void EnsureModsWatcher()
    {
        var mcDir = EffectiveMcDir();
        var root = Path.Combine(mcDir, "s1mp1e-mods");
        try { Directory.CreateDirectory(root); } catch { }
        if (_modsWatcher != null && _modsWatcher.Path == root) return;
        _modsWatcher?.Dispose();
        try
        {
            _modsWatcher = new FileSystemWatcher(root)
            {
                IncludeSubdirectories = true,
                NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite | NotifyFilters.Size,
                EnableRaisingEvents = true,
            };
            FileSystemEventHandler bump = (_, _) =>
                Dispatcher.UIThread.Post(() =>
                {
                    _modsRescanDebounce ??= new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
                    _modsRescanDebounce.Stop();
                    _modsRescanDebounce.Tick -= OnModsRescanTick;
                    _modsRescanDebounce.Tick += OnModsRescanTick;
                    _modsRescanDebounce.Start();
                });
            _modsWatcher.Created += bump;
            _modsWatcher.Changed += bump;
            _modsWatcher.Deleted += bump;
            _modsWatcher.Renamed += (_, _) => bump(null!, null!);
        }
        catch (Exception ex) { LogCrash(ex); }
    }
    private void OnModsRescanTick(object? sender, EventArgs e)
    {
        _modsRescanDebounce?.Stop();
        if (_modModeIdx == 1) _ = RunLocalScanAsync();
    }

    private void InitLocalBrowsePage()
    {
        if (LocalModList.ItemsSource is not null) return;
        LocalModList.ItemTemplate = new FuncDataTemplate<LocalModVm>((vm, ns) =>
        {
            // [ Icon 40x40 ]   Name            [ Toggle ]
            var iconTile = new Border
            {
                Width = 40, Height = 40, CornerRadius = new CornerRadius(10),
                ClipToBounds = true,
                Background = new SolidColorBrush(Color.Parse("#22000000")),
            };
            var img = new Image { Stretch = Stretch.UniformToFill };
            img.Bind(Image.SourceProperty, new Avalonia.Data.Binding(nameof(LocalModVm.Icon)));
            iconTile.Child = img;
            Grid.SetColumn(iconTile, 0);

            var name = new TextBlock
            {
                FontSize = 14, FontWeight = FontWeight.SemiBold,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
            };
            name.Bind(TextBlock.TextProperty, new Avalonia.Data.Binding(nameof(LocalModVm.Name)));
            name.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextMain"));
            Grid.SetColumn(name, 1);

            // Delete button — trash-can glyph, red on hover. Sits to the LEFT of the
            // enable toggle. Uses a Path (not an Image) so it inherits the theme's
            // accent/danger colour and doesn't need a bundled asset.
            var trashPath = new Avalonia.Controls.Shapes.Path
            {
                Width = 14, Height = 15, Stretch = Stretch.Uniform,
                Fill = new SolidColorBrush(Color.Parse("#FF453A")),
                Data = Geometry.Parse("M4 3 L4 1.5 A0.5 0.5 0 0 1 4.5 1 L9.5 1 A0.5 0.5 0 0 1 10 1.5 L10 3 L13 3 L13 4 L1 4 L1 3 Z M2 5 L12 5 L11 14 A1 1 0 0 1 10 15 L4 15 A1 1 0 0 1 3 14 Z"),
            };
            var delBtn = new Button
            {
                Padding = new Thickness(8),
                Background = Brushes.Transparent,
                BorderThickness = new Thickness(0),
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                Content = trashPath,
                Cursor = new Cursor(StandardCursorType.Hand),
            };
            delBtn.Click += (_, __) =>
            {
                if (delBtn.DataContext is not LocalModVm m) return;
                try
                {
                    if (System.IO.File.Exists(m.JarPath)) System.IO.File.Delete(m.JarPath);
                    _localModsAll.Remove(m);
                    _localMods.Remove(m);
                    // Also drop the projectId → mc mapping for the current MC so
                    // the Modrinth search stops rendering the row as 已下載.
                    var curMc = string.IsNullOrEmpty(_cfg.Settings.Version) ? "1.21.1" : _cfg.Settings.Version;
                    ForgetDownloadedByFilename(Path.GetFileName(m.JarPath), curMc);
                }
                catch (Exception ex) { LogCrash(ex); }
            };
            Grid.SetColumn(delBtn, 2);

            var toggle = new CheckBox
            {
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
                Margin = new Thickness(8, 0, 0, 0),
            };
            toggle.Bind(ToggleButton.IsCheckedProperty,
                new Avalonia.Data.Binding(nameof(LocalModVm.Enabled)) { Mode = BindingMode.TwoWay });
            // Rename the jar on the disk when the checkbox flips.
            toggle.IsCheckedChanged += (s, __) =>
            {
                if (toggle.DataContext is LocalModVm m)
                {
                    try { m.JarPath = LocalModScanner.SetEnabled(m.JarPath, toggle.IsChecked == true); }
                    catch (Exception ex) { LogCrash(ex); }
                }
            };
            Grid.SetColumn(toggle, 3);

            var grid = new Grid
            {
                ColumnDefinitions = new ColumnDefinitions("40,*,Auto,Auto"),
                ColumnSpacing = 12,
            };
            grid.Children.Add(iconTile);
            grid.Children.Add(name);
            grid.Children.Add(delBtn);
            grid.Children.Add(toggle);

            // The row itself opens the detail sheet — clicking the checkbox is filtered
            // out below so a toggle-flip doesn't also pop the sheet.
            var row = new Border
            {
                Padding = new Thickness(12, 12),
                CornerRadius = new CornerRadius(10),
                Margin = new Thickness(0, 4),
                Background = Avalonia.Media.Brushes.Transparent,
                Cursor = new Cursor(StandardCursorType.Hand),
                Child = grid,
            };
            row.Classes.Add("modrow");
            row.PointerPressed += (s, ev) =>
            {
                var src = ev.Source as Visual;
                // Bubble-up guard: if the click originated inside the checkbox, don't
                // open the sheet (user is toggling, not inspecting).
                while (src != null && src != row)
                {
                    if (src is CheckBox || src is Button) return;
                    src = src.GetVisualParent();
                }
                if (row.DataContext is LocalModVm m)
                {
                    // Origin for the droplet-morph = row's BOTTOM-CENTRE, so the
                    // sheet expands downward from directly under the mod row.
                    var pt = row.TranslatePoint(new Point(row.Bounds.Width / 2, row.Bounds.Height), OverlayHost)
                             ?? new Point(OverlayHost.Bounds.Width / 2, OverlayHost.Bounds.Height / 2);
                    _ = ShowLocalModDetailAsync(m, pt);
                }
            };
            return row;
        }, supportsRecycling: true);
        LocalModList.ItemsSource = _localMods;
    }

    private async System.Threading.Tasks.Task RunLocalScanAsync()
    {
        InitLocalBrowsePage();
        EnsureModsWatcher();
        var mcDir = string.IsNullOrEmpty(_cfg.Settings.McPath)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".minecraft")
            : _cfg.Settings.McPath;
        var mcVer = string.IsNullOrEmpty(_cfg.Settings.Version) ? "1.21.1" : _cfg.Settings.Version;
        LocalModStatus.Text = "掃描中…";
        try
        {
            var mods = await Task.Run(() => LocalModScanner.ScanAsync(mcDir, mcVer));

            // Self-correct mod conflicts: auto-disable duplicate mod-ids that would
            // hard-crash the game (keep newest, rename the rest to .disabled); the
            // ambiguous ones become a warning. Re-scan once if anything was disabled
            // so the list reflects the new state.
            ConflictResult? conflicts = null;
            try
            {
                var launchDir = ConflictScanner.LaunchModDir(mcDir, mcVer);
                conflicts = await Task.Run(() => ConflictScanner.DetectAndFix(mods, launchDir));
                if (conflicts.AutoDisabled > 0)
                    mods = await Task.Run(() => LocalModScanner.ScanAsync(mcDir, mcVer));
            }
            catch (Exception ex) { LogCrash(ex); }

            _localModsAll = new List<LocalModVm>(mods.Count);
            foreach (var m in mods)
            {
                Bitmap? bmp = null;
                if (m.IconBytes is not null)
                {
                    try { using var ms = new MemoryStream(m.IconBytes); bmp = new Bitmap(ms); }
                    catch { /* bad icon */ }
                }
                _localModsAll.Add(new LocalModVm
                {
                    JarPath = m.JarPath,
                    Id = m.Id,
                    Name = m.Name,
                    Description = m.Description,
                    Icon = bmp,
                    Enabled = m.Enabled,
                });
            }
            ApplyLocalFilter();
            var enabled = _localModsAll.Count(m => m.Enabled);
            LocalModStatus.Text = _localModsAll.Count == 0
                ? "沒有偵測到本地模組 (mods/*.jar)"
                : $"{_localModsAll.Count} 個模組 · {enabled} 個啟用";
            if (conflicts is not null && (conflicts.AutoDisabled > 0 || conflicts.Warnings.Count > 0))
            {
                var parts = new List<string>();
                if (conflicts.AutoDisabled > 0)
                    parts.Add($"已自動停用 {conflicts.AutoDisabled} 個重複({string.Join("、", conflicts.Fixed)})");
                if (conflicts.Warnings.Count > 0)
                    parts.Add($"{conflicts.Warnings.Count} 個衝突需手動選:{string.Join("、", conflicts.Warnings)}");
                LocalModStatus.Text += "  ⚠ " + string.Join(" · ", parts);
            }
        }
        catch (Exception ex) { LogCrash(ex); LocalModStatus.Text = "掃描失敗"; }
    }

    private void ApplyLocalFilter()
    {
        _localMods.Clear();
        var q = (ModSearchBox.Text ?? "").Trim();
        IEnumerable<LocalModVm> src = _localModsAll;
        if (q.Length > 0)
        {
            src = _localModsAll.Where(m =>
                m.Name.Contains(q, StringComparison.OrdinalIgnoreCase) ||
                m.Id.Contains(q, StringComparison.OrdinalIgnoreCase));
        }
        foreach (var m in src) _localMods.Add(m);
    }

    // Remembered geometry so CloseGlassMenu can shrink the sheet BACK to the
    // origin point (reverse droplet-morph), not just fade it out in place.
    private Point _sheetOrigin;
    private double _sheetTargetL, _sheetTargetT, _sheetTargetW, _sheetTargetH;

    // Reverse of ShowLocalModDetailAsync's morph — shrink+fade back to the origin
    // blob. Same keyframe ratios so open and close feel like the same gesture in
    // reverse.
    private async System.Threading.Tasks.Task CollapseSheetToOriginAsync(
        Control sheet, Point origin, double tL, double tT, double tW, double tH)
    {
        double smallW = Math.Min(tW * 0.30, 160);
        double smallH = Math.Max(tH * 0.22, 40);
        double smallL = Math.Max(0, Math.Min(OverlayHost.Bounds.Width  - smallW, origin.X - smallW / 2));
        double smallT = Math.Max(0, Math.Min(OverlayHost.Bounds.Height - smallH, origin.Y - smallH / 2));

        var collapse = new Animation
        {
            Duration = TimeSpan.FromMilliseconds(240),
            Easing = new CubicEaseIn(),
            FillMode = FillMode.Forward,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = {
                    new Setter(Canvas.LeftProperty, tL),
                    new Setter(Canvas.TopProperty,  tT),
                    new Setter(WidthProperty,  tW),
                    new Setter(HeightProperty, tH),
                    new Setter(OpacityProperty, 1d) } },
                new KeyFrame { Cue = new Cue(0.5d), Setters = {
                    new Setter(OpacityProperty, 0.5d) } },
                new KeyFrame { Cue = new Cue(1d), Setters = {
                    new Setter(Canvas.LeftProperty, smallL),
                    new Setter(Canvas.TopProperty,  smallT),
                    new Setter(WidthProperty,  smallW),
                    new Setter(HeightProperty, smallH),
                    new Setter(OpacityProperty, 0d) } },
            }
        };
        await collapse.RunAsync(sheet);
        sheet.IsVisible = false;
    }

    private async System.Threading.Tasks.Task ShowLocalModDetailAsync(LocalModVm m, Point origin)
    {
        try
        {
            SheetTitle.Text = m.Name;
            SheetIcon.Source = m.Icon;
            SheetDesc.Text = string.IsNullOrWhiteSpace(m.Description) ? "(此模組沒有提供介紹)" : "翻譯中…";

            OverlayHost.IsVisible = true;
            MenuRoot.IsVisible = false;
            SheetRoot.IsVisible = true;

            // Target (settled) rect — centred in the overlay. Fixed sheet size
            // (Measure was returning stale/tiny heights during animation, so the
            // description ScrollViewer collapsed to zero). Remember origin/target
            // so CloseGlassMenu can reverse-morph back to origin.
            const double targetW = 460;
            const double targetH = 300;
            double targetL = (OverlayHost.Bounds.Width  - targetW) / 2;
            double targetT = (OverlayHost.Bounds.Height - targetH) / 2;
            _sheetOrigin = origin;
            _sheetTargetL = targetL; _sheetTargetT = targetT;
            _sheetTargetW = targetW; _sheetTargetH = targetH;

            // Droplet-morph start: small blob at the click origin. Same "22% height,
            // 70% width" ratio the menu uses so the two popup styles feel consistent.
            double smallW = Math.Min(targetW * 0.30, 160);
            double smallH = Math.Max(targetH * 0.22, 40);
            double smallL = Math.Max(0, Math.Min(OverlayHost.Bounds.Width  - smallW, origin.X - smallW / 2));
            double smallT = Math.Max(0, Math.Min(OverlayHost.Bounds.Height - smallH, origin.Y - smallH / 2));
            // Mild overshoot at 66% — 2% bigger than settled, matches AnimateMenu.
            double overW = targetW * 1.02, overH = targetH * 1.02;
            double overL = targetL - (overW - targetW) / 2;
            double overT = targetT - (overH - targetH) / 2;

            SheetRoot.Width  = smallW; SheetRoot.Height = smallH;
            Canvas.SetLeft(SheetRoot, smallL);
            Canvas.SetTop (SheetRoot, smallT);
            SheetRoot.Opacity = 0;

            var morph = new Animation
            {
                Duration = TimeSpan.FromMilliseconds(320),
                Easing = new CubicEaseOut(), FillMode = FillMode.Forward,
                Children =
                {
                    new KeyFrame { Cue = new Cue(0d), Setters = {
                        new Setter(Canvas.LeftProperty, smallL),
                        new Setter(Canvas.TopProperty,  smallT),
                        new Setter(WidthProperty,  smallW),
                        new Setter(HeightProperty, smallH),
                        new Setter(OpacityProperty, 0d) } },
                    new KeyFrame { Cue = new Cue(0.22d), Setters = {
                        new Setter(OpacityProperty, 0.6d) } },
                    new KeyFrame { Cue = new Cue(0.5d), Setters = {
                        new Setter(OpacityProperty, 1d) } },
                    new KeyFrame { Cue = new Cue(0.66d), Setters = {
                        new Setter(Canvas.LeftProperty, overL),
                        new Setter(Canvas.TopProperty,  overT),
                        new Setter(WidthProperty,  overW),
                        new Setter(HeightProperty, overH) } },
                    new KeyFrame { Cue = new Cue(1d), Setters = {
                        new Setter(Canvas.LeftProperty, targetL),
                        new Setter(Canvas.TopProperty,  targetT),
                        new Setter(WidthProperty,  targetW),
                        new Setter(HeightProperty, targetH),
                        new Setter(OpacityProperty, 1d) } },
                }
            };
            _ = morph.RunAsync(SheetRoot);

            if (!string.IsNullOrWhiteSpace(m.Description))
            {
                var zh = await TranslateClient.ToZhTwAsync(m.Description);
                SheetDesc.Text = zh;
            }
        }
        catch (Exception ex) { LogCrash(ex); OverlayHost.IsVisible = false; }
    }

    // Open Microsoft's guide for creating an Azure Public-Client app in the user's browser.
    private void OnOpenAzureHelp(object? sender, PointerPressedEventArgs e)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(
                "https://learn.microsoft.com/entra/identity-platform/quickstart-register-app")
            { UseShellExecute = true });
        }
        catch { }
    }

    // ---- sidebar search: filter nav rows by label ----
    private void OnSearchChanged(object? sender, TextChangedEventArgs e)
    {
        var q = (SearchBox.Text ?? string.Empty).Trim();
        foreach (var row in NavRows.Children.OfType<Border>())
        {
            var label = row.GetVisualDescendants().OfType<TextBlock>().FirstOrDefault();
            var text = label?.Text ?? string.Empty;
            row.IsVisible = q.Length == 0 || text.Contains(q, StringComparison.OrdinalIgnoreCase);
        }
        // the pill is index-positioned; hide it while rows shift under a filter.
        SelPill.IsVisible = q.Length == 0;
    }

    // Bold the selected nav row's primary label (secondary selected-state cue beyond the pill).
    private void UpdateNavWeights(int selected)
    {
        var rows = NavRows.Children.OfType<Border>().ToList();
        for (int i = 0; i < rows.Count; i++)
        {
            var label = rows[i].GetVisualDescendants().OfType<TextBlock>().FirstOrDefault();
            if (label is not null)
                label.FontWeight = i == selected ? FontWeight.SemiBold : FontWeight.Normal;
        }
    }

    // Sidebar hover highlight — fades in on the hovered nav row, slides between rows
    // while the pointer stays in the bar, fades out when it leaves (Minecraft-inventory
    // feel). When appearing fresh it snaps to the row (no slide-from-last-position); when
    // moving between rows it animates via the Canvas.Top transition.
    private void WireSidebarHover()
    {
        foreach (var row in NavRows.Children.OfType<Border>())
        {
            row.PointerEntered += (s, _) =>
            {
                if (s is not Border b || !int.TryParse(b.Tag?.ToString(), out var idx)) return;
                if (HoverPill.Opacity < 0.5)
                {
                    var tr = HoverPill.Transitions;      // appearing: snap into place, no slide
                    HoverPill.Transitions = null;
                    Canvas.SetTop(HoverPill, idx * RowStride);
                    HoverPill.Transitions = tr;
                }
                else
                {
                    Canvas.SetTop(HoverPill, idx * RowStride);   // moving between rows: slide
                }
                HoverPill.Opacity = 1;
            };
        }
        NavRows.PointerExited += (_, _) => HoverPill.Opacity = 0;
    }

    private void MovePill(int index, bool animate)
    {
        if (!animate && SelPill.Transitions is { } t)
        {
            // suppress the transition for the very first placement
            SelPill.Transitions = null;
            Canvas.SetTop(SelPill, index * RowStride);
            SelPill.Transitions = t;
            return;
        }
        Canvas.SetTop(SelPill, index * RowStride);
    }
}
