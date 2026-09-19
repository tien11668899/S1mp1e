using System;
using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Styling;
using LiquidGlassAvaloniaUI;

namespace S1mp1e.Controls;

/// <summary>
/// Glass slider, the launcher twin of the in-game liquid-glass slider: a thin track with an accent fill and
/// a white capsule thumb that turns into a clear liquid-glass LENS while held — a rounded rectangle 1.48× wider
/// and 1.58× taller than the capsule, stretching wider and flatter at constant area as you drag faster (all
/// measured from the user's iOS 26 recordings). Plus <b>click the value to type an exact number</b> (Enter
/// commits, Esc cancels, clamped to [Minimum,Maximum] and snapped to TickFrequency).
///
/// The lens is REAL glass: a <see cref="LiquidGlassSurface"/> that refracts and slightly magnifies whatever is
/// behind it — the track, the accent line and the card — with a clear interior, refraction at the rim, a rim
/// highlight and a soft shadow. Nothing is painted dark inside it; on the dark theme it only looks dark because
/// the card behind it is.
///
/// Feel (<see cref="GlassMotion"/>): while dragging, the thumb follows the pointer 1:1 and keeps the offset
/// you grabbed it at; the value snaps to TickFrequency underneath. Past either end the thumb rubber-bands.
/// On release it settles onto the snapped value on a critically damped spring with no bounce. A click on bare
/// track or a typed value glides there.
///
/// Exposes Minimum/Maximum/Value/TickFrequency/Unit and raises <see cref="ValueChanged"/> / <see cref="ValueCommitted"/>.
/// </summary>
public class GlassSlider : ContentControl
{
    public static readonly StyledProperty<double> MinimumProperty =
        AvaloniaProperty.Register<GlassSlider, double>(nameof(Minimum), 0d);
    public static readonly StyledProperty<double> MaximumProperty =
        AvaloniaProperty.Register<GlassSlider, double>(nameof(Maximum), 100d);
    public static readonly StyledProperty<double> ValueProperty =
        AvaloniaProperty.Register<GlassSlider, double>(nameof(Value), 0d,
            defaultBindingMode: Avalonia.Data.BindingMode.TwoWay, coerce: CoerceValue);
    public static readonly StyledProperty<double> TickFrequencyProperty =
        AvaloniaProperty.Register<GlassSlider, double>(nameof(TickFrequency), 1d);
    public static readonly StyledProperty<string> UnitProperty =
        AvaloniaProperty.Register<GlassSlider, string>(nameof(Unit), "");

    public double Minimum { get => GetValue(MinimumProperty); set => SetValue(MinimumProperty, value); }
    public double Maximum { get => GetValue(MaximumProperty); set => SetValue(MaximumProperty, value); }
    public double Value { get => GetValue(ValueProperty); set => SetValue(ValueProperty, value); }
    public double TickFrequency { get => GetValue(TickFrequencyProperty); set => SetValue(TickFrequencyProperty, value); }
    public string Unit { get => GetValue(UnitProperty); set => SetValue(UnitProperty, value); }

    /// <summary>Raised after Value changes (every step of a drag, typed, or programmatic). Read Value from the sender.</summary>
    public event EventHandler? ValueChanged;
    /// <summary>Raised once a user edit is finished — a drag released or a typed value committed. Persist here,
    /// not in <see cref="ValueChanged"/>, which fires on every snapped step while dragging.</summary>
    public event EventHandler? ValueCommitted;

    internal void RaiseCommitted() => ValueCommitted?.Invoke(this, EventArgs.Empty);

    private static double CoerceValue(AvaloniaObject o, double v)
    {
        var s = (GlassSlider)o;
        if (v < s.Minimum) v = s.Minimum;
        if (v > s.Maximum) v = s.Maximum;
        return v;
    }

    private readonly GlassSliderSurface _surface;
    private readonly TextBlock _value = new() { VerticalAlignment = VerticalAlignment.Center, MinWidth = 46,
                                                TextAlignment = TextAlignment.Right, FontSize = 12.5 };
    private readonly TextBox _edit = new() { IsVisible = false, Width = 60, FontSize = 12.5, Padding = new Thickness(4, 1),
                                             VerticalAlignment = VerticalAlignment.Center };

