using System;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Styling;

namespace S1mp1e.Controls;

/// <summary>
/// iOS/macOS-style toggle with a LIQUID knob: on toggle the knob slides across
/// while briefly stretching wider, then settles back to a circle — like a drop of
/// liquid. Derives from ToggleButton so IsChecked + click-to-toggle come for free
/// (and, unlike ToggleSwitch, no required PART_MovingKnobs). Track colour (green/
/// gray) is driven by :checked styles in XAML; the knob motion is animated here so
/// it fires only on a real state change. Only the knob's own Margin/Width are
/// animated (safe) — never a standalone Transform via RunAsync (that crashes).
/// </summary>
public class LiquidToggle : ToggleButton
{
    private Border? _knob;

    // Panel 74 wide, 48 flat track centred (track spans x=13..61, centre x=37); knob OVAL 28x20.
    private static readonly Thickness Off = new(15, 0, 0, 0);   // 13 + 2
    private static readonly Thickness On = new(31, 0, 0, 0);    // 61 - 2 - 28
    private const double KnobW = 28;   // oval: wider than tall, dominates the track
    private const double KnobH = 20;
    private const double Dur = 480;

    // Peak "glass blob": the resting oval scaled up PROPORTIONALLY (same ratio),
    // centred on the track (no upward lift — it grows symmetrically about the centre).
    private const double BigW = 52;
    private const double BigH = 37;
    private const double BigL = 11;    // (74 - 52) / 2  -> centred, so blob centre == track centre (37)

    protected override void OnApplyTemplate(TemplateAppliedEventArgs e)
    {
        base.OnApplyTemplate(e);
        _knob = e.NameScope.Find<Border>("Knob");
        Rest(IsChecked == true);
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == IsCheckedProperty && _knob is not null)
            Animate(IsChecked == true);
    }

    private const double OffL = 15;
    private const double OnL = 31;

    private void Rest(bool on)
    {
        if (_knob is null) return;
        _knob.Margin = on ? On : Off;
        _knob.Width = KnobW;
        _knob.Height = KnobH;
        _knob.Opacity = 1;
    }

    private void Animate(bool on)
    {
        if (_knob is null) return;
        var fromL = on ? OffL : OnL;
        var toL = on ? OnL : OffL;

        // small white OVAL slides across, ballooning at the middle:
        //   -> ENLARGE: scales up PROPORTIONALLY (same ~1.2 ratio) into a translucent
        //      glass blob CENTRED on the track (grows symmetrically, no upward lift)
        //   -> RETURN: shrinks back to the small oval at the other end
        // Positions on the way in/out lie between the endpoint and the centred blob.
        var p1L = fromL + (BigL - fromL) * 0.5;   // half-way (by position) toward centre
        var p2L = toL + (BigL - toL) * 0.5;
        var from = new Thickness(fromL, 0, 0, 0);
        var p1 = new Thickness(p1L, 0, 0, 0);
        var big = new Thickness(BigL, 0, 0, 0);   // centred on the track, vertically symmetric
        var p2 = new Thickness(p2L, 0, 0, 0);
        var to = new Thickness(toL, 0, 0, 0);
        try
        {
            var a = new Animation
            {
                Duration = TimeSpan.FromMilliseconds(Dur),
                Easing = new CubicEaseInOut(),
                FillMode = FillMode.Forward,
                Children =
                {
                    new KeyFrame { Cue = new Cue(0d), Setters =
                        { new Setter(MarginProperty, from), new Setter(WidthProperty, KnobW), new Setter(HeightProperty, KnobH), new Setter(OpacityProperty, 1d) } },
                    new KeyFrame { Cue = new Cue(0.28d), Setters =
                        { new Setter(MarginProperty, p1), new Setter(WidthProperty, 39d), new Setter(HeightProperty, 27d), new Setter(OpacityProperty, 0.82d) } },
                    new KeyFrame { Cue = new Cue(0.5d), Setters =
                        { new Setter(MarginProperty, big), new Setter(WidthProperty, BigW), new Setter(HeightProperty, BigH), new Setter(OpacityProperty, 0.5d) } },
                    new KeyFrame { Cue = new Cue(0.72d), Setters =
                        { new Setter(MarginProperty, p2), new Setter(WidthProperty, 39d), new Setter(HeightProperty, 27d), new Setter(OpacityProperty, 0.82d) } },
                    new KeyFrame { Cue = new Cue(1d), Setters =
                        { new Setter(MarginProperty, to), new Setter(WidthProperty, KnobW), new Setter(HeightProperty, KnobH), new Setter(OpacityProperty, 1d) } },
                },
            };
            _ = a.RunAsync(_knob);
        }
        catch
        {
            Rest(on);   // never let a toggle crash the app
        }
    }
}
