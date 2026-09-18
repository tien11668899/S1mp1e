using System;
using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Styling;

namespace S1mp1e.Controls;

/// <summary>
/// Glass slider, the launcher twin of the in-game liquid-glass slider: a thin track with an accent fill and
/// a white capsule thumb that turns into a clear glass LENS while held — a rounded rectangle 1.48× wider and
/// 1.58× taller than the capsule, stretching wider and flatter at constant area as you drag faster (all
/// measured from the user's iOS 26 recordings). Plus <b>click the value to type an exact number</b> (Enter
/// commits, Esc cancels, clamped to [Minimum,Maximum] and snapped to TickFrequency).
///
/// Feel (<see cref="GlassMotion"/>): while dragging, the thumb follows the pointer 1:1 and keeps the offset
/// you grabbed it at; the value snaps to TickFrequency underneath. Past either end the thumb rubber-bands.
/// On release it settles onto the snapped value on a critically damped spring with no bounce. A click on bare
/// track or a typed value glides there. The dark theme gets the dark lens of the dark recording, the light
/// theme the clear lens with a grey rim of the light one.
///
/// Exposes Minimum/Maximum/Value/TickFrequency/Unit and raises the plain <see cref="ValueChanged"/> event.
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

        _surface = new GlassSliderSurface(this) { VerticalAlignment = VerticalAlignment.Stretch };
        _surface.Bind(GlassSliderSurface.AccentProperty, this.GetResourceObservable("Accent"));
        _value.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextSub"));

        var right = new Grid();
        right.Children.Add(_value);
        right.Children.Add(_edit);

        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,12,Auto") };
        Grid.SetColumn(_surface, 0);
        Grid.SetColumn(right, 2);
        grid.Children.Add(_surface);
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
/// The track + thumb of <see cref="GlassSlider"/>, drawn directly (not composed from Borders) so the thumb can
/// morph from a capsule into a rounded-rect lens with sub-pixel motion. It steps its springs on real frame
/// time in <see cref="Render"/> and keeps requesting frames only while something is still moving.
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

    private readonly GlassSlider _owner;
    private readonly GlassMotion.Clock _clock = new();
    private readonly GlassMotion.Spring _lift = new(GlassMotion.MorphInS, 0);    // 0 = white pill, 1 = glass lens
    private readonly GlassMotion.Spring _glide = new(GlassMotion.JumpS, 0);      // drawn ratio minus base, decays to 0
    private readonly GlassMotion.Spring _stretch =
        new GlassMotion.Spring(GlassMotion.StretchS, 1).Tune(GlassMotion.StretchS, GlassMotion.StretchBounce);

    private bool _dragging, _rebase, _released, _lifted, _frameRequested;
    private double _dragT, _grabDX, _lastBase = double.NaN, _drawnT = double.NaN, _lastThumbX = double.NaN, _speed;
    private long _pressTicks, _holdUntilTicks;

    public GlassSliderSurface(GlassSlider owner)
    {
        _owner = owner;
        ClipToBounds = false;          // the lens is taller than the row and may overhang it
        Cursor = new Cursor(StandardCursorType.Hand);
        ActualThemeVariantChanged += (_, _) => InvalidateVisual();   // dark lens ↔ light lens
    }

    private double TravelX0 => HW;
    private double Span => Math.Max(1, Bounds.Width - 2 * HW);

    /// <summary>Something changed (value, range, press): repaint now and keep frames running until it has settled.
    /// Only for callers OUTSIDE the render pass (input handlers, property changes).</summary>
    internal void Kick()
    {
        InvalidateVisual();
        ScheduleFrame();
    }

    /// <summary>Ask for one more frame. Safe to call from <see cref="Render"/>: Avalonia throws if a visual is
    /// invalidated during the render pass, so the invalidation happens in the animation-frame callback instead.</summary>
    private void ScheduleFrame()
    {
        if (_frameRequested) return;
        var top = TopLevel.GetTopLevel(this);
        if (top is null) return;
        _frameRequested = true;
        top.RequestAnimationFrame(_ => { _frameRequested = false; InvalidateVisual(); });
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == BoundsProperty) InvalidateVisual();
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
            _glide.Tune(GlassMotion.JumpS, 0);
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

    // ---- motion + paint ----
    public override void Render(DrawingContext ctx)
    {
        double w = Bounds.Width, h = Bounds.Height;
        if (w <= 0 || h <= 0) return;
        double dt = _clock.Tick();
        long now = Environment.TickCount64;
        double span = Span, cy = h / 2;

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
            if (_released) _glide.Tune(GlassMotion.SettleS, 0);                                   // let go
            else if (_rebase || (_glide.X == 0 && _glide.V == 0)) _glide.Tune(GlassMotion.JumpS, 0);   // track click / typed
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

        Paint(ctx, w, cy, fx, L, sw, sh, sc);

        bool moving = _dragging || _lifted || _lift.X != 0 || _glide.X != 0 || _glide.V != 0
                      || Math.Abs(_stretch.X - 1) > 1e-3 || _speed > 0.5;
        if (moving) ScheduleFrame();          // never InvalidateVisual() from inside Render
        else { _speed = 0; _lastThumbX = double.NaN; _stretch.Snap(1); _clock.Reset(); }
    }

    private void Paint(DrawingContext ctx, double w, double cy, double fx, double L, double sw, double sh, double sc)
    {
        bool dark = ActualThemeVariant == ThemeVariant.Dark;
        IBrush accent = Accent ?? new SolidColorBrush(Color.FromRgb(0x0A, 0x84, 0xFF));
        var trackBrush = new SolidColorBrush(dark ? Color.FromArgb(0x4D, 0xFF, 0xFF, 0xFF)    // white @0.30, as in-game
                                                  : Color.FromArgb(0x33, 0x78, 0x78, 0x80));  // iOS light system fill
        double r = TrackH / 2;

        // track + accent fill (the fill's round end tucks under the pill at rest and reaches the lens centre when held)
        ctx.DrawRectangle(trackBrush, null, new RoundedRect(new Rect(0, cy - r, w, TrackH), r));
        double fillEnd = Math.Min(w, fx - (HW - TrackH) * (1 - L));
        if (fillEnd > TrackH)
            ctx.DrawRectangle(accent, null, new RoundedRect(new Rect(0, cy - r, Math.Max(TrackH, fillEnd), TrackH), r));

        // the thumb: capsule at rest, rounded-rect lens when held
        double lw = HW * (1 + (sw - 1) * L), lh = HH * (1 + (sh - 1) * L);
        double cr = lh * (1 + (sc - 1) * L);
        var lensRect = new Rect(fx - lw, cy - lh, lw * 2, lh * 2);
        var lens = new RoundedRect(lensRect, cr);

        if (L > 0.004)
        {
            using (ctx.PushOpacity(L))
            {
                // soft grounding shadow, then the glass body
                ctx.DrawRectangle(new SolidColorBrush(Color.FromArgb(1, 0, 0, 0)), null, lens,
                    BoxShadows.Parse(dark ? "0 3 10 0 #59000000" : "0 4 12 0 #33000000"));
                ctx.DrawRectangle(new SolidColorBrush(dark ? Color.FromArgb(0xCC, 0x12, 0x12, 0x14)
                                                           : Color.FromArgb(0x8C, 0xFF, 0xFF, 0xFF)), null, lens);

                // the track seen through the lens, a little magnified (thicker in the light lens, as recorded). The
                // dark lens shows only the accent line: its body hides the grey track, as in the dark recording.
                double bandH = Math.Min(r * (dark ? 1.15 : 1.4), lh * 0.76);
                double bx0 = Math.Max(0, lensRect.Left), bx1 = Math.Min(w, lensRect.Right);
                if (bx1 - bx0 > 0.5)
                {
                    using (ctx.PushClip(lens))
                    {
                        if (!dark)
                            ctx.DrawRectangle(trackBrush, null, new RoundedRect(new Rect(bx0, cy - bandH, bx1 - bx0, bandH * 2), bandH));
                        double split = Math.Min(bx1, Math.Max(bx0 + 2 * bandH, Math.Min(w, fx)));
                        if (fx > bx0)
                        {
                            ctx.DrawRectangle(accent, null, new RoundedRect(new Rect(bx0, cy - bandH, split - bx0, bandH * 2), bandH));
                            // where the line enters the lens it flares like a meniscus (the refraction at the rim)
                            double fl = bandH * 2.4, fh = bandH * 1.65;
                            if (lensRect.Left > 0 && split - bx0 > fl)
                            {
                                var flare = new StreamGeometry();
                                using (var g = flare.Open())
                                {
                                    g.BeginFigure(new Point(bx0, cy - fh), true);
                                    g.QuadraticBezierTo(new Point(bx0 + fl * 0.3, cy - bandH), new Point(bx0 + fl, cy - bandH));
                                    g.LineTo(new Point(bx0 + fl, cy + bandH));
                                    g.QuadraticBezierTo(new Point(bx0 + fl * 0.3, cy + bandH), new Point(bx0, cy + fh));
                                    g.EndFigure(true);
                                }
                                ctx.DrawGeometry(accent, null, flare);
                            }
                        }
                    }
                }

                // top sheen + rim: light from above, darker lower edge (the grey rim of the light recording)
                var sheen = new LinearGradientBrush
                {
                    StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
                    EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
                    GradientStops =
                    {
                        new GradientStop(Color.FromArgb(dark ? (byte)0x2E : (byte)0x73, 0xFF, 0xFF, 0xFF), 0),
                        new GradientStop(Color.FromArgb(0, 0xFF, 0xFF, 0xFF), 0.45),
                        new GradientStop(Color.FromArgb(dark ? (byte)0x00 : (byte)0x10, 0, 0, 0), 1),
                    },
                };
                ctx.DrawRectangle(sheen, null, lens);
                var rim = new LinearGradientBrush
                {
                    StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
                    EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
                    GradientStops = dark
                        ? new GradientStops { new GradientStop(Color.FromArgb(0x42, 0xFF, 0xFF, 0xFF), 0),
                                              new GradientStop(Color.FromArgb(0x14, 0xFF, 0xFF, 0xFF), 1) }
                        : new GradientStops { new GradientStop(Color.FromArgb(0xE6, 0xFF, 0xFF, 0xFF), 0),
                                              new GradientStop(Color.FromArgb(0x2A, 0x00, 0x00, 0x00), 1) },
                };
                ctx.DrawRectangle(null, new Pen(rim, 1), lens);
            }
        }

        // the solid white pill, fading out as the lens forms (and back in as it re-forms); squared so the two layers
        // never sit at 50/50 together, which reads as a flat grey knob on the dark theme
        double whiteA = (1 - L) * (1 - L);
        if (whiteA > 0.004)
        {
            using (ctx.PushOpacity(whiteA))
            {
                ctx.DrawRectangle(Brushes.White, dark ? null : new Pen(new SolidColorBrush(Color.FromArgb(0x12, 0, 0, 0)), 0.75),
                    lens, BoxShadows.Parse(dark ? "0 1 4 0 #40000000" : "0 1 4 0 #2E000000"));
            }
        }
    }
}