    public GlassSlider()
    {
        Height = 24;
        ClipToBounds = false;          // ContentControl clips by default; the held lens overhangs the 24 px row
        HorizontalContentAlignment = HorizontalAlignment.Stretch;
        VerticalContentAlignment = VerticalAlignment.Center;

        // track layer (draws the track + fill, takes the input), the glass lens over it, the white pill on top
        var lensHost = new Canvas
        {
            IsHitTestVisible = false,
            ClipToBounds = true,       // the glass composite must not paint outside its host (see the sidebar pill)
            Margin = new Thickness(-GlassSliderSurface.HostPadX, -GlassSliderSurface.HostPadY,
                                   -GlassSliderSurface.HostPadX, -GlassSliderSurface.HostPadY),
        };
        var lens = new LiquidGlassSurface { IsHitTestVisible = false, IsVisible = false };
        LiquidGlassBackdrop.SetIsExcludedFromCapture(lens, true);
        lensHost.Children.Add(lens);

        var pillHost = new Canvas
        {
            IsHitTestVisible = false,
            ClipToBounds = false,
            Margin = lensHost.Margin,
        };
        LiquidGlassBackdrop.SetIsExcludedFromCapture(pillHost, true);   // the lens must not refract the pill
        var rim = new Border { IsHitTestVisible = false, Background = null, BorderThickness = new Thickness(1), IsVisible = false };
        var pill = new Border { IsHitTestVisible = false, Background = Brushes.White };
        pillHost.Children.Add(rim);
        pillHost.Children.Add(pill);

        _surface = new GlassSliderSurface(this, lens, rim, pill) { VerticalAlignment = VerticalAlignment.Stretch };
        _surface.Bind(GlassSliderSurface.AccentProperty, this.GetResourceObservable("Accent"));
        _value.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextSub"));

        var track = new Panel { ClipToBounds = false };
        track.Children.Add(_surface);
        track.Children.Add(lensHost);
        track.Children.Add(pillHost);

        var right = new Grid();
        right.Children.Add(_value);
        right.Children.Add(_edit);

        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,12,Auto"), ClipToBounds = false };
        Grid.SetColumn(track, 0);
        Grid.SetColumn(right, 2);
        grid.Children.Add(track);
        grid.Children.Add(right);
        Content = grid;

        _value.PointerPressed += (_, _) => BeginEdit();
        _edit.KeyDown += OnEditKey;
        _edit.LostFocus += (_, _) => CommitEdit();

        UpdateText();
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == ValueProperty)
        {
            _surface.Kick();
            UpdateText();
            ValueChanged?.Invoke(this, EventArgs.Empty);
        }
        else if (change.Property == MinimumProperty || change.Property == MaximumProperty
                 || change.Property == UnitProperty)
        {
            _surface.Kick();
            UpdateText();
        }
    }

    internal double Norm()
    {
        var range = Maximum - Minimum;
        return range <= 0 ? 0 : Math.Clamp((Value - Minimum) / range, 0, 1);
    }

    /// <summary>Commit the value for a 0..1 ratio along the track (clamped, then snapped to TickFrequency).</summary>
    internal void SetFromRatio(double t) => Value = Snap(Minimum + GlassMotion.Clamp01(t) * (Maximum - Minimum));

    private void UpdateText() => _value.Text = Format(Value) + Unit;

    private static string Format(double v)
    {
        // integer if the value is whole (RAM/CPS etc.), else 2dp
        return Math.Abs(v - Math.Round(v)) < 1e-6
            ? ((long)Math.Round(v)).ToString(CultureInfo.InvariantCulture)
            : v.ToString("0.##", CultureInfo.InvariantCulture);
    }

    private double Snap(double v)
    {
        var tf = TickFrequency;
        if (tf > 0) v = Minimum + Math.Round((v - Minimum) / tf) * tf;
        return Math.Clamp(v, Minimum, Maximum);
    }

    // ---- click-to-type ----
    private void BeginEdit()
    {
        _edit.Text = Format(Value);
        _edit.IsVisible = true;
        _value.IsVisible = false;
        _edit.Focus();
        _edit.SelectAll();
    }
    private void OnEditKey(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) { CommitEdit(); e.Handled = true; }
        else if (e.Key == Key.Escape) { CancelEdit(); e.Handled = true; }
    }
    private void CommitEdit()
    {
        if (!_edit.IsVisible) return;
        if (double.TryParse(_edit.Text, NumberStyles.Any, CultureInfo.InvariantCulture, out var v))
        {
            Value = Snap(v);
            RaiseCommitted();
        }
        EndEdit();
    }
    private void CancelEdit() => EndEdit();
    private void EndEdit()
    {
        _edit.IsVisible = false;
        _value.IsVisible = true;
        UpdateText();
    }
}

