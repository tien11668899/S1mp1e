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
        if (_hydrating || _captureMode) return;   // capture mode never writes the user's config
        // Settings only — never write our in-memory account over what the itest CLI
        // put on disk (see ConfigStore.SaveUiOwned).
        ConfigStore.SaveUiOwned(_cfg);
    }

    public MainWindow()
    {
        InitializeComponent();

        // App / taskbar icon and the sidebar brand mark follow the light / dark theme.
        ApplyBrandIcons();
        ActualThemeVariantChanged += (_, _) => ApplyBrandIcons();

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
            // Sealed frame-capture mode (spec: 60 fps PNG dump). Impossible to trigger without the env var; when set it
            // takes over the window, never writes config/accounts, and exits by itself. Must be FIRST so nothing else runs.
            var shotDir = Environment.GetEnvironmentVariable("S1MP1E_MENUSHOT");
            if (!string.IsNullOrWhiteSpace(shotDir)) { _ = RunCaptureModeAsync(shotDir!); return; }

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

            // Gap I — Esc dismisses any open pop-up (context menu, pull-down, sheet, gallery) with the SAME close
            // animation as clicking the scrim/outside. Tunnel + handledEventsToo so it fires before a focused row/list
            // swallows the key. No-op when nothing is open.
            AddHandler(InputElement.KeyDownEvent, OnGlobalKeyDown,
                RoutingStrategies.Tunnel | RoutingStrategies.Bubble, handledEventsToo: true);

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
        catch (Exception ex) { LogCrash(ex); OverlayHost.IsVisible = false; if (_menuAnchor is not null) RestoreAnchor(_menuAnchor); }
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
            // Fabric on 1.12.2). 1.8.9 allows both (Fabric = Ornithe).
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
        // Two paradigms behind one flag: a GlassSelect value picker is a PULL-DOWN (covers the value, non-modal, no scrim);
        // anything else (account chip, +, skin, download) is an iOS CONTEXT MENU (emerges beside the anchor, modal scrim).
        bool fades = anchor is S1mp1e.Controls.GlassSelect;
        bool contextMenu = !fades;
        _menuOnPick = onPick; _menuDisabled = disabled;
        if (!_capWired) { _capWired = true; MenuItems.PointerExited += (_, _) => OnMenuPointerExited(); }
        static bool IsDestructive(string s)
        {
            s = s.Trim();
            return s == "登出" || s.StartsWith("刪除") || s.StartsWith("移除") || s.StartsWith("Remove") || s.StartsWith("Delete");
        }
        MenuItems.Children.Clear();
        _menuRows.Clear();
        _menuCellLabels.Clear();
        // Request 3: the old 3-column version GRID is retired. A long pull-down (the version picker, and likewise the
        // menu-key picker) is now a SINGLE vertical column of loader-style rows inside a smooth-scrolling viewport; short
        // menus stay a plain single column. So every row is a left-aligned single-column row now (never centred grid cells).
        int rowsCount = items.Length;
        ResetCapsuleForOpen(fades, selected, grid: false, items.Length);
        Border MakeRow(int i)
        {
            int idx = i;
            bool dis = IsDisabled(i);
            var row = BuildMenuRow(items[i], i == selected, dis, centered: false,
                                   contextMenu: contextMenu, destructive: IsDestructive(items[i]));
            // Hover moves the selection capsule onto this row (spec §10.1); disabled rows are skipped (capsule clamps).
            row.PointerEntered += (_, _) => OnRowHover(idx);
            row.PointerMoved   += (_, _) => OnRowHover(idx);
            row.Tapped += (_, _) =>
            {
                if (dis) return;   // disabled → swallow click, leave menu open
                ShowCapsuleAt(idx, animate: false);   // keep the capsule lit on the clicked row through the collapse (spec §10.7)
                try { onPick(idx); } catch (Exception ex) { LogCrash(ex); }
                CloseGlassMenu();
            };
            // iOS 26 context menu: a slight top-first positional reveal (~1 frame per row, capped); pull-downs stay in lock-step.
            double openDelay = contextMenu ? Math.Min(0.05, 0.012 * i) : 0;
            // context cuts every row together in ~1 frame (iOS spec §4); pull-down keeps a small bottom-first stagger.
            double closeDelay = contextMenu ? 0 : Math.Min(0.008, 0.004 * (rowsCount - 1 - i));
            _menuRows.Add(new MenuRowAnim(row, i == selected ? row.Tag as Control : null, openDelay, closeDelay));
            return row;
        }

        // Measure one row for the pitch, then decide whether this pull-down is tall enough to need scrolling. The viewport is
        // capped to ~8.5 rows (a half row peeking signals more) AND to whatever fits inside the window with a margin. Only
        // pull-downs (fades) ever scroll; context menus and short pickers stay their natural height.
        double rowPitch;
        {
            var probe = BuildMenuRow(items.Length > 0 ? items[0] : "", false, false, centered: false, contextMenu: contextMenu);
            probe.Measure(Size.Infinity);
            rowPitch = Math.Max(1, probe.DesiredSize.Height);
        }
        _menuRowPitch = rowPitch;
        // Panel corner + the concentric capsule inset are both fixed now that the row pitch is known (both depend only on the
        // menu KIND and the pitch). _menuRadius is the nominal panel corner; the panel is DRAWN as a squircle of semi-axis
        // a = 1.31 * _menuRadius (see ApplyMenuFrame), so for the capsule (a full-height stadium, corner = pitch/2) to nest
        // concentrically the uniform gap is a - pitch/2. Applied identically to value pickers and context menus.
        _menuRadius = contextMenu ? CtxCornerRadius : MenuPanelRadius;   // context menus round more (iOS ~0.13*width); pull-downs keep the card corner
        _capInset = Math.Max(4.0, 1.31 * _menuRadius - rowPitch / 2.0);
        _menuScrolling = false;
        double viewportH = 0;
        if (fades)
        {
            double marginsV = 2 * _capInset;               // MenuItems top+bottom padding (symmetric _capInset each side)
            double maxFit = Math.Max(rowPitch, ClientSize.Height - 32 - marginsV);   // always leave a 16 px window margin top+bottom
            double cap = Math.Min(8.5 * rowPitch, maxFit);
            double naturalH = items.Length * rowPitch;
            if (naturalH > cap + 1) { _menuScrolling = true; viewportH = cap; }
        }

        if (_menuScrolling)
        {
            EnsureLazyMenuScroller();
            _menuScrollPanel!.Children.Clear();
            for (int i = 0; i < items.Length; i++) _menuScrollPanel.Children.Add(MakeRow(i));
            _menuScroller!.Height = viewportH;
            _menuScroller.Offset = new Vector(0, 0);
            _menuScrollTarget = double.NaN;
            MenuItems.Children.Add(_menuScroller);
            // Open scrolled so the CURRENT value is visible near the TOP (a slight peek above signals more), but NOT
            // highlighted (Request 2 already keeps the capsule off it). Applied once the extent is known (see below / settle).
            _menuInitScrollY = Math.Max(0, (selected - 0.5) * rowPitch);
        }
        else
        {
            for (int i = 0; i < items.Length; i++) MenuItems.Children.Add(MakeRow(i));
        }

        OverlayHost.IsVisible = true;
        MenuScrim.Opacity = 0;   // the scrim is opaque black shown via Opacity; keep it clear until AnimateMenu fades it in
        // Ensure any previously-opened sheet/gallery is out of the way and the
        // MenuRoot panel is the visible one — ShowLocalModDetailAsync flips
        // MenuRoot to invisible, and if we don't flip it back the next menu opens
        // into an invisible panel.
        MenuRoot.IsVisible = true;
        if (SheetRoot is not null) SheetRoot.IsVisible = false;
        if (SkinGalleryRoot is not null) SkinGalleryRoot.IsVisible = false;
        // Plain dropdown menus don't get the frosted sheet backdrop — they should
        // feel lightweight, not modal.
        ApplyMenuGlassTheme(contextMenu);
        // re-measure from scratch: an earlier menu left its final size pinned on MenuItems
        MenuItems.Width = double.NaN;
        MenuItems.Height = double.NaN;
        MenuItems.Margin = new Thickness(4, _capInset, 4, _capInset);   // top/bottom = the concentric capsule inset (symmetric)
        MenuItems.Measure(Size.Infinity);
        var ds = MenuItems.DesiredSize;
        double h = ds.Height;
        // width follows the content: each row already carries the iOS side padding (check gutter + generous right space);
        // never narrower than the trigger it grows out of
        double w = Math.Max(ds.Width, anchor.Bounds.Width);

        var pr = anchor.TranslatePoint(new Point(anchor.Bounds.Width, anchor.Bounds.Height), OverlayHost)
                 ?? new Point(0, 0);
        var pt = anchor.TranslatePoint(new Point(0, 0), OverlayHost) ?? new Point(0, 0);
        double anchorRight = pr.X - padRight;
        // iOS 26: the menu grows OUT OF the trigger and covers it. Its right edge sits just past the trigger's text edge
        // and its top just above the trigger row, so it grows DOWN and LEFT; when there is no room below it flips and
        // grows UP from the trigger's bottom edge instead (e.g. the sidebar account chip).
        // Two kinds of anchor. A dropdown VALUE (GlassSelect) hands its text to the glass: the menu grows out of it and
        // covers it (right edge +12 past the chevron, top 13 above the value centre — measured on iOS). Anything else
        // (the account chip, the ⊕ button, the skin button) stays visible, so the menu sits clear of it — above or below
        // with a 6 DIP gap — and aligns to the anchor's near edge (left edges for a left-side chip, right edges otherwise).
        // 'fades' was decided at the top of this method. A left-side chip aligns its LEFT edges (biased ~6 pt outward per
        // iOS 26); a right-side context anchor aligns right edges; a pull-down covers the value (+12 past the chevron).
        bool leftSide = !fades && (pt.X + pr.X) / 2 < OverlayHost.Bounds.Width / 2;
        double right;
        if (leftSide)
        {
            // iOS 26: the context menu's left edge sits ~5-7 pt OUTWARD (left) of the anchor's left edge. Allow it nearer the
            // window edge than the usual 8 DIP inset so a near-edge anchor (the account chip at x=12) still gets the full bias.
            const double ctxEdgeMin = 4;
            _menuLeft = Math.Max(ctxEdgeMin, pt.X - CtxLeftBias);
            right = Math.Min(OverlayHost.Bounds.Width - 8, _menuLeft + w);
            _menuLeft = Math.Max(ctxEdgeMin, right - w);
        }
        else
        {
            right = Math.Min(OverlayHost.Bounds.Width - 8, anchorRight + (fades ? 12 : 0));
            _menuLeft = Math.Max(8, right - w);
        }
        double anchorCY = (pt.Y + pr.Y) / 2;
        double topIfDown = fades ? anchorCY - 13 : pr.Y + CtxMenuGap;   // context menu: clear gap below the anchor; pull-down covers it
        double availBelow = OverlayHost.Bounds.Height - topIfDown - 8;
        bool flipUp = availBelow < h + 4;
        _menuTop = flipUp ? Math.Max(8, (fades ? anchorCY + 13 : pt.Y - CtxMenuGap) - h) : Math.Max(8, topIfDown);
        _menuW = w;
        _menuH = h;
        _btnCX = pr.X - anchor.Bounds.Width / 2;
        _btnW = anchor.Bounds.Width;
        _menuFlipUp = flipUp;
        if (fades)
        {
            // Pull-down: the seed = the anchor's own box, lying INSIDE the final rect (top and right barely move) — the value
            // balloons in place. Origin top-right (set in AnimateMenu).
            _seedR = Math.Min(right, anchorRight);
            _seedL = Math.Max(_menuLeft, Math.Min(_seedR - 24, pt.X));
            _seedT = Math.Max(_menuTop, pt.Y);
            _seedB = Math.Min(_menuTop + h, Math.Max(_seedT + 12, pr.Y));
        }
        else
        {
            // Context menu (iOS 26 "emerge from anchor"): the panel is a UNIFORM scale about its pinned corner (top-left when
            // growing down, bottom-left when flipped up) — both axes scale by the SAME factor, the pinned edges never move.
            // Verified by a gradient-edge re-measure of 下拉深 (2).mp4: width% == height% at every open frame (the earlier
            // brightness-diff re-measure's "near-full-width, height-only grow" was a detector artifact where the menu grey
            // equals the scrimmed wallpaper and only the low-contrast top is missed). Seed a small WIDE nub (spec §2:
            // iOS's first frame is a rounded RECT ~0.28 w x 0.12 h, not a point) so the emergence reads as iOS's does and the
            // blob is first-visible ~1 frame in (with the phase-seed in SetupMenuAnim). The floors are small so the diluted
            // overshoot / fitted spring stay within tolerance.
            double seedW = (ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark ? CtxSeedWDark : CtxSeedWLight);
            _seedL = _menuLeft;
            _seedR = _menuLeft + seedW * w;
            if (flipUp) { _seedB = _menuTop + h; _seedT = _seedB - CtxSeedH * h; }   // flipped: pinned bottom-left, grows up
            else        { _seedT = _menuTop;     _seedB = _seedT + CtxSeedH * h; }   // pinned top-left, grows down + right
        }
        // Backdrop scrim floor (spec §7/§13.1): the account switcher is fully modal; the small +/skin/download popups dim
        // lightly; pull-downs are non-modal and get NO scrim (defining difference from the context menu).
        bool darkNow = ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
        _scrimLevel = fades ? 0
            : ReferenceEquals(anchor, AccountChip) ? (darkNow ? 0.53 : 0.22)   // dark floor 1-0.53=0.47 ~= iOS 0.481
            : (darkNow ? 0.16 : 0.13);
        // Keep the pressed anchor lit above the scrim: paint a full-brightness clone into the overlay (spec §6/§13.10).
        if (contextMenu) SetupAnchorClone(anchor, pt, flipUp); else HideAnchorClone();
        // lay the items out at their FINAL size once; the clip reveals them as the glass grows
        // top/bottom padding = _capInset (symmetric both ways of flip) so the top-row and bottom-row selection capsules sit
        // the SAME gap from the panel edge as the left/right sides — the capsule corner stays concentric with the panel's.
        MenuItems.Margin = new Thickness(4, _capInset, 4, _capInset);
        MenuItems.Width = Math.Max(0, w - MenuItems.Margin.Left - MenuItems.Margin.Right);
        MenuItems.Height = Math.Max(0, h - MenuItems.Margin.Top - MenuItems.Margin.Bottom);

        // Scroll pull-down: force the viewport to lay out now (so its extent is known) and open scrolled to the current value
        // near the top. Re-applied at settle too, in case the extent only resolves after the first real layout pass.
        if (_menuScrolling && _menuScroller is not null)
        {
            _menuScroller.UpdateLayout();
            ApplyMenuInitScroll();
        }

        // a dropdown's value text blurs in place and is absorbed by the glass born on top of it
        if (_menuAnchor is not null && !ReferenceEquals(_menuAnchor, anchor)) RestoreAnchor(_menuAnchor);
        _menuAnchor = anchor;
        _anchorFades = anchor is S1mp1e.Controls.GlassSelect;
        _anchorGen++;   // stops a value-return tail still running on this trigger
        // The backdrop below is frozen with ONE capture taken right now — before the trigger's value text has faded — so the
        // glass kept refracting a blurred copy of the trigger's own blue "26.2 ⌃⌄" for the whole morph (the "blue text
        // behind the menu" the user reported). iOS's glass never shows its own trigger's text: keep the trigger out of the
        // capture while the menu owns it; RestoreAnchor / FinishAnchorReturn put it back.
        if (_anchorFades) LiquidGlassAvaloniaUI.LiquidGlassBackdrop.SetIsExcludedFromCapture(anchor, true);

        // Freeze the backdrop for the morph: ONE capture whose clip already covers the FINAL menu (plus its overshoot), then
        // no re-captures while the glass grows — otherwise every growth frame re-rasterises the window (the jank).
        if (TopLevel.GetTopLevel(this) is { } tlv)
        {
            var o = OverlayHost.TranslatePoint(new Point(_menuLeft, _menuTop), tlv) ?? new Point(_menuLeft, _menuTop);
            LiquidGlassAvaloniaUI.LiquidGlassBackdrop.FreezeForAnimation(MenuGlass,
                new Rect(o.X - 0.06 * _menuW, o.Y - (_menuFlipUp ? 0.14 * _menuH : 0), _menuW * 1.12, _menuH * 1.14));
        }
        // In capture mode the harness drives the animation on a virtual clock; don't start the live RAF loop here.
        if (!_captureMode) AnimateMenu(open: true);
    }

    // ---- pressed-anchor clone: a full-brightness copy of the anchor drawn above the scrim so it stays lit while
    // everything else dims, with the iOS long-press lift.
    //
    // DUPLICATE-LAYER FIX: the previous version painted a live VisualBrush copy of the anchor 1.06x / 5 px offset
    // ABOVE the scrim while the REAL anchor stayed visible (dimmed) underneath — the user saw the chip text twice,
    // offset vertically. We now (a) SNAPSHOT the anchor to a static bitmap (a live VisualBrush would vanish the moment
    // its source is hidden, so it can't be used together with hiding the source) and (b) HIDE the live anchor for as
    // long as the lit clone is up. Exactly ONE copy is ever visible: the lit, lifted clone above the dim; on close the
    // live anchor's opacity is restored in HideAnchorClone. ----
    private Control? _clonedAnchor;   // the live anchor hidden while its lit snapshot clone shows

    private void SetupAnchorClone(Control anchor, Point pt, bool flipUp)
    {
        try
        {
            double w = anchor.Bounds.Width, h = anchor.Bounds.Height;
            if (w < 1 || h < 1) { HideAnchorClone(); return; }
            double scale = (TopLevel.GetTopLevel(this)?.RenderScaling) ?? 1.0;
            // Snapshot the chip content to a bitmap at exact display resolution. A live VisualBrush would go blank the
            // instant we hide its source (verified), so a static copy is the only way to keep exactly one lit chip.
            var px = new PixelSize(Math.Max(1, (int)Math.Round(w * scale)), Math.Max(1, (int)Math.Round(h * scale)));
            var snap = new RenderTargetBitmap(px, new Vector(96 * scale, 96 * scale));
            // Grayscale (not LCD-subpixel) text AA in the snapshot: subpixel fringes baked into a bitmap read as a coloured
            // horizontal ghost when the bitmap is later resampled. Grayscale AA has none, so the clone stays clean.
            Avalonia.Media.TextOptions.SetTextRenderingMode(anchor, Avalonia.Media.TextRenderingMode.Antialias);
            snap.Render(anchor);
            // Request 1: the lit clone sits pixel-exactly OVER the original anchor — no size lift and no positional shift,
            // so the pressed chip keeps EXACTLY its size and place; only its lighting changes (it stays bright above the
            // scrim). (Earlier this baked a +6 % lift into the clone's size and nudged it a few px toward the menu.)
            _ = flipUp;
            double cw = w, ch = h;
            AnchorClone.Width = cw;
            AnchorClone.Height = ch;
            Canvas.SetLeft(AnchorClone, pt.X);
            Canvas.SetTop(AnchorClone, pt.Y);
            AnchorClone.RenderTransform = null;
            var ib = new ImageBrush(snap) { Stretch = Stretch.Fill };
            RenderOptions.SetBitmapInterpolationMode(AnchorClone, BitmapInterpolationMode.HighQuality);
            AnchorClone.Background = ib;
            AnchorClone.IsVisible = true;
            // Hide the live anchor so the only visible copy is the lit clone above the scrim (no double). This MUST be set at
            // ANIMATION priority: SetAccountView's sign-in crossfade runs with FillMode.Forward, which leaves an Animation-
            // priority Opacity=1 committed on AccountChip; a plain (LocalValue) anchor.Opacity=0 cannot override that, so the
            // live chip stayed visible UNDER the lifted clone -> the two overlapping chips the user reported (spec §11 G10).
            anchor.SetValue(OpacityProperty, 0.0, Avalonia.Data.BindingPriority.Animation);
            _clonedAnchor = anchor;
        }
        catch (Exception ex) { LogCrash(ex); HideAnchorClone(); }
    }

    private void HideAnchorClone()
    {
        AnchorClone.IsVisible = false;
        AnchorClone.Background = null;
        AnchorClone.RenderTransform = null;
        if (_clonedAnchor is not null)
        {
            // Restore at the SAME (Animation) priority we hid it at, so it wins over any Forward-filled value still sitting
            // there (a plain set would be shadowed by that animation-priority value and leave the chip invisible).
            _clonedAnchor.SetValue(OpacityProperty, 1.0, Avalonia.Data.BindingPriority.Animation);
            Avalonia.Media.TextOptions.SetTextRenderingMode(_clonedAnchor, Avalonia.Media.TextRenderingMode.Unspecified);
            _clonedAnchor = null;
        }
    }

    private async void CloseGlassMenu()
    {
        _openSel = null;
        bool menuClose = false;
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
            else
            {
                menuClose = true;
                if (!await AnimateMenu(open: false)) return;   // a newer menu took over: leave the overlay up
            }
        }
        catch (Exception ex) { LogCrash(ex); }
        OverlayHost.IsVisible = false;
        MenuScrim.Opacity = 0;
        HideAnchorClone();
        LiquidGlassAvaloniaUI.LiquidGlassBackdrop.Unfreeze(MenuGlass);
        if (menuClose && _menuAnchor is not null)
        {
            // the value text re-forms in place — blurred, then sharp — a beat after the glass has gone
            if (_anchorFades) StartAnchorReturn(_menuAnchor);
            else RestoreAnchor(_menuAnchor);
        }
    }

    private void OnMenuDismiss(object? sender, PointerPressedEventArgs e) => CloseGlassMenu();

    // Gap I — keyboard dismiss: Esc closes whatever pop-up is open, using the same close path (and animation) as a
    // click outside. Only acts while the overlay is up so it never eats Esc elsewhere.
    private void OnGlobalKeyDown(object? sender, Avalonia.Input.KeyEventArgs e)
    {
        if (!OverlayHost.IsVisible) return;
        var k = e.Key;
        if (k == Avalonia.Input.Key.Escape) { CloseGlassMenu(); e.Handled = true; return; }
        // Keyboard selection-capsule movement (spec §10.6): Up/Down/Home/End move it (no wrap), Enter/Space commit.
        if (!_capActive || !_menuSettled) return;
        switch (k)
        {
            case Avalonia.Input.Key.Down: KeyMoveCapsule(+1, false, false); e.Handled = true; break;
            case Avalonia.Input.Key.Up:   KeyMoveCapsule(-1, false, false); e.Handled = true; break;
            case Avalonia.Input.Key.Home: KeyMoveCapsule(0, true, false);   e.Handled = true; break;
            case Avalonia.Input.Key.End:  KeyMoveCapsule(0, false, true);   e.Handled = true; break;
            case Avalonia.Input.Key.Enter:
            case Avalonia.Input.Key.Space: CommitCapsule(); e.Handled = true; break;
        }
    }

    private double _menuLeft, _menuTop, _menuW, _menuH, _btnCX, _btnW;
    private double _seedL, _seedT, _seedR, _seedB;   // the trigger box the menu grows out of / collapses into
    private int _menuAnimGen;               // a newer open/close supersedes a running animation
    private const double MenuCellW = 106;          // version-grid cell width (3 columns)
    private readonly System.Collections.Generic.List<TextBlock> _menuCellLabels = new();   // grid-cell labels, index == item
    private const double MenuPanelRadius = 20;   // unified with the launcher's cards/sheets; drawn as a continuous (squircle) corner (iOS 26 ~0.13*width; a = 1.31*R)
    private double _menuRadius = MenuPanelRadius;

    // ---- iOS 26 context-menu scale (spec §8/§11): every size is 1 DIP = 1 iOS pt, held in ONE knob so it can be
    // re-tuned to the launcher's target device scale later without touching the individual numbers. ----
    private const double IosPt        = 1.0;            // DIP per iOS pt
    private const double CtxRowHeight = 44 * IosPt;     // row pitch 44 pt
    private const double CtxTextSize  = 17 * IosPt;     // body text 17 pt SF Pro
    private const double CtxRowPadH   = 14 * IosPt;     // row left/right inset
    private const double CtxCheckGutter = 26 * IosPt;   // leading ✓ gutter (icon-column analogue)
    private const double CtxMenuGap   = 14 * IosPt;     // anchor-bottom -> menu-top gap (spec §8: 14-17 pt)
    private const double CtxLeftBias  = 6  * IosPt;     // menu left edge sits ~5-7 pt outward of the anchor's left edge
    // Context-menu corner: iOS keeps it ~0.12-0.13 * menu width (spec §9); on our ~225 DIP account menu that is ~28-29 DIP,
    // drawn as a superellipse a = 1.31 * this radius. Its own knob so the pull-down keeps MenuPanelRadius (=20).
    private const double CtxCornerRadius = 28 * IosPt;
    // iOS 26 dark context-menu body. The spec's clear-glass model (0.41 alpha / rgb 102) composited to interior L~179 over
    // the harness wallpaper (L~232 behind the menu) — a light silver SHEET, where iOS 26 reads as a DARK OPAQUE grey
    // (interior L~96-106) that holds its darkness regardless of the (bright) backdrop and keeps the white rows crisp.
    // Re-solved from the settled grabs: iOS's effective alpha is ~0.66 over a dark tint; because the launcher menu sits over
    // a BRIGHTER wallpaper region than iOS's did (232 vs 184), matching iOS's ABSOLUTE L needs a touch more opacity so the
    // body is backdrop-independent. alpha 0.80 over neutral rgb 71 -> interior L ~103 over 232 / ~94 over 184 (iOS 96-106).
    // [DESIGN-INTENT NOTE — needs user sign-off: this makes the dark context body a near-opaque dark grey, which reverses the
    //  2026-09-19 "clear glass / 只是一個透明片" decision for THIS one menu. It is the iOS-26-faithful choice ("identical to
    //  iOS 26"); to revert to clear glass, restore Color.FromArgb(0x69,0x66,0x66,0x68) here — one knob, nothing else changes.]
    private static readonly Color CtxBodyGrey = Color.FromArgb(0xCC, 0x47, 0x47, 0x47);

    // Context-menu "emerge from anchor" seed (spec §2; iOS blob ~0.28 w x 0.12 h of the final panel, aspect ~1.75 at the
    // first visible frame). The panel is born as a WIDE, short rounded-rect nub pinned at the anchor's bottom edge and a
    // phase-seeded spring grows it, so the first frame reads as a rounded RECT the way iOS does, not a growing round dot.
    // The width floor is the widest the harness scale-spring fit tolerates: a bigger floor lifts the fitted response past
    // iOS+5 % (the scale spring is fit on 0.5*(wf+hf), so the width floor inflates it). Height floor ~0 -> wide+short.
    // Per theme because light's response budget (iOS 0.282 +-5 %) is tighter than dark's (0.310), so light caps narrower.
    private const double CtxSeedWDark  = 0.16;   // dark nub width fraction  -> first-frame aspect wider (iOS ~1.75; capped by the spring-response tolerance since this menu is taller-than-wide; 0.16 keeps response margin)
    private const double CtxSeedWLight = 0.12;   // light nub width fraction (kept narrow: widening improved blob aspect but diluted the bbox overshoot below iOS-0.5pp and pulled settle-to-2% too early — a net-worse trade)
    private const double CtxSeedH      = 0.01;   // near-zero height floor: the nub is wide and short like iOS's
    // Phase-seed the OPEN spring this far into its own trajectory (iOS scale-spring t0 ~ -37 ms) so the nub is first-visible
    // ~1 frame in (iOS 16.7 ms) WITHOUT altering the spring's response / damping / overshoot (intrinsic, recovered by the
    // fit regardless of phase). Kept as small as the wide seed allows, because settle-to-2 % is anti-correlated with
    // first-visible on this phase axis (a bigger advance brings the 2 % re-entry earlier) — iOS's later settle is
    // crisp-capture rim jitter, not a slower spring, so the residual settle gap is a documented capture artifact.
    private const double CtxOpenPhaseSeedDark  = 0.018;
    private const double CtxOpenPhaseSeedLight = 0.014;

    private bool _menuFlipUp;   // popover above anchor instead of below
    private Control? _menuAnchor;           // the trigger of the open menu
    private bool _anchorFades;              // dropdown triggers hand their value text over to the glass
    private int _anchorGen;                 // cancels a value-return tail
    private long _closeStartTicks;

    // ---- deterministic 60 fps frame-capture harness (inert unless S1MP1E_MENUSHOT names a folder) ----
    private bool _captureMode;              // sealed capture run: never writes config/accounts, virtual clock
    private long _capMs;                    // virtual clock (ms) driven by the capture pump
    private long NowMs() => _captureMode ? _capMs : Environment.TickCount64;

    // ---- backdrop-scrim + context-menu animation state (shared by the live RAF loop and the capture pump) ----
    private double _scrimLevel;             // the floor this menu's scrim dims to (0 = non-modal pull-down: no scrim)
    private double _scrimFrom;              // scrim opacity captured at close-start, so the un-dim can outlast the shape
    private S1mp1e.Controls.GlassMotion.Spring? _mSw, _mSh;   // the open springs for the running animation
    private bool _mOpen, _mDark;
    private double _mCloseW, _mCloseH, _mFadeT, _mFadeP, _mScrimCloseDur;
    private Control? _mFadeAnchor;          // the anchor whose value the glass absorbs (fades path only)
    private readonly Avalonia.Media.BlurEffect _anchorBlur = new() { Radius = 0 };
    // ---- pull-down trigger-text RETURN (spec IOS26_PULLDOWN_SPEC §3b): after the glass collapses, the grey value text
    // re-forms and springs back on a HIGH-DAMPING spring (response ~0.47, dampingFraction ~0.80), blurred-then-sharp, and
    // the chevron returns LAST (~110 ms after the text). Split state so both the live RAF loop and the capture pump drive it.
    private S1mp1e.Controls.GlassMotion.Spring? _retSpring;   // value opacity (monotone) + a small scale rebound (the visible 回彈)
    private S1mp1e.Controls.GlassSelect? _retAnchor;
    private long _retStartMs;
    private double _retBlur0, _retBlur1;                       // value edge-sharpen (blur-clear) window; dark clears later than light
    private const double RetChevronDelay = 0.11;              // chevron begins ~110 ms after the text and finishes with it
    private readonly System.Collections.Generic.List<MenuRowAnim> _menuRows = new();

    // ---- iOS 26 selection capsule (spec IOS26_MENU_SELECTION_SPEC.md): a SINGLE rigid stadium behind the rows that
    // springs from row-centre to row-centre under the pointer / keyboard focus, fades in on first hover and out on
    // leave, and is the ONLY selection/hover indicator (no check marks, no per-row background). ----
    private readonly S1mp1e.Controls.GlassMotion.Spring _capX = new(0.11, 0);   // left  (constant for single-column; tracks cell for a grid)
    private readonly S1mp1e.Controls.GlassMotion.Spring _capY = new(0.11, 0);   // top   (the tracked axis)
    private int _capIndex = -1;          // row the capsule currently targets (-1 = none shown)
    private int _capHome = -1;           // MOUSE idle-home row (always -1 now: the capsule fades out on leave, never springs back — Request 2)
    private int _capKeyHome = -1;        // KEYBOARD first-reveal row (a value picker may still light the current value on the first arrow key)
    private bool _capGrid;               // grid menu (per-cell rounded rect) vs single-column (full-width stadium)
    private bool _capActive;             // a menu is open and the capsule is usable
    private bool _menuSettled;           // open morph finished -> capsule geometry (row layout at scale 1) is valid
    private double _capOpacity, _capOpacityTarget;   // driven manually so the capture harness is deterministic
    private double _capW, _capH, _capCorner;         // this menu's capsule size (every row is the same size)
    // Uniform gap between the capsule and the menu-panel edge, EQUAL on all four sides (top / bottom / left / right).
    // Chosen so the capsule's rounded corner is CONCENTRIC with the panel's: panel drawn corner (a = 1.31 * _menuRadius,
    // the settled squircle semi-axis every lens/rim/clip uses) = capsule corner (rowPitch/2) + _capInset. Set per menu in
    // ShowMenuFor; drives the capsule's horizontal inset (CapsuleTargetPos) AND MenuItems' top/bottom padding.
    private double _capInset = 10;
    private bool _capLoopRunning;
    private int _capItemCount;
    private Action<int>? _menuOnPick;    // the open menu's pick action (for Enter/Space commit)
    private bool[]? _menuDisabled;       // the open menu's disabled mask
    private bool _capWired;              // one-time PointerExited wiring on MenuItems

    // ---- Request 3: scrollable single-column pull-down (version / menu-key picker) ----
    private ScrollViewer? _menuScroller;          // the scroll viewport; created lazily, reused across opens
    private StackPanel? _menuScrollPanel;         // its single-column row stack
    private bool _menuScrolling;                  // the currently-open menu uses the scroll viewport
    private double _menuRowPitch;                 // one row's height (drives the 8.5-row cap + keyboard paging)
    private double _menuInitScrollY;              // open-scroll target: current value near the top
    private double _menuScrollTarget = double.NaN;// smooth-scroll tween target for the menu viewport
    private DispatcherTimer? _menuScrollTimer;
    private readonly System.Diagnostics.Stopwatch _menuScrollSw = new();

    private void ApplyCapsuleTheme()
    {
        bool dark = ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
        // Dark: additive-ish white ~15 % (+~26 L over the body). Light: black ~16 %. Flat, neutral, no rim/shadow (spec §2-3).
        MenuCapsule.Background = new SolidColorBrush(dark ? Color.FromArgb(0x26, 0xFF, 0xFF, 0xFF)
                                                         : Color.FromArgb(0x29, 0x00, 0x00, 0x00));
    }

    // Prepare the capsule when a menu opens. Value pickers (GlassSelect / fades) rest on the current value; action
    // menus (account switcher, +, download, skin) open with NO capsule and fade it in on first hover (spec §10.8).
    private void ResetCapsuleForOpen(bool fades, int selected, bool grid, int itemCount)
    {
        _capActive = true;
        _menuSettled = false;
        _capIndex = -1;
        _capGrid = grid;
        _capItemCount = itemCount;
        // Request 2: value pickers no longer indicate the current value with the capsule — it is NOT pre-placed on open and
        // does NOT spring back to the current value on leave. _capHome (mouse pre-place / spring-back home) is always -1 now,
        // exactly like an action menu: the capsule appears under the pointer and fades out in place on leave. The keyboard's
        // first arrow may still reveal it on the current value, so that lives in a separate _capKeyHome.
        _capHome = -1;
        _capKeyHome = fades ? selected : -1;
        _capOpacity = 0; _capOpacityTarget = 0;
        MenuCapsule.Opacity = 0;
        ApplyCapsuleTheme();
    }

    private void HideCapsuleInstant()
    {
        _capActive = false;
        _menuSettled = false;
        _capIndex = -1;
        _capOpacity = 0; _capOpacityTarget = 0;
        MenuCapsule.Opacity = 0;
    }

    // Top-left (in the capsule Canvas frame) for row i, also computing this menu's fixed capsule size.
    private (double x, double y) CapsuleTargetPos(int i)
    {
        var row = _menuRows[i].Row;
        var tl = row.TranslatePoint(new Point(0, 0), MenuCapsuleLayer) ?? new Point(0, 0);
        double rw = row.Bounds.Width, rh = row.Bounds.Height;
        if (_capGrid)
        {
            _capW = rw * 0.92; _capH = rh; _capCorner = Math.Min(_capW, _capH) * 0.30;
            return (tl.X + (rw - _capW) / 2, tl.Y);
        }
        // single column: rigid stadium (spec §1: corner = height/2, x fixed). The side inset = _capInset, the SAME uniform gap
        // used top/bottom (MenuItems padding), so the capsule nests concentrically inside the panel's corner on every side.
        _capW = Math.Max(0, _menuW - 2 * _capInset); _capH = rh; _capCorner = _capH / 2;
        return (_capInset, tl.Y);
    }

    // Move / show the capsule on row i.
    //   animate=false : pre-place / click commit — snap position AND light it instantly.
    //   animate=true, first appearance (currently invisible) : snap to the row and FADE IN in place (spec §4a: opacity
    //     only, no slide from wherever the springs were).
    //   animate=true, already visible : SPRING from the current row to row i (spec §4b).
    private void ShowCapsuleAt(int i, bool animate)
    {
        if (!_capActive || i < 0 || i >= _menuRows.Count) return;
        var (x, y) = CapsuleTargetPos(i);
        MenuCapsule.Width = _capW;
        MenuCapsule.Height = _capH;
        MenuCapsule.CornerRadius = new CornerRadius(_capCorner);
        _capIndex = i;
        _capOpacityTarget = 1;
        bool firstAppear = _capOpacity < 0.02;
        if (animate && !firstAppear)
        {
            _capX.Retarget(x); _capY.Retarget(y);
        }
        else
        {
            _capX.Snap(x); _capY.Snap(y);
            Canvas.SetLeft(MenuCapsule, x);
            Canvas.SetTop(MenuCapsule, y);
            if (!animate) { _capOpacity = 1; MenuCapsule.Opacity = 1; }   // pre-place / click: lit at once
        }
        StartCapsuleLoop();
    }

    // Advance the capsule one frame; returns true once springs are settled and opacity has reached its target.
    private bool CapsuleTick(double dt)
    {
        _capX.Update(dt);
        _capY.Update(dt);
        double rate = (_capOpacityTarget >= _capOpacity ? 1.0 / 0.165 : 1.0 / 0.175);   // fade in ~165 ms / out ~175 ms (spec §4a/4c)
        double step = rate * dt;
        if (_capOpacity < _capOpacityTarget) _capOpacity = Math.Min(_capOpacityTarget, _capOpacity + step);
        else                                 _capOpacity = Math.Max(_capOpacityTarget, _capOpacity - step);
        Canvas.SetLeft(MenuCapsule, _capX.X);
        Canvas.SetTop(MenuCapsule, _capY.X);
        MenuCapsule.Opacity = _capOpacity;
        bool springDone = _capX.Settle(0.5) & _capY.Settle(0.5);
        bool opDone = Math.Abs(_capOpacity - _capOpacityTarget) < 0.001;
        return springDone && opDone;
    }

    private void StartCapsuleLoop()
    {
        if (_captureMode) return;   // the capture harness drives CapsuleTick on the virtual clock instead
        if (_capLoopRunning) return;
        var top = TopLevel.GetTopLevel(this);
        if (top is null) { CapsuleTick(1.0); return; }
        _capLoopRunning = true;
        var clock = new S1mp1e.Controls.GlassMotion.Clock();
        void Frame()
        {
            if (!_capActive) { _capLoopRunning = false; return; }
            double dt = clock.Tick();
            bool done = CapsuleTick(dt);
            if (done) { _capLoopRunning = false; return; }
            top.RequestAnimationFrame(_ => Frame());
        }
        Frame();
    }

    // Pointer moved onto row i (skips disabled rows so the capsule clamps, matching the finger drag).
    private void OnRowHover(int i)
    {
        if (!_capActive || !_menuSettled) return;
        if (_menuDisabled is not null && i < _menuDisabled.Length && _menuDisabled[i]) return;
        if (i == _capIndex && _capOpacityTarget >= 1) return;
        ShowCapsuleAt(i, animate: true);
    }

    // Pointer left the rows: a value picker springs back to its current value (stays lit); an action menu fades out.
    private void OnMenuPointerExited()
    {
        if (!_capActive || !_menuSettled) return;
        if (_capHome >= 0) ShowCapsuleAt(_capHome, animate: true);
        else { _capOpacityTarget = 0; StartCapsuleLoop(); }
    }

    private int SkipDisabled(int i, int dir)
    {
        if (dir == 0) dir = 1;
        while (i >= 0 && i < _capItemCount)
        {
            if (!(_menuDisabled is not null && i < _menuDisabled.Length && _menuDisabled[i])) return i;
            i += dir;
        }
        return -1;
    }

    // Keyboard focus movement (spec §10.6): Up/Down step one row, Home/End jump to the ends, no wrap. The first key
    // press while the capsule is hidden just reveals it (current value for a picker, first row for an action menu).
    private void KeyMoveCapsule(int delta, bool toHome, bool toEnd)
    {
        if (!_capActive || !_menuSettled || _capItemCount <= 0) return;
        bool hidden = _capIndex < 0 || _capOpacityTarget < 0.5;
        int target;
        if (hidden) target = _capKeyHome >= 0 ? _capKeyHome : 0;   // first arrow reveals the current value (picker) or the first row
        else if (toHome) target = 0;
        else if (toEnd) target = _capItemCount - 1;
        else target = Math.Clamp(_capIndex + delta, 0, _capItemCount - 1);
        target = SkipDisabled(target, toEnd ? -1 : (delta != 0 ? delta : 1));
        if (target < 0) return;
        EnsureMenuRowVisible(target);   // Request 3: scroll the capsule's row into view (keyboard Up/Down/Home/End)
        ShowCapsuleAt(target, animate: true);
    }

    private void CommitCapsule()
    {
        if (!_capActive || _capIndex < 0 || _capIndex >= _capItemCount) return;
        if (_menuDisabled is not null && _capIndex < _menuDisabled.Length && _menuDisabled[_capIndex]) return;
        int idx = _capIndex;
        try { _menuOnPick?.Invoke(idx); } catch (Exception ex) { LogCrash(ex); }
        CloseGlassMenu();
    }

    private sealed class MenuRowAnim
    {
        public MenuRowAnim(Control row, Control? check, double openDelay, double closeDelay)
        {
            Row = row; Check = check; OpenDelay = openDelay; CloseDelay = closeDelay;
        }
        public Control Row { get; }
        public Control? Check { get; }          // the ✓ of the selected row, which lands last
        public double OpenDelay { get; }
        public double CloseDelay { get; }
        public Avalonia.Media.BlurEffect Blur { get; } = new() { Radius = 0 };
    }

    // iOS-26 pop-up menu, fitted to the user's 下拉深 / 下拉淺 clips (all open/close repeats tracked frame by frame
    // with two independent methods and a re-measure; geometry and timing are the same in both themes):
    // OPEN — the value box balloons into glass; left+right ride a width spring (zeta 0.72, measured left-edge peak
    //   ~195 ms, +3.5%), top+bottom a height spring (zeta 0.56, measured bottom peak 84 ms, +11-12%, one overshoot,
    //   no ringing); it holds a capsule until ~150 ms and sharpens by ~340 ms; opacity is full by ~140 ms. The value text
    //   blurs in place and is gone within ~50 ms; the rows appear as blurred, lens-magnified ghosts — middle, top,
    //   bottom — and sharpen in place; the ✓ lands last (~+125 ms after the first row).
    // CLOSE — fast and monotonic, not a mirror: the glass fades at once (linear) while the shape collapses into the
    //   trigger (u^1.5; ~75 ms dark / ~50 ms light), rows blur out bottom-first within ~40 ms, and the value text
    //   re-forms blurred-to-sharp a beat later (StartAnchorReturn).
    // Driven from animation-frame callbacks, never from Render. Returns false if a newer open/close took over.
    // The per-frame math lives in MenuTick so BOTH the live loop here and the deterministic capture pump run identically.
    private System.Threading.Tasks.Task<bool> AnimateMenu(bool open)
    {
        int gen = SetupMenuAnim(open);
        var done = new System.Threading.Tasks.TaskCompletionSource<bool>();
        var clock = new S1mp1e.Controls.GlassMotion.Clock();
        long t0 = NowMs();
        var top = TopLevel.GetTopLevel(this);

        void Frame()
        {
            if (gen != _menuAnimGen) { done.TrySetResult(false); return; }
            double t = (NowMs() - t0) / 1000.0;
            double dt = clock.Tick();
            if (MenuTick(t, dt))
            {
                if (open) FinishMenuAnim();
                done.TrySetResult(true);
                return;
            }
            top!.RequestAnimationFrame(_ => Frame());
        }

        if (top is null)
        {
            ApplyMenuFrame(open ? 1 : 0, open ? 1 : 0, open ? 1 : 0, open ? 1 : 0, 1, 1.05);
            foreach (var r in _menuRows) SetMenuRow(r, open ? 1 : 0, 0);
            MenuScrim.Opacity = open ? _scrimLevel : 0;
            done.TrySetResult(true);
        }
        else Frame();   // the first frame lays down the seed state synchronously
        return done.Task;
    }

    /// <summary>Build the springs and per-run state for one open/close; used by both the live loop and the capture pump.
    /// Split "drop-then-widen" springs for the PULL-DOWN (matched to 下拉深/下拉淺); ONE uniform spring for both axes on the
    /// CONTEXT-MENU path (spec §3: response 0.28 s, dampingFraction 0.78 -> Tune(0.28, 0.22)).</summary>
    private int SetupMenuAnim(bool open)
    {
        int gen = ++_menuAnimGen;
        MenuRoot.Opacity = 1;
        MenuItems.Opacity = 1;
        MenuItems.Effect = null;
        // Pull-down grows from its top-RIGHT (over the value); context menu is born at its top-LEFT and grows down+right.
        MenuItems.HorizontalAlignment = _anchorFades ? Avalonia.Layout.HorizontalAlignment.Right : Avalonia.Layout.HorizontalAlignment.Left;
        MenuItems.VerticalAlignment = _menuFlipUp ? Avalonia.Layout.VerticalAlignment.Bottom : Avalonia.Layout.VerticalAlignment.Top;
        MenuItems.RenderTransformOrigin = new RelativePoint(_anchorFades ? 1 : 0, _menuFlipUp ? 1 : 0, RelativeUnit.Relative);
        _mDark = ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
        _mOpen = open;

        if (_anchorFades)
        {
            // iOS 26 pull-down opens on TWO different springs (spec IOS26_PULLDOWN_SPEC §4):
            //  - HEIGHT is the fast "drop": response 0.12 s, DEADBEAT (dampingFraction ~1.0, 0 % overshoot). It lands first
            //    (~120-180 ms), squaring the nub into a full-height bulging ellipse. (Corrects the old "+11 % height
            //    overshoot" belief — the dedicated 下拉深/下拉淺 clips are deadbeat.)
            //  - WIDTH is the slow, visible spring: response 0.36 s, dampingFraction ~0.70 (bounce 0.30), overshoot ~3.6-3.9 %.
            //    It grows left and settles ~420 ms — the "widen" you actually watch.
            _mSw = new S1mp1e.Controls.GlassMotion.Spring(0.36, open ? 0 : 1).Tune(0.36, 0.30);
            _mSh = new S1mp1e.Controls.GlassMotion.Spring(0.12, open ? 0 : 1).Tune(0.12, 0.0);
        }
        else
        {
            // iOS 26 context menu: ONE uniform spring, both axes together. Re-measured PER THEME (harness gradient-edge fit):
            //  - response: iOS DARK 0.310 s, iOS LIGHT 0.282 s (light is the faster one). We drive dark 0.31 and light 0.29
            //    (fit lands ~0.285, within 5 % of iOS 0.282, and only ~5 % faster than the old 0.305 so the light settle
            //    stays within a frame of iOS 266.7 ms). One knob per theme so they can be re-tuned independently.
            //  - bounce: DARK 0.28 with the wide-nub seed lands the fitted damping ~0.745 (iOS 0.751, within 0.03) and the
            //    diluted bbox overshoot ~3.0 % (iOS 3.03 %, within 0.5 pp). LIGHT 0.255 lands damping ~0.772 (iOS 0.794,
            //    within 0.03) and lifts the overshoot toward iOS; iOS light's 2.79 % overshoot is still physically
            //    inconsistent with its own 0.794 damping (crisp-capture rim-glow), so the residual is a documented artifact.
            double resp   = _mDark ? 0.315 : 0.285;
            double bounce = _mDark ? 0.29  : 0.255;   // dark: a touch more bounce/response rings ~1 frame longer -> the 2% settle moves toward iOS's 316.7 ms while damping/overshoot stay in tolerance
            _mSw = new S1mp1e.Controls.GlassMotion.Spring(resp, open ? 0 : 1).Tune(resp, bounce);
            _mSh = new S1mp1e.Controls.GlassMotion.Spring(resp, open ? 0 : 1).Tune(resp, bounce);
        }
        _mSw.Retarget(1);
        _mSh.Retarget(1);
        // Phase-seed the context OPEN spring (spec §1/§2, iOS scale-spring t0 ~ -37 ms): advance it ~1.5 frames so the wide
        // nub is first-visible ~1 frame in like iOS, without changing response/damping/overshoot. Pull-downs and the close
        // keep their own onset (they were matched to the other recordings and must not shift).
        if (open && !_anchorFades)
        {
            double adv = _mDark ? CtxOpenPhaseSeedDark : CtxOpenPhaseSeedLight;
            _mSw.Update(adv);
            _mSh.Update(adv);
        }

        long now = NowMs();
        if (!open) { _closeStartTicks = now; _scrimFrom = MenuScrim.Opacity; HideCapsuleInstant(); }
        // Close power curves: width u^1.6 / height u^1.1 (no overshoot); glass opacity LEADS the shape.
        if (_anchorFades)
        {
            // PULL-DOWN close — matched to 下拉深/下拉淺 (T 124/112 ms dark, 105/95 ms light). Do NOT change.
            _mCloseW = _mDark ? 0.124 : 0.105; _mCloseH = _mDark ? 0.112 : 0.095;
            _mFadeT = 0.085; _mFadeP = _mDark ? 0.94 : 1.37;
        }
        else
        {
            // CONTEXT close — the re-measured iOS 26 clip collapses the shape in ~66.7 ms in BOTH themes (faster than the
            // spec's 85-100 ms band and than the old 100/83 ms curves). Speed the shape to ~66.7 ms; in LIGHT speed the glass
            // fade further (the bright light rim otherwise sweeps the fixed interior sample box ~1 frame past the row cut, so
            // the "content cut" read 66.7 ms — a faster fade drops the rim out of the box by iOS's 50 ms).
            _mCloseW = 0.085; _mCloseH = 0.078;
            _mFadeT = _mDark ? 0.085 : 0.060; _mFadeP = _mDark ? 0.94 : 1.37;
        }
        // the scrim un-dim (250 ms) must outlast the ~100 ms shape collapse (spec §1/§7); only when this menu had a scrim.
        _mScrimCloseDur = _scrimFrom > 0.001 ? 0.30 : 0.0;
        _mFadeAnchor = _anchorFades ? _menuAnchor : null;   // context anchors stay lit (the clone), so they are never faded
        // A prior close-return may have left the two trigger glyphs mid-spring; reset them so the OPEN fade composes from a
        // clean, fully-lit trigger (the open fades the parent control; the glyphs must be at 1 underneath it).
        if (open && _mFadeAnchor is S1mp1e.Controls.GlassSelect gsAnchor)
        {
            if (gsAnchor.ValueText is not null) { gsAnchor.ValueText.Opacity = 1; gsAnchor.ValueText.RenderTransform = null; }
            if (gsAnchor.Chevron  is not null)   gsAnchor.Chevron.Opacity  = 1;
        }
        // the mask-blurred shadow would be re-rendered on every growth frame; skip it while growing (live only).
        if (open && !_captureMode) MenuGlass.ShadowEnabled = false;
        return gen;
    }

    /// <summary>One frame of the menu morph at animation time <paramref name="t"/> (s) with spring step <paramref name="dt"/>.
    /// Returns true once the animation has fully finished. No self-scheduling and no completion snap (see FinishMenuAnim).</summary>
    private bool MenuTick(double t, double dt)
    {
        if (_mSw is null || _mSh is null) return true;
        if (_mOpen)
        {
            _mSw.Update(dt);
            _mSh.Update(dt);
            if (_anchorFades)
            {
                // ---- PULL-DOWN open (iOS 26, spec IOS26_PULLDOWN_SPEC): a glass nub balloons out of the value into a fat,
                // bulging ellipse (height drops first, §4), squares into a rounded rect, and its grey tint fills in LATE
                // (§7). The content is genuinely LENSED — magnified at the fat-ellipse frame, then de-magnified/sharpened
                // as the ellipse squares off (§6). The grey value text + chevron fade out together in ~2 frames (§3a). ----
                double corner = MenuSmooth(0.15, 0.34, t);   // n: ellipse (2) while small -> settled squircle during the last third
                // Ellipse-ness e(t): ~0 while the nub is tiny, ~1 at the fat, full-height ellipse (height done, corner not yet
                // squared), back to ~0 when settled. Keys the lens to the SHAPE (peaks mid-open at the oval), not to t=0.
                double hFrac = Math.Clamp(_mSh.X, 0, 1);
                double e = hFrac * (1 - corner);
                ApplyMenuFrame(_mSw.X, _mSh.X,
                    cornerK: corner,
                    glassA: MenuSmooth(0.15, 0.23, t),   // §7: near-transparent lens until ~150 ms, then the grey tint fills fast
                    labelScale: 1 + 0.20 * e,            // §6: interior magnification m = 1 + 0.20*e, peaks at the fat ellipse
                    zoom: 1.05);
                foreach (var r in _menuRows)
                {
                    double d = r.OpenDelay;
                    double op = MenuSmooth(0.02 + d, 0.11 + d, t);   // §6: readable in ~6 frames (fast-in), NOT a slow ramp
                    SetMenuRow(r, op, 5 * e);                         // blur clears as the ellipse squares off (e -> 0): sharpen
                    if (r.Check is not null) r.Check.Opacity = op;
                }
                if (_mFadeAnchor is not null)
                {
                    // §3a: the grey value text AND chevron fade out together, very fast (~2 frames / ~33 ms) to near-zero.
                    _mFadeAnchor.Opacity = 1 - MenuSmooth(0.0, 0.033, t);
                    SetAnchorBlur(_mFadeAnchor, 4 * MenuSmooth(0.0, 0.030, t));
                }
                MenuScrim.Opacity = _scrimLevel * MenuSmooth(0.0, 0.22, t);   // (no scrim for pull-downs: _scrimLevel==0)
                bool wD = _mSw.Settle(0.0008), hD = _mSh.Settle(0.0008);
                return t > 0.45 && wD && hD;
            }
            // ---- CONTEXT-MENU open (iOS 26 "emerge from anchor"): the ENTIRE menu — glass body AND its rows — grows as
            // one scaled object from the pinned corner, and gains opacity as it grows. Body-alpha and row opacity both
            // track the panel SCALE (gaps B/C), reproducing iOS's faint translucent miniature (rows visible inside the
            // small blob) rather than an opaque block that fills, then drops the rows in at full size. ----
            double s = 0.5 * (_mSw.X + _mSh.X);   // panel scale (0 = seed nub, 1 = settled)
            // iOS dark body-alpha-vs-scale (measured in opus-fix/ios_body.py): ~0.24 floor while small, then a ramp that
            // KEEPS DEEPENING as the panel settles. iOS's per-frame body alpha crosses 90% only ~117 ms in (scale ~0.92) —
            // the old 1.60/0.30 ramp hit 90% at scale ~0.71 (~83 ms), arriving opaque ~2 frames too abruptly. DARK now ramps
            // so 90% lands at scale ~0.92 (aK 1.254 / aS0 0.394); LIGHT keeps its (unflagged) 1.60/0.30 whitish fill-in.
            double aK  = _mDark ? 1.254 : 1.60;
            double aS0 = _mDark ? 0.394 : 0.30;
            double bodyA = Math.Clamp(0.24 + aK * (s - aS0), 0.24, 1.0);
            ApplyMenuFrame(_mSw.X, _mSh.X,
                cornerK: MenuSmooth(0.0, 0.10, t),   // corner establishes its ~constant radius fast (reads rounder while small)
                glassA: bodyA,
                labelScale: 1, zoom: 1.05);
            // Rows track scale (gap B): a faint low-opacity miniature while the panel is small (iOS shows the rows as a
            // faint ghost inside the growing blob), then a steep rise near full size. Measured iOS row-detail-energy is
            // ~0.1 at scale 0.5-0.7 and crosses 50 % at scale ~0.85 / 90 % ~0.95 (opus ios_body.py) -> this curve lands
            // the launcher's energy-50 % ~100 ms and 90 % ~150 ms (gap G) while keeping the early miniature iOS-faint.
            // Row opacity: the rows are already geometrically scaled with the panel (ApplyMenuFrame's contentScale), so they
            // read as a miniature; a faint scale-tracked FLOOR gives their earliest presence (iOS shows ~0.1 row energy from
            // the first frame), while the MAIN opacity is a TIME ramp that runs past the ~130 ms shape-settle so the
            // row-detail energy lands 50 % ~100 ms / 90 % ~150 ms (iOS; gap G). The ✓ lands with its row.
            // Early-growth miniature: iOS's rows are already faintly legible inside the small blob (row-detail energy ~0.10 at
            // frame 2). DARK's floor 0.07 matched; LIGHT read only ~0.04 — the faint white rows were too blurred while small,
            // so the birth blob looked featureless. LIGHT gets a higher floor and a shorter blur window so the miniature rows
            // carry edges early, lifting frame-2 energy toward iOS ~0.095 without moving the 50%/90% crossings.
            double rowFloor = (_mDark ? 0.07 : 0.185) * MenuSmooth(0.10, 0.32, s);
            // dark: the opaque body raised the settled row contrast (bigger normaliser), pushing the energy 50%/90% crossings
            // ~1 frame late — speed the dark rise back so 50% lands ~100 ms / 90% ~150 ms (iOS). light: unchanged rise, but a
            // higher floor + a shorter blur window so the early miniature rows carry edges (frame-2 energy toward iOS ~0.095).
            double riseHi = _mDark ? 0.150 : 0.140;
            double blurHi = _mDark ? 0.130 : 0.042;
            foreach (var r in _menuRows)
            {
                double d = r.OpenDelay;                                        // small top-first positional stagger
                double rise = MenuSmooth(0.01 + 0.6 * d, riseHi + 0.6 * d, t);
                double rowOp = Math.Max(rowFloor, rise);
                SetMenuRow(r, rowOp, 5 * (1 - MenuSmooth(0.01 + 0.6 * d, blurHi + 0.6 * d, t)));
                if (r.Check is not null) r.Check.Opacity = rowOp;
            }
            if (_mFadeAnchor is not null)
            {
                _mFadeAnchor.Opacity = 1 - MenuSmooth(0.0, 0.05, t);
                SetAnchorBlur(_mFadeAnchor, 4 * MenuSmooth(0.0, 0.035, t));
            }
            // Scrim fades in to its floor (spec §7). Windows tuned so the 10->floor read matches iOS (dark 150 / light 116.7 ms).
            double scrimInWin = _mDark ? 0.185 : 0.15;
            MenuScrim.Opacity = _scrimLevel * MenuSmooth(0.0, scrimInWin, t);
            bool wDone = _mSw.Settle(0.0008), hDone = _mSh.Settle(0.0008);
            return t > 0.45 && wDone && hDone && (_scrimLevel <= 0 || t >= scrimInWin);
        }
        else
        {
            double uw = Math.Clamp(t / _mCloseW, 0, 1), uh = Math.Clamp(t / _mCloseH, 0, 1);
            double gw = Math.Pow(uw, 1.6), gh = Math.Pow(uh, 1.1);           // measured: width u^1.6, height u^1.1, no overshoot
            double pw = 1 - gw, ph = 1 - gh;
            bool rowsGone = true;
            if (_anchorFades)
            {
                // ---- PULL-DOWN close (iOS 26, spec §5/§7): the grey tint de-fills FIRST (leading the shape), the glass
                // deforms back through an ellipse (squircle n~4.5 -> ellipse n~2) and collapses toward the trigger; the
                // content LENSES (magnifies) through the deforming glass as it goes. Near-critically damped, NO glass
                // rebound — only the trigger value TEXT rebounds afterwards (StartAnchorReturn, §3b). ----
                double glassA = 1 - Math.Pow(Math.Clamp(t / _mFadeT, 0, 1), _mFadeP);   // tint leads the shape out
                double corner = pw;                                  // squircle -> ellipse as it collapses
                double eC = Math.Clamp(ph, 0, 1) * (1 - corner);     // ellipse-ness during the collapse (peaks mid)
                ApplyMenuFrame(pw, ph, cornerK: corner, glassA: glassA, labelScale: 1 + 0.20 * eC, zoom: 1.05);
                double rowCut = 0.035;
                foreach (var r in _menuRows)
                {
                    double k = Math.Clamp((t - r.CloseDelay) / rowCut, 0, 1);
                    SetMenuRow(r, 1 - k, 5 * k);
                    rowsGone &= k >= 1;
                }
                return uw >= 1 && uh >= 1 && t >= _mFadeT && rowsGone;
            }
            // ---- CONTEXT-MENU close (iOS 26, gap D): the row content drops to a faint GHOST in ~1 frame, and that ghost
            // (plus a translucent body that fades as it shrinks) collapses WITH the panel — not an opaque block over an
            // empty rect. Body alpha reuses the open scale-ramp (so it fades to ~0 as the panel vanishes); the rows scale
            // down via ApplyMenuFrame's contentScale. ----
            double s = 0.5 * (pw + ph);
            double bodyA = Math.Clamp(0.24 + 1.60 * (s - 0.30), 0.0, 1.0);   // no floor on close: the glass fades right out
            ApplyMenuFrame(pw, ph, cornerK: pw, glassA: bodyA, labelScale: 1, zoom: 1.05);
            // Contrast drops to a faint ghost BEFORE the shape moves (spec §4). DARK: an instant 1-frame cut — the ghost is
            // present from the first close frame while the panel is still full size, so the interior-detail energy reads
            // <0.15 at frame 0 like iOS (rows_close_cut ~0). LIGHT: persists ~3 frames as it blurs out (iOS reads ~50 ms).
            const double ghost = 0.06;
            double rowVis, rblur;
            // iOS cuts the row CONTENT to a faint ghost in the FIRST close frame — BEFORE the shape moves (spec §4). DARK is a
            // hard 1-frame cut to the ghost. LIGHT is NOT held crisp either: the previous curve started the first close frame
            // at full contrast (energy 1.00) then faded over 50 ms, so the very first frame still showed crisp black rows
            // while iOS already reads ~0.18. LIGHT now STARTS already-ghosted (~0.20 at frame 0, iOS ~0.18) and deepens to the
            // 0.06 floor over ~3 frames, its band energy crossing 15% ~50 ms like iOS — a cut, not a fade-from-full.
            if (_mDark) { rowVis = ghost; rblur = 5; }
            else { double k = MenuSmooth(0.016, 0.058, t); rowVis = 0.20 * (1 - k) + ghost * k; rblur = 3 + 2 * k; }
            rowVis *= Math.Clamp(s / 0.15, 0, 1);                  // the ghost only vanishes once the panel is nearly gone
            foreach (var r in _menuRows)
            {
                SetMenuRow(r, rowVis, rblur);
                rowsGone &= rowVis < 0.02;
            }
            // Scrim clears slower than the shape and outlasts it (spec §1/§5/§7). Windows tuned so 10->90 % fade-out reads
            // iOS's 183 ms (dark) / 150 ms (light) while still outlasting the ~66.7 ms shape collapse.
            double scrimWin = _mDark ? 0.23 : 0.195;
            MenuScrim.Opacity = _scrimFrom * (1 - MenuSmooth(0.0, scrimWin, t));
            return uw >= 1 && uh >= 1 && t >= _mFadeT && rowsGone && t >= _mScrimCloseDur;
        }
    }

    /// <summary>Snap the open animation to its settled state (called once when MenuTick reports finished).</summary>
    private void FinishMenuAnim()
    {
        ApplyMenuFrame(1, 1, 1, 1, 1, 1.05);
        MenuGlass.ShadowEnabled = true;
        if (!_captureMode) LiquidGlassAvaloniaUI.LiquidGlassBackdrop.Unfreeze(MenuGlass);   // back to live captures now the glass is still
        foreach (var r in _menuRows)
        {
            SetMenuRow(r, 1, 0);
            if (r.Check is not null) r.Check.Opacity = 1;
        }
        MenuScrim.Opacity = _scrimLevel;
        if (_mFadeAnchor is not null) { _mFadeAnchor.Opacity = 0; SetAnchorBlur(_mFadeAnchor, 0); }
        _menuSettled = true;
        // Request 2: the capsule is NO LONGER pre-placed on the current value for value pickers (_capHome is -1). It appears
        // only under the pointer. (This line stays as a guard for any future menu that would want a resting capsule.)
        if (_capActive && _capHome >= 0) ShowCapsuleAt(_capHome, animate: false);
        // Scroll pull-down: make sure it is scrolled to the current value near the top now the extent is final.
        if (_menuScrolling && _menuScroller is not null) ApplyMenuInitScroll();
    }

    // ================= deterministic 60 fps frame-capture harness =================
    // Sealed capture mode: enabled ONLY when the env var S1MP1E_MENUSHOT names an output folder. It never writes the user's
    // config/accounts (in-memory fake state), drives the menu on a virtual clock (NowMs -> _capMs, spring dt = 1/60), and
    // renders the WHOLE window (so the backdrop scrim is included) to PNG for both themes x {context menu, pull-down}.
    private const int CapOpenMs = 700, CapCloseMs = 450;

    private async System.Threading.Tasks.Task RunCaptureModeAsync(string outDir)
    {
        _captureMode = true;
        try
        {
            System.IO.Directory.CreateDirectory(outDir);
            // In-memory fake identity so the account switcher has rows WITHOUT touching %APPDATA%\S1mp1e.
            _cfg = new LauncherConfig
            {
                Accounts = new System.Collections.Generic.List<SavedAccount>
                {
                    new() { Name = "Steve",     Uuid = "00000000000000000000000000000001" },
                    new() { Name = "Alex",      Uuid = "00000000000000000000000000000002" },
                    new() { Name = "Herobrine", Uuid = "00000000000000000000000000000003" },
                    new() { Name = "Notch",     Uuid = "00000000000000000000000000000004" },
                    new() { Name = "S1mp1e",    Uuid = "00000000000000000000000000000005" },
                },
            };
            _cfg.Account = _cfg.Accounts[0];

            // Show the start page (VersionBox + the sidebar account chip both live here) and reveal the signed-in chip.
            _selected = 0;
            // Capture-only: load the FULL supported-version list into the picker (normal mode does this via
            // RefreshInstalledVersionsAsync, which the sealed harness skips) so the version pull-down actually has enough
            // rows to scroll — otherwise it would show only the 5 XAML defaults. This is UI state only; nothing is persisted.
            VersionBox.Options = string.Join("|", SupportedVersions);
            VersionBox.SelectedIndex = 0;
            MovePill(0, animate: false);
            UpdateNavWeights(0);
            ShowPage(0);
            if (ChipName is not null) ChipName.Text = _cfg.Account.Name;
            if (ChipSub  is not null) ChipSub.Text  = $"Microsoft · {_cfg.Accounts.Count} 個帳號";
            SetAccountView(signedIn: true, animate: false);
            foreach (var gs in this.GetVisualDescendants().OfType<GlassSelect>())
                gs.OpenRequested += (_, src) => { if (!_captureMode) ShowGlassMenu(src); };

            await System.Threading.Tasks.Task.Delay(400);   // let the window, chip and backdrop settle (wall time; frame timing is virtual)
            await NextFrameAsync();
            // Wait for the window to reach its FINAL laid-out size before any capture: on a loaded machine the window opens at
            // a smaller default and resizes a beat later, and the right-side pull-down ROI is measured in fixed pixels, so a
            // capture taken mid-resize corrupts the pull-down (and only the pull-down, being far from the origin). Poll until
            // ClientSize is stable across two frames, up to ~2 s. Frame timing stays virtual; this is wall-clock settling only.
            {
                Size prev = ClientSize;
                for (int i = 0; i < 40; i++)
                {
                    await System.Threading.Tasks.Task.Delay(50);
                    await NextFrameAsync();
                    Size cur = ClientSize;
                    if (i >= 2 && cur.Width > 900 && Math.Abs(cur.Width - prev.Width) < 0.5 && Math.Abs(cur.Height - prev.Height) < 0.5) break;
                    prev = cur;
                }
            }

            double scale = (TopLevel.GetTopLevel(this)?.RenderScaling) ?? 1.0;
            var manifestSets = new System.Collections.Generic.List<object>();

            foreach (var dark in new[] { true, false })
            {
                string theme = dark ? "dark" : "light";
                if (Application.Current is { } app)
                    app.RequestedThemeVariant = dark ? ThemeVariant.Dark : ThemeVariant.Light;
                await System.Threading.Tasks.Task.Delay(350);   // theme + acrylic backdrop repaint
                await NextFrameAsync();

                // context menu (account switcher) and one pull-down (version picker)
                foreach (var (menu, anchor, open) in new (string, Control, Action)[]
                {
                    ("context",  AccountChip, () => ShowAccountSwitcher()),
                    ("pulldown", VersionBox,  () => ShowGlassMenu(VersionBox)),
                })
                {
                    var (openTimes, closeTimes) = await CaptureMenuSetAsync(outDir, theme, menu, open);
                    var ar = AnchorRectDip(anchor);
                    manifestSets.Add(new
                    {
                        theme, menu,
                        anchorRectDip = new[] { ar.X, ar.Y, ar.Width, ar.Height },
                        openFrames = openTimes.Count, closeFrames = closeTimes.Count,
                        openFrameTimesMs = openTimes, closeFrameTimesMs = closeTimes,
                    });
                }

                // Selection-capsule hover sequences (capture-only; run.py ignores these labels). Both the account switcher
                // and the version picker now behave the same: the capsule stays hidden until the pointer is over a row, then
                // follows it, then fades out in place on leave (Request 2 removed the value picker's current-value resting).
                await CaptureCapsuleSequenceAsync(outDir, theme, "ctxhover", () => ShowAccountSwitcher(), valuePicker: false);
                await CaptureCapsuleSequenceAsync(outDir, theme, "pdhover", () => ShowGlassMenu(VersionBox), valuePicker: true);

                // Pull-down CLOSE + trigger-text RETURN (spec §3b) — outside-dismiss and select-dismiss (same close, §9).
                await CapturePulldownReturnAsync(outDir, theme, "pdretout", pickValue: false);
                await CapturePulldownReturnAsync(outDir, theme, "pdretsel", pickValue: true);

                // ---- three NEW capture-only sequences, one per user request (run.py ignores these labels) ----
                // Request 1: the account chip before press vs while the switcher is open — same size, same position.
                await CaptureAccountChipSizeAsync(outDir, theme);
                // Request 2: the version picker capsule appears only under the pointer (no pre-placement / no jump-back).
                await CaptureVersionHoverAsync(outDir, theme);
                // Request 3: the version picker as a single scrolling column (wheel down/up with edge fades, then a click).
                await CaptureVersionScrollAsync(outDir, theme);
            }

            var manifest = new
            {
                generatedUtc = DateTime.UtcNow.ToString("o"),
                fps = 60,
                frameStepMs = 1000.0 / 60.0,
                windowSizeDip = new[] { ClientSize.Width, ClientSize.Height },
                windowSizePx = new[] { (int)Math.Ceiling(ClientSize.Width * scale), (int)Math.Ceiling(ClientSize.Height * scale) },
                dpiScale = scale,
                openMs = CapOpenMs, closeMs = CapCloseMs,
                sets = manifestSets,
            };
            System.IO.File.WriteAllText(
                System.IO.Path.Combine(outDir, "manifest.json"),
                System.Text.Json.JsonSerializer.Serialize(manifest, new System.Text.Json.JsonSerializerOptions { WriteIndented = true }));
        }
        catch (Exception ex) { LogCrash(ex); }
        finally
        {
            try { Close(); } catch { }
            Environment.Exit(0);
        }
    }

    // Drive ONE menu's open then close on the virtual clock, rendering every frame. Returns the frame-time lists.
    private async System.Threading.Tasks.Task<(System.Collections.Generic.List<double> open, System.Collections.Generic.List<double> close)>
        CaptureMenuSetAsync(string outDir, string theme, string menu, Action open)
    {
        var openTimes = new System.Collections.Generic.List<double>();
        var closeTimes = new System.Collections.Generic.List<double>();

        // Gap A — backdrop parity, CONTEXT MENU ONLY: the account-switcher sits over the acrylic sidebar, which
        // RenderTargetBitmap cannot capture (→ black), so its translucency, rim and modal scrim can't be compared.
        // Put the iOS-26 home-screen wallpaper there (laid out BEFORE open() so the glass freeze samples it). The
        // non-modal PULL-DOWN has no scrim and already renders over the launcher's own OPAQUE detail pane (its real
        // backdrop, over which its frozen split-spring measures cleanly); forcing the settings-page wallpaper behind
        // it only destabilises that fit without adding anything to compare, so we leave it on the real detail pane.
        if (menu == "context") { SetCaptureBackdrop(theme, menu); await NextFrameAsync(); }
        else CaptureBackdrop.IsVisible = false;

        // OPEN — ShowMenuFor lays out the geometry (AnimateMenu is skipped in capture mode).
        open();
        await NextFrameAsync();
        SetupMenuAnim(open: true);
        int openN = (int)Math.Round(CapOpenMs / (1000.0 / 60.0));
        for (int f = 0; f <= openN; f++)
        {
            _capMs = (long)Math.Round(f * 1000.0 / 60.0);
            MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0);
            await NextFrameAsync();
            SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_{menu}_open_{f:D3}.png"));
            openTimes.Add(Math.Round(f * 1000.0 / 60.0, 3));
        }

        // CLOSE — same geometry, spring-less accelerating collapse; scrim outlasts the shape.
        SetupMenuAnim(open: false);
        int closeN = (int)Math.Round(CapCloseMs / (1000.0 / 60.0));
        for (int f = 0; f <= closeN; f++)
        {
            _capMs = (long)Math.Round(f * 1000.0 / 60.0);
            MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0);
            await NextFrameAsync();
            SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_{menu}_close_{f:D3}.png"));
            closeTimes.Add(Math.Round(f * 1000.0 / 60.0, 3));
        }

        // teardown for the next set
        OverlayHost.IsVisible = false;
        MenuScrim.Opacity = 0;
        HideAnchorClone();
        LiquidGlassAvaloniaUI.LiquidGlassBackdrop.Unfreeze(MenuGlass);
        await NextFrameAsync();
        return (openTimes, closeTimes);
    }

    private Rect AnchorRectDip(Control a)
    {
        var p = a.TranslatePoint(new Point(0, 0), this) ?? new Point(0, 0);
        return new Rect(p.X, p.Y, a.Bounds.Width, a.Bounds.Height);
    }

    // Capture-only: open a menu, settle it, then drive the SELECTION CAPSULE through a hover sequence mirroring the iOS
    // drag recordings (enter, step rows with dwell, a two-row jump, leave, re-enter, click a row -> collapse). For a value
    // picker it first holds a few frames on the pre-placed capsule. Frames land as {theme}_{label}_{NNN}.png (run.py ignores
    // these — only *_context_* / *_pulldown_* open/close are measured). Deterministic: virtual clock, CapsuleTick per frame.
    private async System.Threading.Tasks.Task CaptureCapsuleSequenceAsync(string outDir, string theme, string label, Action open, bool valuePicker)
    {
        if (label.StartsWith("ctx")) { SetCaptureBackdrop(theme, "context"); await NextFrameAsync(); }
        else CaptureBackdrop.IsVisible = false;

        open();
        await NextFrameAsync();
        // Fast-forward the open morph to settled without saving frames.
        SetupMenuAnim(open: true);
        int openN = (int)Math.Round(CapOpenMs / (1000.0 / 60.0));
        for (int f = 0; f <= openN; f++) { _capMs = (long)Math.Round(f * 1000.0 / 60.0); MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0); }
        await NextFrameAsync();
        FinishMenuAnim();   // settle rows/scrim, mark _menuSettled, pre-place the value-picker capsule
        await NextFrameAsync();

        int idx = 0;
        long ms = 0;
        async System.Threading.Tasks.Task Hold(int frames)
        {
            for (int i = 0; i < frames; i++)
            {
                _capMs = ms; ms += (long)Math.Round(1000.0 / 60.0);
                CapsuleTick(1.0 / 60.0);
                await NextFrameAsync();
                SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_{label}_{idx:D3}.png"));
                idx++;
            }
        }

        int n = _capItemCount;
        if (valuePicker) await Hold(8);                       // rest on the pre-placed capsule
        ShowCapsuleAt(0, animate: true); await Hold(10);      // pointer enters the first row (fade in)
        for (int r = 1; r < Math.Min(n, 4); r++) { ShowCapsuleAt(r, animate: true); await Hold(8); }   // step down
        if (n >= 3) { ShowCapsuleAt(Math.Max(0, Math.Min(n, 4) - 3), animate: true); await Hold(10); }  // a two-row jump up
        OnMenuPointerExited(); await Hold(14);               // leave the rows (fade out, or spring back to value)
        ShowCapsuleAt(Math.Min(n - 1, 1), animate: true); await Hold(10);   // re-enter
        // click a row: keep the capsule lit, hold one frame, then run the collapse
        int clickRow = Math.Min(n - 1, 2);
        ShowCapsuleAt(clickRow, animate: false);
        await NextFrameAsync();
        SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_{label}_{idx:D3}.png")); idx++;
        SetupMenuAnim(open: false);
        int closeN = (int)Math.Round(CapCloseMs / (1000.0 / 60.0));
        for (int f = 0; f <= closeN; f++)
        {
            _capMs = (long)Math.Round(f * 1000.0 / 60.0);
            MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0);
            await NextFrameAsync();
            SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_{label}_{idx:D3}.png"));
            idx++;
        }

        OverlayHost.IsVisible = false;
        MenuScrim.Opacity = 0;
        HideAnchorClone();
        LiquidGlassAvaloniaUI.LiquidGlassBackdrop.Unfreeze(MenuGlass);
        await NextFrameAsync();
    }

    // Capture-only: a pull-down CLOSE followed by the TRIGGER-TEXT RETURN (spec §3b), so the strip shows the whole
    // "縮回去…文字淡出後回彈": full menu -> liquid collapse into the trigger -> grey value text springs back (blurred-then-sharp)
    // -> chevron returns LAST. Backdrop stays OFF so the launcher's own trigger (VersionBox) is visible for the return
    // (the return is on the launcher's trigger, not over iOS's wallpaper). pickValue=false is outside-dismiss, true is
    // select-dismiss — identical closes per spec §9, captured separately so both are on record. run.py ignores these labels.
    private async System.Threading.Tasks.Task CapturePulldownReturnAsync(string outDir, string theme, string label, bool pickValue)
    {
        CaptureBackdrop.IsVisible = false;
        ShowGlassMenu(VersionBox);
        await NextFrameAsync();
        SetupMenuAnim(open: true);
        int openN = (int)Math.Round(CapOpenMs / (1000.0 / 60.0));
        for (int f = 0; f <= openN; f++) { _capMs = (long)Math.Round(f * 1000.0 / 60.0); MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0); }
        await NextFrameAsync();
        FinishMenuAnim();
        await NextFrameAsync();

        int idx = 0;
        if (pickValue && _capItemCount > 0)   // select-dismiss: keep the capsule lit on the picked row through the close
        {
            ShowCapsuleAt(Math.Min(_capItemCount - 1, 1), animate: false);
            await NextFrameAsync();
        }

        SetupMenuAnim(open: false);
        int closeN = (int)Math.Round(CapCloseMs / (1000.0 / 60.0));
        for (int f = 0; f <= closeN; f++)
        {
            _capMs = (long)Math.Round(f * 1000.0 / 60.0);
            MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0);
            await NextFrameAsync();
            SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_{label}_{idx:D3}.png"));
            idx++;
        }
        // hide the overlay exactly as the live close does, then run the trigger-text return on the virtual clock
        OverlayHost.IsVisible = false;
        MenuScrim.Opacity = 0;
        HideAnchorClone();
        LiquidGlassAvaloniaUI.LiquidGlassBackdrop.Unfreeze(MenuGlass);
        SetupAnchorReturn(VersionBox);
        int retN = (int)Math.Round(620 / (1000.0 / 60.0));   // ~620 ms window (dark recovery ~360 ms + chevron-last + margin)
        for (int f = 0; f <= retN; f++)
        {
            bool done = AnchorReturnTick(f == 0 ? 0 : 1.0 / 60.0, f / 60.0);
            await NextFrameAsync();
            SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_{label}_{idx:D3}.png"));
            idx++;
            if (done) break;
        }
        FinishAnchorReturn();
        await NextFrameAsync();
    }

    // ---- Request 1 (capture-only): the account chip BEFORE press vs while the switcher is OPEN — same size, same place.
    // "before" is the closed launcher (real chip); "open" is the settled account switcher, where the lit clone must sit
    // pixel-exactly over the same spot at the same size (no lift, no shift). No wallpaper backdrop so both are comparable.
    private async System.Threading.Tasks.Task CaptureAccountChipSizeAsync(string outDir, string theme)
    {
        CaptureBackdrop.IsVisible = false;
        OverlayHost.IsVisible = false;
        await NextFrameAsync();
        SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_acctsize_before.png"));

        ShowAccountSwitcher();
        await NextFrameAsync();
        SetupMenuAnim(open: true);
        int openN = (int)Math.Round(CapOpenMs / (1000.0 / 60.0));
        for (int f = 0; f <= openN; f++) { _capMs = (long)Math.Round(f * 1000.0 / 60.0); MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0); }
        await NextFrameAsync();
        FinishMenuAnim();
        await NextFrameAsync();
        SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_acctsize_open.png"));

        OverlayHost.IsVisible = false;
        MenuScrim.Opacity = 0;
        HideAnchorClone();
        LiquidGlassAvaloniaUI.LiquidGlassBackdrop.Unfreeze(MenuGlass);
        await NextFrameAsync();
    }

    // ---- Request 2 (capture-only): the version picker's capsule appears ONLY under the pointer. Frames: settled with the
    // mouse NOT over any row (NO capsule), then the pointer enters a row and moves across rows (capsule follows), then the
    // pointer leaves (capsule fades out IN PLACE — it does NOT jump back to the current value).
    private async System.Threading.Tasks.Task CaptureVersionHoverAsync(string outDir, string theme)
    {
        CaptureBackdrop.IsVisible = false;
        ShowGlassMenu(VersionBox);
        await NextFrameAsync();
        SetupMenuAnim(open: true);
        int openN = (int)Math.Round(CapOpenMs / (1000.0 / 60.0));
        for (int f = 0; f <= openN; f++) { _capMs = (long)Math.Round(f * 1000.0 / 60.0); MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0); }
        await NextFrameAsync();
        FinishMenuAnim();
        await NextFrameAsync();

        int idx = 0; long ms = 0;
        async System.Threading.Tasks.Task Hold(int frames)
        {
            for (int i = 0; i < frames; i++)
            {
                _capMs = ms; ms += (long)Math.Round(1000.0 / 60.0);
                CapsuleTick(1.0 / 60.0);
                await NextFrameAsync();
                SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_verhover_{idx:D3}.png"));
                idx++;
            }
        }

        int n = _capItemCount;
        await Hold(8);                                                   // settled, pointer off the rows -> NO capsule
        ShowCapsuleAt(0, animate: true); await Hold(10);                 // pointer enters the first row (fade in)
        for (int r = 1; r < Math.Min(n, 4); r++) { ShowCapsuleAt(r, animate: true); await Hold(8); }   // move across rows
        OnMenuPointerExited(); await Hold(16);                           // leave -> fade out IN PLACE (no jump to current value)

        SetupMenuAnim(open: false);
        int closeN = (int)Math.Round(CapCloseMs / (1000.0 / 60.0));
        for (int f = 0; f <= closeN; f++)
        {
            _capMs = (long)Math.Round(f * 1000.0 / 60.0);
            MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0);
            await NextFrameAsync();
            SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_verhover_{idx:D3}.png"));
            idx++;
        }
        OverlayHost.IsVisible = false; MenuScrim.Opacity = 0; HideAnchorClone();
        LiquidGlassAvaloniaUI.LiquidGlassBackdrop.Unfreeze(MenuGlass);
        await NextFrameAsync();
    }

    // ---- Request 3 (capture-only): the version picker as a single SCROLLING column. Frames: the open morph (viewport
    // size), settled scrolled so 26.2 is near the top (bottom edge fade present, NOT highlighted), a wheel-scroll DOWN then
    // back UP (top/bottom edge fades appear and disappear), then a click on a row followed by the collapse.
    private async System.Threading.Tasks.Task CaptureVersionScrollAsync(string outDir, string theme)
    {
        CaptureBackdrop.IsVisible = false;
        ShowGlassMenu(VersionBox);
        await NextFrameAsync();
        int idx = 0;
        SetupMenuAnim(open: true);
        int openN = (int)Math.Round(CapOpenMs / (1000.0 / 60.0));
        for (int f = 0; f <= openN; f++)
        {
            _capMs = (long)Math.Round(f * 1000.0 / 60.0);
            MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0);
            await NextFrameAsync();
            SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_verscroll_{idx:D3}.png"));
            idx++;
        }
        FinishMenuAnim();
        await NextFrameAsync();
        SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_verscroll_{idx:D3}.png")); idx++;   // settled, scrolled to current value

        // Scroll gradually top -> bottom -> top so intermediate frames show BOTH edge fades at once (the list is only a bit
        // taller than the viewport, so one real wheel notch would jump straight to the end; here we step in small amounts).
        double extentAll = _menuScroller?.ScrollBarMaximum.Y ?? 0;
        void ScrollTo(double y)
        {
            if (_menuScroller is null) return;
            _menuScroller.Offset = new Vector(0, Math.Max(0, Math.Min(extentAll, y)));
            OnMenuScrollChanged();
        }
        const int scrollSteps = 8;
        for (int s = 1; s <= scrollSteps; s++) { ScrollTo(extentAll * s / scrollSteps); await NextFrameAsync(); SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_verscroll_{idx:D3}.png")); idx++; }   // down
        for (int s = scrollSteps - 1; s >= 0; s--) { ScrollTo(extentAll * s / scrollSteps); await NextFrameAsync(); SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_verscroll_{idx:D3}.png")); idx++; }   // back up

        // Click a visible row: light the capsule on it, hold a frame, then run the collapse.
        int clickRow = Math.Min(_capItemCount - 1, 2);
        ShowCapsuleAt(clickRow, animate: false);
        await NextFrameAsync();
        SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_verscroll_{idx:D3}.png")); idx++;
        SetupMenuAnim(open: false);
        int closeN = (int)Math.Round(CapCloseMs / (1000.0 / 60.0));
        for (int f = 0; f <= closeN; f++)
        {
            _capMs = (long)Math.Round(f * 1000.0 / 60.0);
            MenuTick(f / 60.0, f == 0 ? 0 : 1.0 / 60.0);
            await NextFrameAsync();
            SaveWindowPng(System.IO.Path.Combine(outDir, $"{theme}_verscroll_{idx:D3}.png"));
            idx++;
        }
        OverlayHost.IsVisible = false; MenuScrim.Opacity = 0; HideAnchorClone();
        LiquidGlassAvaloniaUI.LiquidGlassBackdrop.Unfreeze(MenuGlass);
        await NextFrameAsync();
    }

    // Gap A — load the iOS-26 closed-menu wallpaper for this (theme, menu) into the capture-only backdrop image.
    // ctx_* = the home-screen wallpaper the context menu opens over; pd_* = the settings page the pull-down covers.
    // These are near-featureless grey/black gradients (spec §7, blur-invariant), so UniformToFill across the window
    // reproduces the same wallpaper region the glass sits over without needing pixel-exact px/pt alignment.
    private void SetCaptureBackdrop(string theme, string menu)
    {
        try
        {
            string key = menu == "pulldown" ? $"pd_{theme}" : $"ctx_{theme}";
            var uri = new Uri($"avares://S1mp1e/Assets/capture/{key}.png");
            Bitmap bmp = new Bitmap(AssetLoader.Open(uri));
            // Backdrop PARITY (capture-only, DARK context only): iOS 26's Settings menu sat over a wallpaper region of L~184,
            // but the launcher's account chip anchors bottom-left over a BRIGHTER region of the same wallpaper (L~229). With the
            // now-opaque dark body, the body reading is backdrop-independent, but that brighter surround (a) throws the measured
            // body-vs-backdrop LIFT (iOS -88) too deep and (b) makes the dark side-by-side strip read brighter than iOS around
            // the menu. Dim the DARK ctx wallpaper to iOS's level so the menu is composited/compared over an equivalent
            // backdrop. LIGHT is left untouched: its translucent "Regular" body is tuned to its own backdrop and dimming would
            // break its (in-tolerance) measured lift. Asset stays pristine; this is a virtual-capture-only adjustment.
            if (menu == "context" && theme == "dark") bmp = DimBitmapToBlack(bmp, 0.80);
            CaptureBackdrop.Source = bmp;
            CaptureBackdrop.IsVisible = true;
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // Scale a bitmap's colour channels toward black by <paramref name="factor"/> (alpha untouched), for capture backdrop
    // parity. Channel ORDER is irrelevant here — the wallpaper is near-neutral grey and all three colour bytes are scaled
    // equally — so this is correct whether the decoded pixels are BGRA or RGBA; the alpha byte (index +3) is left alone.
    private static Bitmap DimBitmapToBlack(Bitmap src, double factor)
    {
        try
        {
            var size = src.PixelSize;
            int stride = size.Width * 4;
            int bytes = stride * size.Height;
            var scratch = System.Runtime.InteropServices.Marshal.AllocHGlobal(bytes);
            var buf = new byte[bytes];
            try
            {
                src.CopyPixels(new PixelRect(0, 0, size.Width, size.Height), scratch, bytes, stride);
                System.Runtime.InteropServices.Marshal.Copy(scratch, buf, 0, bytes);
            }
            finally { System.Runtime.InteropServices.Marshal.FreeHGlobal(scratch); }
            for (int i = 0; i < bytes; i += 4)
            {
                buf[i]     = (byte)(buf[i]     * factor);
                buf[i + 1] = (byte)(buf[i + 1] * factor);
                buf[i + 2] = (byte)(buf[i + 2] * factor);
            }
            var wb = new WriteableBitmap(size, src.Dpi, PixelFormat.Bgra8888, AlphaFormat.Premul);
            using (var fb = wb.Lock())
                System.Runtime.InteropServices.Marshal.Copy(buf, 0, fb.Address, bytes);
            return wb;
        }
        catch { return src; }   // any format surprise: fall back to the undimmed wallpaper rather than crash the capture
    }

    // Render the whole window (root content, so the scrim + menu + backdrop are all included) into a PNG.
    private void SaveWindowPng(string path)
    {
        try
        {
            var root = (this.Content as Control) ?? (Control)this;
            var sizeDip = ClientSize;
            if (sizeDip.Width < 1 || sizeDip.Height < 1) return;
            double scale = (TopLevel.GetTopLevel(this)?.RenderScaling) ?? 1.0;
            var px = new PixelSize(
                Math.Max(1, (int)Math.Ceiling(sizeDip.Width * scale)),
                Math.Max(1, (int)Math.Ceiling(sizeDip.Height * scale)));
            using var rtb = new RenderTargetBitmap(px, new Vector(96 * scale, 96 * scale));
            rtb.Render(root);
            rtb.Save(path);
        }
        catch (Exception ex) { LogCrash(ex); }
    }

    // Await one compositor frame so the state just set is laid out before we snapshot it. Frame TIMING stays virtual.
    private System.Threading.Tasks.Task NextFrameAsync()
    {
        var tcs = new System.Threading.Tasks.TaskCompletionSource();
        var top = TopLevel.GetTopLevel(this);
        if (top is null) { tcs.SetResult(); return tcs.Task; }
        top.RequestAnimationFrame(_ => tcs.SetResult());
        return tcs.Task;
    }

    private static double MenuSmooth(double a, double b, double t)
    {
        double x = Math.Clamp((t - a) / (b - a), 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static void SetMenuRow(MenuRowAnim r, double opacity, double blur)
    {
        r.Row.Opacity = opacity;
        if (blur > 0.25) { r.Blur.Radius = blur; r.Row.Effect = r.Blur; }
        else r.Row.Effect = null;
    }

    private void SetAnchorBlur(Control a, double blur)
    {
        if (blur > 0.25) { _anchorBlur.Radius = blur; a.Effect = _anchorBlur; }
        else if (ReferenceEquals(a.Effect, _anchorBlur)) a.Effect = null;
    }

    private void RestoreAnchor(Control a)
    {
        a.Opacity = 1;
        if (ReferenceEquals(a.Effect, _anchorBlur)) a.Effect = null;
        LiquidGlassAvaloniaUI.LiquidGlassBackdrop.SetIsExcludedFromCapture(a, false);
    }

    /// <summary>After a pull-down closes, its grey value text re-forms in place and SPRINGS back — invisible, then a
    /// blurred bright blob, then sharp — on a high-damping spring, and the chevron returns ~110 ms LATER (spec §3b).
    /// This is the user's core "縮回去…文字淡出後回彈" ask. Driven by the live RAF loop; the capture pump uses the same
    /// Setup/Tick/Finish trio on the virtual clock.</summary>
    private void StartAnchorReturn(Control a)
    {
        if (a is not S1mp1e.Controls.GlassSelect gs) { RestoreAnchor(a); return; }
        int g = ++_anchorGen;
        SetupAnchorReturn(gs);
        var top = TopLevel.GetTopLevel(this);
        if (top is null) { FinishAnchorReturn(); return; }
        var clock = new S1mp1e.Controls.GlassMotion.Clock();
        void Frame()
        {
            if (g != _anchorGen) return;   // a new menu took this trigger over
            double dt = clock.Tick();
            double t = (NowMs() - _retStartMs) / 1000.0;
            if (AnchorReturnTick(dt, t)) { FinishAnchorReturn(); return; }
            top.RequestAnimationFrame(_ => Frame());
        }
        Frame();
    }

    /// <summary>Arm the trigger-text return: value + chevron start hidden, the value carries a high-damping spring.</summary>
    private void SetupAnchorReturn(S1mp1e.Controls.GlassSelect a)
    {
        bool dark = ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
        _retAnchor = a;
        _retStartMs = NowMs();
        // §3b: response ~0.45-0.50 s, dampingFraction ~0.78-0.81 (bounce ~0.20), overshoot 1-3 % (opacity stays monotone;
        // the spring drives the SCALE rebound and the easing curve).
        _retSpring = new S1mp1e.Controls.GlassMotion.Spring(0.47, 0).Tune(0.47, 0.20);
        _retSpring.Retarget(1);
        // Edges clear later than brightness (blurred-then-sharp): dark legibility recovery ~344-368 ms, light ~174-189 ms.
        _retBlur0 = 0.05; _retBlur1 = dark ? 0.36 : 0.185;
        a.Opacity = 1;   // the parent trigger is lit; the two child glyphs carry the return on their own clocks
        if (a.ValueText is not null) { a.ValueText.Opacity = 0; a.ValueText.RenderTransformOrigin = new RelativePoint(1, 0.5, RelativeUnit.Relative); }
        if (a.Chevron  is not null)   a.Chevron.Opacity  = 0;
        SetAnchorBlur(a, 4);
    }

    /// <summary>One frame of the trigger-text return at spring step <paramref name="dt"/> (s) and time <paramref name="t"/>
    /// (s since the return began). Returns true once finished.</summary>
    private bool AnchorReturnTick(double dt, double t)
    {
        var a = _retAnchor;
        if (a is null || _retSpring is null) return true;
        _retSpring.Update(dt);
        double p = _retSpring.X;                    // 0 -> ~1.02 -> 1 (high-damping spring, slight overshoot)
        double op = Math.Clamp(p, 0, 1);            // opacity is MONOTONIC (no visible opacity bounce, §3b)
        if (a.ValueText is not null)
        {
            a.ValueText.Opacity = op;
            // the visible "回彈": the text re-coalesces from the anchor (right) side and scales 0.97 -> 1.0 with the spring's
            // tiny overshoot (a cross-mode-safe substitute for the dark clip's ~15 px horizontal reform, spec §3b.4).
            double sc = 0.97 + 0.03 * p;
            a.ValueText.RenderTransform = Math.Abs(sc - 1) < 0.002 ? null : new ScaleTransform(sc, sc);
        }
        SetAnchorBlur(a, 4 * (1 - MenuSmooth(_retBlur0, _retBlur1, t)));   // edges sharpen after brightness
        if (a.Chevron is not null)
            a.Chevron.Opacity = t <= RetChevronDelay ? 0 : MenuSmooth(RetChevronDelay, _retBlur1 + 0.02, t);   // chevron LAST
        return t >= _retBlur1 && Math.Abs(p - 1) < 0.01;
    }

    private void FinishAnchorReturn()
    {
        var a = _retAnchor;
        if (a is not null)
        {
            a.Opacity = 1;
            if (a.ValueText is not null) { a.ValueText.Opacity = 1; a.ValueText.RenderTransform = null; }
            if (a.Chevron  is not null)   a.Chevron.Opacity  = 1;
            if (ReferenceEquals(a.Effect, _anchorBlur)) a.Effect = null;
            LiquidGlassAvaloniaUI.LiquidGlassBackdrop.SetIsExcludedFromCapture(a, false);   // text is back: capture it again
        }
        _retAnchor = null;
        _retSpring = null;
    }

    /// <summary>One frame of the menu morph. pw / ph are the width / height spring positions (0 = the trigger box,
    /// 1 = the menu, above 1 = overshoot); every edge interpolates between the two boxes. The corners blend from a
    /// capsule (cornerK 0) to the panel radius (cornerK 1).</summary>
    private void ApplyMenuFrame(double pw, double ph, double cornerK, double glassA, double labelScale, double zoom)
    {
        double menuR = _menuLeft + _menuW, menuB = _menuTop + _menuH;
        double l = _seedL + (_menuLeft - _seedL) * pw, r = _seedR + (menuR - _seedR) * pw;
        double t = _seedT + (_menuTop - _seedT) * ph, b = _seedB + (menuB - _seedB) * ph;
        double w = Math.Max(4, r - l), h = Math.Max(4, b - t);
        Canvas.SetLeft(MenuRoot, l);
        Canvas.SetTop(MenuRoot, t);
        MenuRoot.Width = w;
        MenuRoot.Height = h;
        // iOS continuous corner: the outline morphs from a capsule (superellipse n = 2, a = half the short side) to the settled
        // squircle (n = 2.8, semi-axis a = 1.31 R, R = 0.25 h — the measured continuous corner, smoothing 0.66). The lens SDF,
        // highlight, shadow, clip and rim all share (a, n), so nothing shows a seam.
        double capsule = Math.Min(w, h) / 2;
        double k = Math.Clamp(cornerK, 0, 1);
        double a = Math.Max(0, Math.Min(capsule, capsule + (1.31 * _menuRadius - capsule) * k));
        // Settled continuous-corner exponent. Context stays ~2.5 (soft, re-measured); the PULL-DOWN settles SQUARER
        // (n ~4.5, spec IOS26_PULLDOWN_SPEC §5) while still being born near-elliptical (n~2 while small), so the morph
        // reads ellipse -> squircle. Birth is near-elliptical in both because k -> 0 as the nub shrinks.
        double n = 2 + (_anchorFades ? 2.5 : 0.5) * k;
        MenuGlass.CornerRadius = new CornerRadius(a);
        MenuGlass.CornerExponent = n;
        var outline = LiquidGlassAvaloniaUI.LiquidGlassShapes.CreateSquircleGeometry(new Rect(0, 0, w, h), a, n);
        MenuClip.Clip = outline;
        MenuRimPath.Width = w;
        MenuRimPath.Height = h;
        MenuRimPath.Data = outline;
        MenuGlass.Opacity = glassA;
        MenuRimPath.Opacity = glassA;
        MenuGlass.BackdropZoom = 1.0;   // faithful: interior stays 1× — the circle-map lens does the edge magnification, not a zoom
        _ = zoom;                       // (kept in the signature; the reference panel has no centre magnifier)
        // context: the content scales WITH the box (iOS scales the menu's content as the panel grows — verified by the row
        // pitch growing 64 -> 86 -> 107 px through the open); pull-down uses labelScale for its lens magnify instead.
        double contentScale = _anchorFades ? labelScale : (w / Math.Max(1.0, _menuW));
        MenuItems.RenderTransform = Math.Abs(contentScale - 1) < 0.002 ? null : new ScaleTransform(contentScale, contentScale);
    }

    /// <summary>Menu glass per theme, from the reference: dark = a translucent cool-grey frosted panel (the rows behind
    /// read as soft blobs) with a bright top rim; light = a near-opaque frosted white with a darker hairline and a
    /// clearly visible drop shadow. Both bend the backdrop at the rim with the 26.2 edge model.</summary>
    private void ApplyMenuGlassTheme(bool contextMenu = false)
    {
        bool dark = ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
        var g = MenuGlass;
        // Faithful circle-map lens (reference dialog/panel: refractionHeight 12, amount -24, saturation 1.5,
        // brightness 0.2). Interior stays 1× via the circle-map early-out — NO Snell, NO BackdropZoom (see ApplyMenuFrame).
        g.SnellRefraction = true;   // MC 26.2 hotbar glass.fsh (reglass defaults): Snell edge band, IOR 1.4, dispersion 7 * 0.015
        g.SnellThickness = 13.15;                // REF_THICKNESS: a fixed ~13 px band from the rim
        g.SnellIor = 1.4;
        g.SnellDispersion = 0.105;               // REF_DISP 7 * (1.015 - 1)
        g.SnellOffset = 0.08 * ((TopLevel.GetTopLevel(this)?.ClientSize.Height) ?? 720);   // OFFSET_SCALE 0.08 * screen height: the rim samples far into the backdrop
        g.BackdropZoom = 1.0;
        g.RefractionHeight = 5;           // visible refraction band ~0.14 P; 12/24 warped 11 % of the height
        g.RefractionAmount = 8;
        g.DepthEffect = true;
        g.ChromaticAberration = false;
        g.BlurRadius = 1.5;   // hotbar strip frost: (1 - a) * 4 px with a 0.5 -> ~2 px Gaussian
        g.Vibrancy = 1.0;   // glass.fsh: no colour grading of any kind
        g.Brightness = 0;                 // the two-point body/card solve needs no pre-gain; the surface colour does the lift
        g.TintColor = Colors.Transparent;
        // Body tint. The iOS 26 dark CONTEXT menu is a ~41 %-opaque neutral grey (spec §10): body sits +24 L above the card
        // under it (measured L49 over L25). We match that for the context menu (0.41 alpha over an L102 grey -> +24 L over the
        // launcher's card). The PULL-DOWN stays the near-clear sheet (user's 2026-09-19 "只是一個透明片" decision), and the
        // light menus stay the whitish "Regular" material. [Design-intent note: the grey context body partly reverses the
        // clear-glass look for that one menu — it is the iOS-faithful choice; flip CtxBodyGrey to revert.]
        g.SurfaceColor = (contextMenu && dark) ? CtxBodyGrey
                       : dark ? Color.FromArgb(0x55, 0x2A, 0x2A, 0x2C) : Color.FromArgb(0x9A, 0xFC, 0xFC, 0xFE);
        // Recorded rim: a SOFT luminous glow (top edge L92 vs body L49), brightest along the top, not a crisp outline.
        g.HighlightEnabled = true;   // Fresnel rim: uniform all round (not directional), ~3 px, white at ~14 %
        // Measured dark rim: a ~1 px hairline at 1.99x the body on top (1.88x bottom), wider/softer on the sides (FWHM 8-12 px,
        // 1.45-1.8x). HighlightWidth 0.5 -> a 1 px stroke (the vendored stroke is 2*width, no longer rounded up to 2 px).
        // iOS 26 directional rim (spec §10; confirmed by the pull-down re-measure): a bright specular TOP + BOTTOM rim with a
        // soft inner glow, and thin DARK refraction hairlines on the LEFT/RIGHT. The Fresnel highlight (white, additive, |·|)
        // handles the symmetric bright top+bottom; the side darks come from MenuRimPath's cross-axis gradient (source-only,
        // no vendor edit needed — see the rim-path brush below).
        // iOS 26 dark rim is DIRECTIONAL: bright specular TOP (+64 L), a weaker bottom (~+20 L, ~1/3 the top), thin dark side
        // hairlines. The context menu uses the vendor's new HighlightBackRim (0.33 = bottom at 1/3 top) with a brighter, wider
        // top glow; the pull-down keeps the symmetric rim it was tuned to.
        g.HighlightOpacity = contextMenu ? (dark ? 0.85 : 0.30) : (dark ? 0.34 : 0.24);
        // dark context: directional (bright top, ~1/3 bottom) per iOS §10; light context: symmetric bright top+bottom (§10 light).
        g.HighlightBackRim = (contextMenu && dark) ? 0.33 : 1.0;
        g.HighlightFalloff = 1.5;        // concentrate the bright specular on the top (and, symmetric-mode, bottom), dim on the sides
        g.HighlightWidth = 1.5;
        g.HighlightBlurRadius = contextMenu ? 8 : 6;   // context: wider soft top inner-glow (~16 px); pull-down keeps ~12 px
        g.HighlightAngle = -90;          // straight down: top edge is the light-facing one
        g.ShadowEnabled = true;
        // With the backdrop scrim now carrying the depth, the drop shadow is minimal in dark and a soft downward-biased
        // lobe in light (spec §9/§13.9; light reach measured ~40-65 px below, so softer + offset down, not the tight r6).
        if (dark)
        {
            g.ShadowRadius = 8;
            g.ShadowOffset = new Vector(0, 2);
            g.ShadowColor = Color.FromArgb(0x0A, 0, 0, 0);   // near-off: depth is the scrim + rim
        }
        else
        {
            g.ShadowRadius = 18;
            g.ShadowOffset = new Vector(0, 6);               // downward-biased soft lobe
            g.ShadowColor = Color.FromArgb(0x2A, 0, 0, 0);
        }
        g.ShadowOpacity = 1;
        // Cross-axis (left->right) gradient: dark at the very edges (the L/R refraction hairlines), clear across the middle
        // (where the Fresnel glow does the bright top/bottom). One brush, source-only — the launcher's rim path can only vary
        // along a single axis, and this is the axis that yields the dark side hairlines iOS shows.
        var sideDark = dark ? Color.FromArgb(0x42, 0, 0, 0) : Color.FromArgb(0x24, 0, 0, 0);
        var clearSide = Color.FromArgb(0, 0, 0, 0);
        MenuRimPath.Stroke = new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0, 0.5, RelativeUnit.Relative),
            EndPoint = new RelativePoint(1, 0.5, RelativeUnit.Relative),
            GradientStops = new GradientStops
            {
                new GradientStop(sideDark, 0.0),
                new GradientStop(clearSide, 0.14),
                new GradientStop(clearSide, 0.86),
                new GradientStop(sideDark, 1.0),
            },
        };
    }

    // one menu row: just a label inside a transparent Border (Border.menurow). NO check mark and NO reserved check
    // gutter anywhere (iOS 26 spec §6/§10.5) — the single sliding selection capsule is the ONLY selection/hover cue.
    private Border BuildMenuRow(string text, bool selected) => BuildMenuRow(text, selected, disabled: false);
    private Border BuildMenuRow(string text, bool selected, bool disabled, bool centered = false,
                               bool contextMenu = false, bool destructive = false)
    {
        _ = selected;   // selection is shown by the capsule's position now, never per-row
        // Context-menu rows follow the iOS 26 scale (row 44 / text 17, ONE knob IosPt); destructive rows use systemRed.
        bool darkTheme = ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
        var destColor = darkTheme ? Color.FromRgb(0xFA, 0x78, 0x7A) : Color.FromRgb(0xD4, 0x29, 0x2E);   // spec §11 rendered systemRed
        double textSize = contextMenu ? CtxTextSize : 13;
        // An "add" row keeps its leading ＋ inline (it is an affordance, not a check mark), so we render the label text
        // verbatim — no gutter, no glyph reflow.
        var label = new TextBlock { Text = text, VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center, FontSize = textSize };
        // Disabled rows render in the secondary/muted colour and don't get the hover cursor. Destructive rows render systemRed.
        if (destructive && !disabled) label.Foreground = new SolidColorBrush(destColor);
        else label.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable(disabled ? "TextSub" : "TextMain"));

        Control content;
        if (centered)
        {
            // multi-column (version grid): the label is centred in its cell; the capsule sits behind it.
            label.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
            var cell = new Grid { ClipToBounds = false };
            cell.Children.Add(label);
            _menuCellLabels.Add(label);
            content = cell;
        }
        else
        {
            label.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left;
            content = label;
        }

        var row = new Border
        {
            CornerRadius = new CornerRadius(contextMenu ? 10 : 12),
            Margin = new Thickness(4, 0),
            // Context menu: fixed 44 pt row pitch, content vertically centred, iOS side inset. Pull-down keeps its tighter look.
            Height = contextMenu ? CtxRowHeight : double.NaN,
            Padding = contextMenu ? new Thickness(CtxRowPadH, 0)
                    : centered   ? new Thickness(10, 7, 10, 7)
                                 : new Thickness(14, 7, 14, 7),   // iOS: labels left-aligned, symmetric inset (no check gutter)
            Background = Brushes.Transparent,
            Cursor = new Avalonia.Input.Cursor(disabled ? Avalonia.Input.StandardCursorType.Arrow : Avalonia.Input.StandardCursorType.Hand),
            Child = content,
            Opacity = disabled ? 0.55 : 1,
        };
        row.Classes.Add(disabled ? "menurow-disabled" : "menurow");
        row.Tag = null;
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
            if (PerfToggle is not null) PerfToggle.IsChecked = s.PerfPack;
            if (DemoToggle is not null) DemoToggle.IsChecked = s.ReduceTransparency;
            // Theme: restore the saved 自動/淺色/深色 choice (was previously never
            // persisted, so it reset to 自動 on every launch).
            if (ThemeBox is not null)
                ThemeBox.SelectedIndex = s.Theme switch { "light" => 1, "dark" => 2, _ => 0 };
            if (MenuKeyBox is not null)
            {
                // The shared modules.json is the live truth: an in-game rebind writes it,
                // so prefer its GLFW code over the launcher's own remembered LWJGL one.
                var shared = S1mp1eModConfig.ReadMenuKey(EffectiveMcDir());
                SetGlassSelect(MenuKeyBox, S1mp1eModConfig.LabelFor(shared ?? s.MenuKey));
            }
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

    // The in-game config-GUI open key. Writes launcher settings (LWJGL code, unchanged so
    // existing configs stay valid) AND the SHARED <.minecraft>/s1mp1e-mods/modules.json the
    // client mod reads on every version (GLFW code). The in-game rebind writes the same
    // field, so either path works and both apply to all versions at once.
    private void OnMenuKeyChanged(object? sender, EventArgs e)
    {
        if (_hydrating) return;
        var label = (sender as GlassSelect)?.SelectedText ?? MenuKeyBox.SelectedText ?? "Right Shift";
        int lwjgl = 54, glfw = 344;
        foreach (var c in S1mp1eModConfig.KeyChoices)
            if (c.Label == label) { lwjgl = c.Lwjgl; glfw = c.Glfw; break; }
        _cfg.Settings.MenuKey = lwjgl;
        SaveCfg();
        try { S1mp1eModConfig.WriteMenuKey(EffectiveMcDir(), glfw); } catch (Exception ex) { LogCrash(ex); }
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

    // The inverse: pre-1.13 MC (1.12.2) — our liquid glass there is a FORGE coremod, so
    // Fabric is greyed and the loader auto-switches to Forge, mirroring how 26.2 forces
    // Fabric. 1.8.9 is the exception: its "Fabric" is Ornithe (Java 25 + Pylon + Argentum,
    // 1000+ fps; Forge 1.8.9 mods still load through the S1mp1e Forge compat layer), and the
    // classic Forge profile stays selectable as a fallback.
    private static bool IsForgeOnly(string mc)
    {
        if (string.IsNullOrEmpty(mc) || mc == "26.2" || mc == "1.8.9") return false;
        var parts = mc.Split('.');
        if (parts.Length >= 2 && parts[0] == "1" && int.TryParse(parts[1], out var x))
            return x < 13;
        return false;
    }

    // Which loaders a version can actually use: 1.12.2 = Forge only; 1.8.9 = Fabric (Ornithe)
    // or Forge; 26.2 fork = Fabric only; 1.13+ = Fabric. Forge is greyed on everything ≥1.13.
    private static bool LoaderAllowed(string mc, string loader)
    {
        loader = (loader ?? "").ToLowerInvariant();
        if (IsForgeOnly(mc)) return loader == "forge";
        if (mc == "26.2")   return loader == "fabric";
        if (IsFabricOnly(mc)) return loader == "fabric";
        return true;
    }

    // 1.8.9 + Fabric = the Ornithe line (installed as `fabric-loader-<v>-ornithe-1.8.9`).
    private static bool IsOrnithe(string mc, string loader)
        => mc == "1.8.9" && !string.Equals(loader, "forge", StringComparison.OrdinalIgnoreCase);

    // Which Modrinth loader to search/download mods with. On 1.8.9 that is always Forge:
    // the Ornithe line loads Forge 1.8.9 mods through the S1mp1e Forge compat layer, and
    // Modrinth's 1.8.9 "fabric" builds are Legacy Fabric ones that Ornithe can't load.
    private static string ModLoaderFor(string mc, string loader)
        => mc == "1.8.9" ? "forge" : loader.ToLowerInvariant();

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
        StartNavSub.Text = IsOrnithe(mc, ldr) ? $"{mc} · Fabric（Ornithe）" : $"{mc} · {ldr}";
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
                    // 1.8.9's Fabric is Ornithe only (a Legacy Fabric 1.8.9 profile another
                    // launcher left behind can't run our 1.8.9 line)
                    if (id.StartsWith("fabric-loader", StringComparison.OrdinalIgnoreCase)
                        && id.EndsWith("-" + mc, StringComparison.OrdinalIgnoreCase)
                        && (mc != "1.8.9" || id.Contains("-ornithe-", StringComparison.OrdinalIgnoreCase))) return true;
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
            _cfg.Settings.RamMb = (int)Math.Round(gs.Value) * 1024;   // saved once the drag/typing is finished
    }
    private void OnRamCommitted(object? sender, EventArgs e)
    {
        if (_hydrating) return;
        SaveCfg();
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
    private void OnPerfToggleChanged(object? sender, RoutedEventArgs e)
    {
        if (_hydrating || PerfToggle is null) return;
        _cfg.Settings.PerfPack = PerfToggle.IsChecked == true;
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

    // ================= Request 3: scrollable single-column pull-down =================
    // The version / menu-key pickers open into this reused ScrollViewer instead of the old 3-column grid. Smooth wheel
    // scrolling (same exp-decay feel as the detail pane), an iOS soft edge fade driven by scroll offset, and the selection
    // capsule (which lives in the un-scrolled MenuCapsuleLayer) re-pinned to its row on every scroll.
    private void EnsureLazyMenuScroller()
    {
        if (_menuScroller is not null) return;
        _menuScrollPanel = new StackPanel();
        _menuScroller = new ScrollViewer
        {
            Content = _menuScrollPanel,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            VerticalScrollBarVisibility = ScrollBarVisibility.Hidden,   // no scrollbar clutter — the edge fade + a half-row peek signal scrolling
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Top,
        };
        // Intercept the wheel BEFORE the ScrollViewer's own snap-scroll and drive the smooth tween ourselves.
        _menuScroller.AddHandler(InputElement.PointerWheelChangedEvent, OnMenuScrollWheel,
            RoutingStrategies.Tunnel | RoutingStrategies.Bubble, handledEventsToo: true);
        _menuScroller.ScrollChanged += (_, _) => OnMenuScrollChanged();
    }

    // Pull-down (下拉選單) ONLY: the user asked for NO edge fade in the dropdown — the scrolling version list is hard-clipped
    // at the viewport edges (crisp rows, no top/bottom dissolve). _menuScroller is used exclusively by pull-downs (context
    // menus / short pickers never scroll), so clearing its mask here is scoped to the dropdown and leaves every other menu's
    // fades untouched. Kept as a method (still called on open / scroll) so any previously-set mask is cleared.
    private void UpdateMenuScrollFade()
    {
        if (_menuScroller is null) return;
        _menuScroller.OpacityMask = null;
    }

    private void ApplyMenuInitScroll()
    {
        if (_menuScroller is null) return;
        double max = _menuScroller.ScrollBarMaximum.Y;
        _menuScroller.Offset = new Vector(0, max <= 0 ? 0 : Math.Clamp(_menuInitScrollY, 0, max));
        _menuScrollTarget = double.NaN;
        UpdateMenuScrollFade();
    }

    // Scroll changed (wheel, keyboard, or the open pre-scroll): refresh the edge fade and rigidly re-pin the capsule to its
    // current row (the row moved under a stationary pointer; pointer-enter events retarget it to whatever row it now covers).
    private void OnMenuScrollChanged()
    {
        UpdateMenuScrollFade();
        if (_menuScrolling && _capActive && _menuSettled && _capOpacity > 0.01
            && _capIndex >= 0 && _capIndex < _menuRows.Count)
        {
            var (x, y) = CapsuleTargetPos(_capIndex);   // TranslatePoint already reflects the new scroll offset
            _capX.Snap(x); _capY.Snap(y);
            Canvas.SetLeft(MenuCapsule, x);
            Canvas.SetTop(MenuCapsule, y);
        }
    }

    private void OnMenuScrollWheel(object? sender, PointerWheelEventArgs e)
    {
        if (_menuScroller is null || !_menuScrolling) return;
        e.Handled = true;
        double extent = _menuScroller.ScrollBarMaximum.Y;
        if (extent <= 0) return;
        if (double.IsNaN(_menuScrollTarget)) _menuScrollTarget = _menuScroller.Offset.Y;
        _menuScrollTarget = Math.Max(0, Math.Min(extent, _menuScrollTarget - e.Delta.Y * ScrollStep));
        if (_captureMode) { _menuScroller.Offset = new Vector(0, _menuScrollTarget); _menuScrollTarget = double.NaN; return; }
        if (_menuScrollTimer is null)
            _menuScrollTimer = new DispatcherTimer(TimeSpan.FromMilliseconds(3), DispatcherPriority.Render, OnMenuScrollTick);
        if (!_menuScrollTimer.IsEnabled) { _menuScrollSw.Restart(); _menuScrollTimer.Start(); }
    }

    private void OnMenuScrollTick(object? sender, EventArgs e)
    {
        if (_menuScroller is null || !OverlayHost.IsVisible || double.IsNaN(_menuScrollTarget)) { _menuScrollTimer?.Stop(); return; }
        var dt = _menuScrollSw.Elapsed.TotalSeconds; _menuScrollSw.Restart();
        if (dt > 0.05) dt = 0.05;
        double extent = _menuScroller.ScrollBarMaximum.Y;
        if (extent > 0) _menuScrollTarget = Math.Max(0, Math.Min(extent, _menuScrollTarget));
        double cur = _menuScroller.Offset.Y;
        double diff = _menuScrollTarget - cur;
        if (Math.Abs(diff) < 0.3)
        {
            _menuScroller.Offset = new Vector(0, _menuScrollTarget);
            _menuScrollTimer?.Stop(); _menuScrollTarget = double.NaN;
            return;
        }
        _menuScroller.Offset = new Vector(0, cur + diff * (1.0 - Math.Exp(-ScrollDecay * dt)));
    }

    // Keyboard paging: bring row i fully into the viewport (snap in capture, smooth-tween live).
    private void EnsureMenuRowVisible(int i)
    {
        if (!_menuScrolling || _menuScroller is null || i < 0 || i >= _menuRows.Count) return;
        double vh = _menuScroller.Bounds.Height; if (vh < 1) vh = _menuScroller.Height;
        if (vh < 1 || double.IsNaN(vh)) return;
        double rowTop = i * _menuRowPitch;               // uniform single column → content coords are exact
        double rowBot = rowTop + _menuRowPitch;
        double y = _menuScroller.Offset.Y;
        double max = _menuScroller.ScrollBarMaximum.Y;
        double newY = y;
        if (rowTop < y) newY = rowTop;
        else if (rowBot > y + vh) newY = rowBot - vh;
        newY = Math.Clamp(newY, 0, max <= 0 ? 0 : max);
        if (Math.Abs(newY - y) < 0.5) return;
        if (_captureMode) { _menuScroller.Offset = new Vector(0, newY); return; }
        _menuScrollTarget = newY;
        if (_menuScrollTimer is null)
            _menuScrollTimer = new DispatcherTimer(TimeSpan.FromMilliseconds(3), DispatcherPriority.Render, OnMenuScrollTick);
        if (!_menuScrollTimer.IsEnabled) { _menuScrollSw.Restart(); _menuScrollTimer.Start(); }
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
            var hits = await ModrinthClient.SearchAsync(q, mc, ModLoaderFor(mc, loader), sort, limit: 40, offset: 0, ct, category: _modCategory);
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
    /// <summary>"One layer" app icon: the white squircle in light mode, the dark squircle in dark mode.</summary>
    private void ApplyBrandIcons()
    {
        bool dark = ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
        var uri = new Uri(dark ? "avares://S1mp1e/Assets/s1mp1e-dark.png" : "avares://S1mp1e/Assets/s1mp1e.png");
        var bmp = new Bitmap(AssetLoader.Open(uri));
        Icon = new WindowIcon(bmp);
        if (BrandIcon is not null) BrandIcon.Source = bmp;
    }

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
            try { if (await DownloadOneVersionAsync(pid, mc, ModLoaderFor(mc, loader))) ok++; }
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
        var loader = ModLoaderFor(mc, LoaderBox.SelectedText ?? "Fabric");
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
                var loader = ModLoaderFor(mc, DefaultLoader(mc));
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
