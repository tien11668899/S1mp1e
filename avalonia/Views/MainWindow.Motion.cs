using System;
using System.Collections.Generic;
using System.Linq;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Styling;
using Avalonia.Threading;

namespace S1mp1e.Views;

public partial class MainWindow
{
    // Same 180 ms / cubic-out keyframe animation as the page cross-fade (Animation.RunAsync — property Transitions don't
    // advance under the capture harness's clock), so every show/hide in the launcher reads as one motion system.
    private const int FadeMs = 180;
    private readonly Dictionary<Control, System.Threading.CancellationTokenSource> _fades = new();

    private static System.Threading.Tasks.Task RunOpacity(Control c, double from, double to, int ms, System.Threading.CancellationToken ct)
        => new Animation
        {
            Duration = TimeSpan.FromMilliseconds(ms),
            Easing = to > from ? new CubicEaseOut() : new CubicEaseIn(),
            FillMode = FillMode.None,
            Children =
            {
                new KeyFrame { Cue = new Cue(0d), Setters = { new Setter(Visual.OpacityProperty, from) } },
                new KeyFrame { Cue = new Cue(1d), Setters = { new Setter(Visual.OpacityProperty, to) } },
            },
        }.RunAsync(c, ct);

    /// <summary>Show or hide with a fade instead of popping. Hiding collapses the control once the fade has finished;
    /// a newer call for the same control cancels an unfinished one.</summary>
    private async void FadeVisible(Control? c, bool show, int ms = FadeMs)
    {
        if (c is null) return;
        bool running = _fades.TryGetValue(c, out var prev);
        if (show && c.IsVisible && !running) return;
        if (!show && !c.IsVisible) return;
        prev?.Cancel();
        var cts = _fades[c] = new System.Threading.CancellationTokenSource();
        try
        {
            if (show)
            {
                c.IsVisible = true;
                await RunOpacity(c, 0, 1, ms, cts.Token);
            }
            else
            {
                await RunOpacity(c, 1, 0, ms, cts.Token);
                if (!cts.IsCancellationRequested) c.IsVisible = false;
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { LogCrash(ex); c.IsVisible = show; }
        finally
        {
            if (_fades.TryGetValue(c, out var cur) && cur == cts) _fades.Remove(c);
        }
    }

    /// <summary>Fade an image in when its picture arrives instead of it popping into an empty frame.</summary>
    private static void FadeInSource(Image img, IImage? source, int ms = 220)
    {
        img.Source = source;
        if (source is not null) _ = RunOpacity(img, 0, 1, ms, default);
    }

    /// <summary>Background colour changes (selected chips) ease instead of snapping.</summary>
    private static void EnsureBackgroundTransition(Border b, int ms = 160)
    {
        b.Transitions ??= new Transitions();
        if (!b.Transitions.OfType<BrushTransition>().Any(t => t.Property == Border.BackgroundProperty))
            b.Transitions.Add(new BrushTransition { Property = Border.BackgroundProperty, Duration = TimeSpan.FromMilliseconds(ms) });
    }

    /// <summary>Fade a list container back in after all its items were replaced, so new results don't snap in.</summary>
    private static void RefreshFade(Control c, int ms = 160) => _ = RunOpacity(c, 0, 1, ms, default);
}
