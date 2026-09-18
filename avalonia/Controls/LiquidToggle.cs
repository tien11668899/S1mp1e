using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Styling;
using LiquidGlassAvaloniaUI;

namespace S1mp1e.Controls;

/// <summary>
/// iOS-26 liquid-glass switch, the launcher twin of the in-game one. At rest the knob is a solid white pill;
/// on toggle it slides across while turning into a REAL refracting liquid-glass lens — a <see cref="LiquidGlassSurface"/>
/// that refracts and slightly magnifies the track (green/grey) behind it, with a rim highlight and a soft shadow —
/// then settles back into the white pill at the other end. Matched to the user's iOS 26 recordings (button 深/淺,
/// 快速/慢速): the track is a wide capsule (~2.27:1), the rest knob a 1.47:1 lozenge, and the lens a wider lozenge
/// that stretches further the faster you flip it (fast toggle → more stretch), never ballooning.
///
/// Derives from ToggleButton so IsChecked + click-to-toggle + IsCheckedChanged keep working. The visual tree is
/// built in code (OnApplyTemplate) into the template's PART_Root; the motion runs on GlassMotion springs stepped
/// in the animation-frame callback (never in Render — Avalonia forbids invalidation during the render pass).
/// </summary>
public class LiquidToggle : ToggleButton
{
    // geometry (device-independent px), matched to the reference
    private const double RootW = 74, RootH = 48;
    private const double TW = 50, TH = 22;                    // track 50×22 (2.27:1)
    private const double TX0 = (RootW - TW) / 2;             // 12
    private const double CY = RootH / 2;                    // 24
    private const double KW = 29, KH = 19;                   // rest knob (pill) 29×19 (1.53:1, 0.86×TH tall)
    private const double Inset = 2;
    private const double OffCX = TX0 + Inset + KW / 2;       // 27.5
    private const double OnCX = TX0 + TW - Inset - KW / 2;   // 46.5
    // the lens is a symmetric BALLOON keyed to flip speed (sigma 0..1): a fast flick overhangs the track top &
    // bottom (45×31 = 2.05×TH wide, 1.40×TH tall); a slow drag just fills it (30×23, no overhang). Measured.
    private const double LensW = 45, LensH = 31;            // FAST peak (sigma = 1)
    private const double LensWSlow = 30, LensHSlow = 23;    // SLOW peak (sigma = 0)
    private const double TravelS = 0.30;
    private const double MorphInS = 0.085, MorphOutS = 0.255;   // snap open, ~3× slower settle
    private const double SpeedFull = 55.0;                  // knob px/s that reads as a full-speed flick (sigma = 1)

    private static readonly Color OnColor = Color.FromRgb(0x34, 0xC7, 0x59);

    private Panel? _root;
    private Border? _track;
    private LiquidGlassSurface? _lens;
    private Border? _rim;
    private Border? _pill;
    private Color _offColor = Color.FromRgb(0x55, 0x55, 0x5A);

    private readonly GlassMotion.Clock _clock = new();
    private readonly GlassMotion.Spring _travel = new(TravelS, 0);                 // 0 = OFF pos, 1 = ON pos
    private readonly GlassMotion.Spring _lift = new(MorphInS, 0);                  // 0 = white pill, 1 = glass lens

    private bool _lifted, _frameRequested;
    private double _lastCX = double.NaN, _speed, _sigma;

    public LiquidToggle() => ClipToBounds = false;

    private bool Dark => ActualThemeVariant == ThemeVariant.Dark;