/// <summary>
/// The track layer of <see cref="GlassSlider"/>: draws the track and accent fill, takes the pointer, runs the
/// springs, and places the glass lens and the white pill (sibling layers above it) every frame.
///
/// All stepping happens in the animation-frame callback, never in <see cref="Render"/>: moving the lens and
/// pill changes other controls' properties, and Avalonia throws if anything is invalidated during the render
/// pass. Frames are requested only while something is still moving.
/// </summary>
internal sealed class GlassSliderSurface : Control
{
    public static readonly StyledProperty<IBrush?> AccentProperty =
        AvaloniaProperty.Register<GlassSliderSurface, IBrush?>(nameof(Accent));
    public IBrush? Accent { get => GetValue(AccentProperty); set => SetValue(AccentProperty, value); }

    static GlassSliderSurface() => AffectsRender<GlassSliderSurface>(AccentProperty);

    private const double HW = 13, HH = 8.5;      // rest capsule 26×17 (iOS 111×72, 1.54 : 1)
    private const double TrackH = 4;
    private const double GrabSlop = 3;
    // reference slider knob POSITION spring = critical k=1000 → Tune(0.199,0) (build-slider.ts:133 isToggleKnob →
    // tg.fraction springStepCritical, methods-animation.ts:149-155). No bounce: the slide never overshoots.
    private const double GlideS = 0.199;
    // reference drag-velocity squash spring = k=300 ζ0.5 → Tune(0.363,0.5) (spring.ts:68-69).
    private const double StretchDurS = 0.363;
    /// <summary>Room around the track for the lens overhang and its shadow (the lens/pill hosts extend this far).</summary>
    internal const double HostPadX = 26, HostPadY = 18;

    private readonly GlassSlider _owner;
    private readonly LiquidGlassSurface _lens;
    private readonly Border _rim;               // thin light rim over the lens (no fill)
    private readonly Border _pill;
    private readonly GlassMotion.Clock _clock = new();
    private readonly GlassMotion.Spring _lift = new(GlassMotion.MorphInS, 0);    // 0 = white pill, 1 = glass lens
    private readonly GlassMotion.Spring _glide = new(GlideS, 0);      // drawn ratio minus base, decays to 0
    private readonly GlassMotion.Spring _stretch =
        new GlassMotion.Spring(StretchDurS, 1).Tune(StretchDurS, GlassMotion.StretchBounce);

    private bool _dragging, _rebase, _released, _lifted, _frameRequested;
    private double _dragT, _grabDX, _lastBase = double.NaN, _drawnT = double.NaN, _lastThumbX = double.NaN, _speed;
    private long _pressTicks, _holdUntilTicks;
    private double _fx = double.NaN, _L;         // what Render draws: thumb centre x and lens amount

    public GlassSliderSurface(GlassSlider owner, LiquidGlassSurface lens, Border rim, Border pill)
    {
        _owner = owner;
        _lens = lens;
        _rim = rim;
        _pill = pill;
        ClipToBounds = false;
        Cursor = new Cursor(StandardCursorType.Hand);
        ApplyTheme();
        ActualThemeVariantChanged += (_, _) => { ApplyTheme(); InvalidateVisual(); };
    }

    private bool Dark => ActualThemeVariant == ThemeVariant.Dark;
    private double TravelX0 => HW;
    private double Span => Math.Max(1, Bounds.Width - 2 * HW);