    protected override void OnApplyTemplate(TemplateAppliedEventArgs e)
    {
        base.OnApplyTemplate(e);
        _root = e.NameScope.Find<Panel>("PART_Root");
        if (_root is null) return;
        _root.Children.Clear();

        _track = new Border
        {
            Width = TW, Height = TH, CornerRadius = new CornerRadius(TH / 2),
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        };
        _root.Children.Add(_track);

        var lensHost = new Canvas { ClipToBounds = false, IsHitTestVisible = false };
        _lens = new LiquidGlassSurface { IsHitTestVisible = false, IsVisible = false };
        LiquidGlassBackdrop.SetIsExcludedFromCapture(_lens, true);   // the lens must not refract itself
        lensHost.Children.Add(_lens);
        _root.Children.Add(lensHost);

        var pillHost = new Canvas { ClipToBounds = false, IsHitTestVisible = false };
        LiquidGlassBackdrop.SetIsExcludedFromCapture(pillHost, true);
        _rim = new Border { IsHitTestVisible = false, Background = null, BorderThickness = new Thickness(1), IsVisible = false };
        _pill = new Border { IsHitTestVisible = false, Background = Brushes.White };
        pillHost.Children.Add(_rim);
        pillHost.Children.Add(_pill);
        _root.Children.Add(pillHost);

        _travel.Snap(IsChecked == true ? 1 : 0);
        ApplyTheme();
        ActualThemeVariantChanged += (_, _) => ApplyTheme();
        Kick();
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == IsCheckedProperty && _root is not null)
        {
            _travel.Retarget(IsChecked == true ? 1 : 0);
            _lifted = true;
            _lift.Tune(MorphInS, 0).Retarget(1);
            Kick();
        }
    }

    /// <summary>Glass optics, rim, pill styling and the OFF track colour per theme. The lens has no body colour of its
    /// own: a clear interior, refraction in a band along the rim, a little magnification, a rim highlight, a shadow.</summary>
    private void ApplyTheme()
    {
        bool dark = Dark;
        _offColor = dark ? Color.FromRgb(0x55, 0x55, 0x5A) : Color.FromRgb(0xE4, 0xE4, 0xE6);
        if (_track is not null) _track.Background = new SolidColorBrush(Lerp(_offColor, OnColor, GlassMotion.Clamp01(_travel.X)));

        if (_lens is not null)
        {
            _lens.BackdropZoom = 1.24;          // stronger convex magnification: the track core reads larger/brighter
            _lens.RefractionHeight = 9;         // deepen the top/bottom dark refraction bands (the SDF notch signature)
            _lens.RefractionAmount = 16;
            _lens.DepthEffect = true;
            _lens.ChromaticAberration = false;
            _lens.BlurRadius = 0;
            _lens.Vibrancy = 1.0;
            _lens.Brightness = 0;
            _lens.TintColor = Color.FromArgb(0, 0, 0, 0);
            _lens.SurfaceColor = dark ? Color.FromArgb(0x1A, 0xFF, 0xFF, 0xFF) : Color.FromArgb(0x26, 0xFF, 0xFF, 0xFF);
            _lens.HighlightEnabled = true;
            _lens.HighlightOpacity = dark ? 0.62 : 0.55;    // bright specular rim, peaks mid-flip (× lens opacity)
            _lens.HighlightWidth = 0.35;
            _lens.HighlightBlurRadius = 0.25;
            _lens.HighlightAngle = 60;                       // top + leading edge
            _lens.ShadowEnabled = true;
            _lens.ShadowRadius = 7;
            _lens.ShadowOffset = new Vector(0, 3);
            _lens.ShadowColor = dark ? Color.FromArgb(0x8C, 0, 0, 0) : Color.FromArgb(0x40, 0, 0, 0);
            _lens.ShadowOpacity = 1;
        }
        if (_rim is not null)
            _rim.BorderBrush = new LinearGradientBrush
            {
                StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
                EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
                GradientStops = dark
                    ? new GradientStops { new GradientStop(Color.FromArgb(0x80, 0xFF, 0xFF, 0xFF), 0),   // bright top
                                          new GradientStop(Color.FromArgb(0x18, 0xFF, 0xFF, 0xFF), 1) }
                    : new GradientStops { new GradientStop(Color.FromArgb(0xF0, 0xFF, 0xFF, 0xFF), 0),
                                          new GradientStop(Color.FromArgb(0x2A, 0x00, 0x00, 0x00), 1) },
            };
        if (_pill is not null)
        {
            _pill.BoxShadow = BoxShadows.Parse(dark ? "0 1 4 0 #40000000" : "0 1 4 0 #2E000000");
            _pill.BorderBrush = dark ? null : new SolidColorBrush(Color.FromArgb(0x12, 0, 0, 0));
            _pill.BorderThickness = new Thickness(dark ? 0 : 0.75);
        }
    }

    internal void Kick() => ScheduleFrame();

    private void ScheduleFrame()
    {
        if (_frameRequested) return;
        var top = TopLevel.GetTopLevel(this);
        if (top is null) return;
        _frameRequested = true;
        top.RequestAnimationFrame(_ => { _frameRequested = false; Tick(); });
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        Kick();
    }

    private void Tick()
    {
        if (_root is null || _track is null) return;
        double dt = _clock.Tick();
        _travel.Update(dt);
        _travel.Settle(0.0002);
        double cx = OffCX + (OnCX - OffCX) * _travel.X;

        // lens forms on the flick, then reverts once the knob has essentially arrived
        bool arrived = Math.Abs(_travel.Target - _travel.X) < 0.05 && Math.Abs(_travel.V) < 0.5;
        if (_lifted && arrived) { _lifted = false; _lift.Tune(MorphOutS, 0).Retarget(0); }
        _lift.Update(dt);
        _lift.Settle(0.002);
        double L = GlassMotion.Clamp01(_lift.X);

        if (!double.IsNaN(_lastCX) && dt > 0)
            _speed += (Math.Abs(cx - _lastCX) / dt - _speed) * GlassMotion.Ema(dt, GlassMotion.SpeedTauS);
        _lastCX = cx;
        // flip speed → sigma (0 = slow drag, 1 = fast flick; a click reads ~1). Hold the peak across the lift so the
        // balloon reflects how hard it was flipped, not the knob's speed at the instant it arrives.
        if (L > 0.02) _sigma = Math.Max(_sigma, GlassMotion.Clamp01(_speed / SpeedFull));
        else _sigma = 0;

        double travel01 = GlassMotion.Clamp01(_travel.X);
        PlaceKnob(cx, L, _sigma);
        _track.Background = new SolidColorBrush(Lerp(_offColor, OnColor, travel01));
        if (_pill is not null)   // the solid pill picks up a pale-green cast toward the ON end
            _pill.Background = new SolidColorBrush(Lerp(Color.FromRgb(0xFF, 0xFF, 0xFF), Color.FromRgb(0xEA, 0xF7, 0xEF), travel01));

        bool moving = _lifted || _lift.X != 0 || Math.Abs(_travel.X - _travel.Target) > 1e-3
                      || Math.Abs(_travel.V) > 1e-3 || _speed > 0.5;
        if (moving) ScheduleFrame();
        else { _speed = 0; _lastCX = double.NaN; _clock.Reset(); }
    }

    private void PlaceKnob(double cx, double L, double sigma)
    {
        // peak lens blends slow↔fast by flip speed; the balloon grows in BOTH axes, so a fast flick overhangs the
        // track top & bottom (h → 1.40×TH) while a slow drag just fills it (h → 1.05×TH, no overhang).
        double pw = LensWSlow + (LensW - LensWSlow) * sigma;
        double ph = LensHSlow + (LensH - LensHSlow) * sigma;
        double w = KW + (pw - KW) * L;
        double h = KH + (ph - KH) * L;
        double cr = h / 2 * (1 - 0.10 * L * sigma);    // slightly rectangular at the fast peak, capsule at rest
        double left = cx - w / 2, top = CY - h / 2;

        bool showLens = L > 0.004;
        _lens!.IsVisible = showLens;
        _rim!.IsVisible = showLens;
        if (showLens)
        {
            _lens.Width = w; _lens.Height = h; _lens.CornerRadius = new CornerRadius(cr); _lens.Opacity = L;
            Canvas.SetLeft(_lens, left); Canvas.SetTop(_lens, top);
            _rim.Width = w; _rim.Height = h; _rim.CornerRadius = new CornerRadius(cr); _rim.Opacity = L;
            Canvas.SetLeft(_rim, left); Canvas.SetTop(_rim, top);
        }

        double whiteA = (1 - L) * (1 - L);    // squared so pill+lens never sit at a flat 50/50
        _pill!.IsVisible = whiteA > 0.004;
        _pill.Width = w; _pill.Height = h; _pill.CornerRadius = new CornerRadius(cr); _pill.Opacity = whiteA;
        Canvas.SetLeft(_pill, left); Canvas.SetTop(_pill, top);
    }

    private static Color Lerp(Color a, Color b, double t)
    {
        byte L(byte x, byte y) => (byte)Math.Round(x + (y - x) * t);
        return Color.FromArgb(0xFF, L(a.R, b.R), L(a.G, b.G), L(a.B, b.B));
    }
}