    /// <summary>Glass optics and pill styling per theme. The lens has no body colour of its own: a clear interior,
    /// refraction in a band along the rim, a little magnification, a rim highlight and a soft shadow.</summary>
    private void ApplyTheme()
    {
        bool dark = Dark;
        // Faithful circle-map lens (reference build-slider.ts knob: refractionHeight 10, amount -14, saturation 1.0,
        // effects = blur+lens only, no dispersion). Interior stays 1× — no BackdropZoom, no Snell approximation.
        _lens.SnellRefraction = false;
        _lens.BackdropZoom = 1.0;
        _lens.RefractionHeight = 10;
        _lens.RefractionAmount = 14;   // positive magnitude; DrawOperation negates it for the shader
        _lens.DepthEffect = true;
        _lens.ChromaticAberration = false;   // reference slider effects block is blur+lens only (no chroma; unlike the toggle)
        _lens.BlurRadius = 0;
        _lens.Vibrancy = 1.0;
        _lens.Brightness = 0;
        _lens.TintColor = Color.FromArgb(0, 0, 0, 0);
        // a faint lift so the clear glass reads as a surface; the refracted backdrop still shows straight through
        _lens.SurfaceColor = dark ? Color.FromArgb(0x00, 0xFF, 0xFF, 0xFF) : Color.FromArgb(0x10, 0xFF, 0xFF, 0xFF);
        _lens.HighlightEnabled = true;
        _lens.HighlightOpacity = 0.38;         // Ambient effective peak (1.0 × paintAlpha 0.38); Plus blend over-brightens above this
        _lens.HighlightWidth = 0.5;            // renders as a 2px stroke regardless (ceil(w)*2)
        _lens.HighlightBlurRadius = 0.25;
        _lens.HighlightAngle = 45;             // reference angle = π/4
        _lens.HighlightFalloff = 1.0;
        // Reference knob shadow is very subtle: radius 4 → sigma ≈1.3 (vendored radius==sigma), alpha 0.05.
        _lens.ShadowEnabled = true;
        _lens.ShadowRadius = 1.5;
        _lens.ShadowOffset = new Vector(0, 0.7);
        _lens.ShadowColor = dark ? Color.FromArgb(0x1A, 0, 0, 0) : Color.FromArgb(0x14, 0, 0, 0);
        _lens.ShadowOpacity = 1;
        // Reference knob inner shadow: radius 4 (== sigma, 1:1), offset (0,4), alpha 0.30 (renders ~0.15 after the
        // shader's ×0.5 coverage). Opacity is ramped by press amount L in PlaceThumb.
        _lens.InnerShadowEnabled = true;
        _lens.InnerShadowRadius = 4;
        _lens.InnerShadowOffset = new Vector(0, 4);
        _lens.InnerShadowColor = Color.FromArgb(0x4D, 0, 0, 0);
        _lens.InnerShadowOpacity = 1;

        // the rim: light from above, dimmer toward the bottom (a grey lower edge on the light theme, as recorded)
        _rim.BorderBrush = new LinearGradientBrush
        {
            StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
            EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
            GradientStops = dark
                ? new GradientStops { new GradientStop(Color.FromArgb(0x42, 0xFF, 0xFF, 0xFF), 0),     // subtle, as before
                                      new GradientStop(Color.FromArgb(0x14, 0xFF, 0xFF, 0xFF), 1) }
                : new GradientStops { new GradientStop(Color.FromArgb(0xE6, 0xFF, 0xFF, 0xFF), 0),
                                      new GradientStop(Color.FromArgb(0x2A, 0x00, 0x00, 0x00), 1) },
        };

        _pill.BoxShadow = BoxShadows.Parse(dark ? "0 1 4 0 #40000000" : "0 1 4 0 #2E000000");
        _pill.BorderBrush = dark ? null : new SolidColorBrush(Color.FromArgb(0x12, 0, 0, 0));
        _pill.BorderThickness = new Thickness(dark ? 0 : 0.75);
    }

    /// <summary>Something changed (value, range, press, size): keep frames coming until it has settled.</summary>
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

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == BoundsProperty) Kick();
    }

    // ---- input ----
    protected override void OnPointerPressed(PointerPressedEventArgs e)
    {
        base.OnPointerPressed(e);
        if (_dragging || e.GetCurrentPoint(this).Properties.PointerUpdateKind != PointerUpdateKind.LeftButtonPressed) return;
        double x = e.GetPosition(this).X;
        double t = double.IsNaN(_drawnT) ? _owner.Norm() : _drawnT;
        double fx = TravelX0 + Span * t;
        if (Math.Abs(x - fx) <= HW + GrabSlop)
        {
            // on the thumb: keep the grab offset; any leftover rubber-band overshoot eases out while dragging
            double c = GlassMotion.Clamp01(t);
            _grabDX = x - (TravelX0 + Span * c);
            _glide.Tune(GlideS, 0);
            _glide.X = t - c;
            _glide.V = 0;
            _glide.Retarget(0);
            _lastBase = c;
        }
        else
        {
            _grabDX = 0;               // bare track: the thumb glides to the pointer
            _rebase = true;
        }
        _dragging = true;
        _pressTicks = Environment.TickCount64;
        _lifted = true;
        _lift.Tune(GlassMotion.MorphInS, 0).Retarget(1);
        e.Pointer.Capture(this);
        e.Handled = true;
        Apply(x);
        Kick();
    }

    protected override void OnPointerMoved(PointerEventArgs e)
    {
        base.OnPointerMoved(e);
        if (!_dragging) return;
        Apply(e.GetPosition(this).X);
        Kick();
    }

    protected override void OnPointerReleased(PointerReleasedEventArgs e)
    {
        base.OnPointerReleased(e);
        if (e.InitialPressMouseButton == MouseButton.Left) EndDrag();   // a right-click mid-drag doesn't end it
    }

    protected override void OnPointerCaptureLost(PointerCaptureLostEventArgs e)
    {
        base.OnPointerCaptureLost(e);
        EndDrag();
    }

    private void EndDrag()
    {
        if (!_dragging) return;
        _dragging = false;
        _released = true;
        long now = Environment.TickCount64;
        _holdUntilTicks = (now - _pressTicks) / 1000.0 < GlassMotion.TapS ? now + (long)(GlassMotion.TapHoldS * 1000) : now;
        _owner.RaiseCommitted();
        Kick();
    }

    /// <summary>Pointer x → raw ratio (honouring the grab offset), then commit the clamped, snapped value.</summary>
    private void Apply(double x)
    {
        _dragT = (x - _grabDX - TravelX0) / Span;
        _owner.SetFromRatio(_dragT);
    }

    // ---- motion (animation-frame callback, outside the render pass) ----
    private void Tick()
    {
        double w = Bounds.Width, h = Bounds.Height;
        if (w <= 0 || h <= 0) return;
        double dt = _clock.Tick();
        long now = Environment.TickCount64;
        double span = Span;

        // where the thumb sits: the raw pointer while dragging (rubber-banded past the ends), else the snapped value
        double baseT;
        if (_dragging)
        {
            double clamped = GlassMotion.Clamp01(_dragT);
            double overPx = (_dragT - clamped) * span;
            baseT = clamped + Math.Sign(overPx) * GlassMotion.RubberBand(Math.Abs(overPx), HW * 2) / span;
        }
        else baseT = _owner.Norm();
        if (!double.IsNaN(_lastBase) && (!_dragging || _rebase) && baseT != _lastBase)
        {
            if (_released) _glide.Tune(GlideS, 0);                                   // let go
            else if (_rebase || (_glide.X == 0 && _glide.V == 0)) _glide.Tune(GlideS, 0);   // track click / typed
            _glide.X += _lastBase - baseT;                  // keep the thumb where it was drawn, then glide to the new base
            _glide.Retarget(0);
            if (_released) _glide.SettleMonotonic(); else _glide.CapOvershoot();
        }
        _released = false;
        _rebase = false;
        _lastBase = baseT;
        _glide.Update(dt);
        _glide.Settle(0.0002);
        double t = baseT + _glide.X;
        _drawnT = t;
        double fx = TravelX0 + span * t;

        // lens: morph in on press, out on release (a quick tap lingers a moment), stretch with drag speed
        if (!_dragging && _lifted && now >= _holdUntilTicks)
        {
            _lifted = false;
            _lift.Tune(GlassMotion.MorphOutS, 0).Retarget(0);
        }
        _lift.Update(dt);
        _lift.Settle(0.002);
        double L = GlassMotion.Clamp01(_lift.X);
        if (!double.IsNaN(_lastThumbX) && dt > 0)
            _speed += (Math.Abs(fx - _lastThumbX) / dt - _speed) * GlassMotion.Ema(dt, GlassMotion.SpeedTauS);
        _lastThumbX = fx;
        _stretch.Retarget(GlassMotion.StretchTarget(_speed, HW * 2)).Update(dt);
        var (sw, sh, sc) = GlassMotion.LensShape(_stretch.X);

        _fx = fx;
        _L = L;
        PlaceThumb(fx, h / 2, L, sw, sh, sc);
        InvalidateVisual();

        bool moving = _dragging || _lifted || _lift.X != 0 || _glide.X != 0 || _glide.V != 0
                      || Math.Abs(_stretch.X - 1) > 1e-3 || _speed > 0.5;
        if (moving) ScheduleFrame();
        else { _speed = 0; _lastThumbX = double.NaN; _stretch.Snap(1); _clock.Reset(); }
    }

    /// <summary>Sizes and positions the glass lens and the white pill (both in hosts offset by HostPad).</summary>
    private void PlaceThumb(double fx, double cy, double L, double sw, double sh, double sc)
    {
        double lw = HW * (1 + (sw - 1) * L), lh = HH * (1 + (sh - 1) * L);
        double cr = lh * (1 + (sc - 1) * L);                 // capsule at rest → rounded rect when lifted
        double left = fx - lw + HostPadX, top = cy - lh + HostPadY;

        bool showLens = L > 0.004;
        _lens.IsVisible = showLens;                          // an invisible lens costs no backdrop captures
        _rim.IsVisible = showLens;
        if (showLens)
        {
            _lens.InnerShadowOpacity = L;    // reference innerShadow alpha ramps with press (radius/alpha × progress)
            _lens.Width = lw * 2;
            _lens.Height = lh * 2;
            _lens.CornerRadius = new CornerRadius(cr);
            _lens.Opacity = L;
            Canvas.SetLeft(_lens, left);
            Canvas.SetTop(_lens, top);
            _rim.Width = lw * 2;
            _rim.Height = lh * 2;
            _rim.CornerRadius = new CornerRadius(cr);
            _rim.Opacity = L;
            Canvas.SetLeft(_rim, left);
            Canvas.SetTop(_rim, top);
        }

        // the white pill fades out as the glass forms (reference white overlay alpha = 1 − pressProgress, linear)
        double whiteA = 1 - L;
        _pill.IsVisible = whiteA > 0.004;
        _pill.Width = lw * 2;
        _pill.Height = lh * 2;
        _pill.CornerRadius = new CornerRadius(cr);
        _pill.Opacity = whiteA;
        Canvas.SetLeft(_pill, left);
        Canvas.SetTop(_pill, top);
    }

    // ---- paint: only the track and the accent fill; the lens refracts them ----
    public override void Render(DrawingContext ctx)
    {
        double w = Bounds.Width, h = Bounds.Height;
        if (w <= 0 || h <= 0) return;
        double fx = double.IsNaN(_fx) ? TravelX0 + Span * _owner.Norm() : _fx;
        double L = _L, cy = h / 2, r = TrackH / 2;

        IBrush accent = Accent ?? new SolidColorBrush(Color.FromRgb(0x0A, 0x84, 0xFF));
        var trackBrush = new SolidColorBrush(Dark ? Color.FromArgb(0x4D, 0xFF, 0xFF, 0xFF)    // white @0.30, as in-game
                                                  : Color.FromArgb(0x33, 0x78, 0x78, 0x80));  // iOS light system fill
        ctx.DrawRectangle(trackBrush, null, new RoundedRect(new Rect(0, cy - r, w, TrackH), r));
        // the fill's round end tucks under the pill at rest and reaches the lens centre when held
        double fillEnd = Math.Min(w, fx - (HW - TrackH) * (1 - L));
        if (fillEnd > TrackH)
            ctx.DrawRectangle(accent, null, new RoundedRect(new Rect(0, cy - r, Math.Max(TrackH, fillEnd), TrackH), r));
    }
}
