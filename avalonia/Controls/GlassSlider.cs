using System;
using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;

namespace S1mp1e.Controls;

/// <summary>
/// Glass slider mirroring the in-game <c>SliderWidget</c>: a rounded track with an
/// accent fill, a glass thumb that "pops" while grabbed, and — the key upgrade over
/// the stock Fluent slider — <b>click the value to type an exact number</b> (Enter
/// commits, Esc cancels, clamped to [Minimum,Maximum] and snapped to TickFrequency).
/// Exposes Minimum/Maximum/Value/TickFrequency so it drops in where the Fluent
/// <c>Slider</c> was; raises the plain <see cref="ValueChanged"/> CLR event.
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

    /// <summary>Raised after Value changes (drag, type, or programmatic). Read Value from the sender.</summary>
    public event EventHandler? ValueChanged;

    private static double CoerceValue(AvaloniaObject o, double v)
    {
        var s = (GlassSlider)o;
        if (v < s.Minimum) v = s.Minimum;
        if (v > s.Maximum) v = s.Maximum;
        return v;
    }

    private const double TrackH = 4, ThumbR = 8, ThumbGrabR = 9.3;   // +16% grab-pop
    private readonly Canvas _track = new() { Height = 22, VerticalAlignment = VerticalAlignment.Center };
    private readonly Border _line = new() { Height = TrackH, CornerRadius = new CornerRadius(TrackH / 2) };
    private readonly Border _fill = new() { Height = TrackH, CornerRadius = new CornerRadius(TrackH / 2) };
    private readonly Border _thumb = new();
    private readonly TextBlock _value = new() { VerticalAlignment = VerticalAlignment.Center, MinWidth = 46,
                                                TextAlignment = TextAlignment.Right, FontSize = 12.5 };
    private readonly TextBox _edit = new() { IsVisible = false, Width = 60, FontSize = 12.5, Padding = new Thickness(4, 1),
                                             VerticalAlignment = VerticalAlignment.Center };
    private bool _dragging;

    public GlassSlider()
    {
        Height = 24;
        HorizontalContentAlignment = HorizontalAlignment.Stretch;
        VerticalContentAlignment = VerticalAlignment.Center;

        _line.Background = new SolidColorBrush(Color.FromArgb(0x4D, 0xFF, 0xFF, 0xFF));   // white @0.30
        _fill.Bind(Border.BackgroundProperty, this.GetResourceObservable("Accent"));
        _value.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("TextSub"));

        _thumb.Width = ThumbR * 2; _thumb.Height = ThumbR * 2;
        _thumb.CornerRadius = new CornerRadius(ThumbR);
        _thumb.Background = Brushes.White;
        _thumb.BoxShadow = BoxShadows.Parse("0 1 4 0 #33000000");

        _track.Children.Add(_line);
        _track.Children.Add(_fill);
        _track.Children.Add(_thumb);

        var right = new Grid();
        right.Children.Add(_value);
        right.Children.Add(_edit);

        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,12,Auto") };
        Grid.SetColumn(_track, 0);
        Grid.SetColumn(right, 2);
        grid.Children.Add(_track);
        grid.Children.Add(right);
        Content = grid;

        _track.PointerPressed += OnTrackPressed;
        _track.PointerMoved += OnTrackMoved;
        _track.PointerReleased += OnTrackReleased;
        _track.PropertyChanged += (_, ev) => { if (ev.Property == BoundsProperty) Relayout(); };

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
            Relayout();
            UpdateText();
            ValueChanged?.Invoke(this, EventArgs.Empty);
        }
        else if (change.Property == MinimumProperty || change.Property == MaximumProperty
                 || change.Property == UnitProperty)
        {
            Relayout();
            UpdateText();
        }
    }

    private double Norm()
    {
        var range = Maximum - Minimum;
        return range <= 0 ? 0 : Math.Clamp((Value - Minimum) / range, 0, 1);
    }

    private void Relayout()
    {
        double w = _track.Bounds.Width;
        if (w <= 0) return;
        double cy = _track.Bounds.Height / 2;
        double usable = Math.Max(0, w - ThumbR * 2);
        double cx = ThumbR + usable * Norm();

        _line.Width = w;
        Canvas.SetLeft(_line, 0);
        Canvas.SetTop(_line, cy - TrackH / 2);

        _fill.Width = Math.Max(TrackH, cx);
        Canvas.SetLeft(_fill, 0);
        Canvas.SetTop(_fill, cy - TrackH / 2);

        double r = _dragging ? ThumbGrabR : ThumbR;
        _thumb.Width = r * 2; _thumb.Height = r * 2; _thumb.CornerRadius = new CornerRadius(r);
        Canvas.SetLeft(_thumb, cx - r);
        Canvas.SetTop(_thumb, cy - r);
    }

    private void UpdateText()
    {
        _value.Text = Format(Value) + Unit;
    }

    private string Format(double v)
    {
        // integer if the value is whole (RAM/CPS etc.), else 2dp
        return Math.Abs(v - Math.Round(v)) < 1e-6
            ? ((long)Math.Round(v)).ToString(CultureInfo.InvariantCulture)
            : v.ToString("0.##", CultureInfo.InvariantCulture);
    }

    private void SetFromPointer(double x)
    {
        double w = _track.Bounds.Width;
        double usable = Math.Max(1, w - ThumbR * 2);
        double t = Math.Clamp((x - ThumbR) / usable, 0, 1);
        double v = Minimum + t * (Maximum - Minimum);
        Value = Snap(v);
    }

    private double Snap(double v)
    {
        var tf = TickFrequency;
        if (tf > 0) v = Minimum + Math.Round((v - Minimum) / tf) * tf;
        return Math.Clamp(v, Minimum, Maximum);
    }

    // ---- drag ----
    private void OnTrackPressed(object? sender, PointerPressedEventArgs e)
    {
        _dragging = true;
        e.Pointer.Capture(_track);
        SetFromPointer(e.GetPosition(_track).X);
        Relayout();
    }
    private void OnTrackMoved(object? sender, PointerEventArgs e)
    {
        if (_dragging) SetFromPointer(e.GetPosition(_track).X);
    }
    private void OnTrackReleased(object? sender, PointerReleasedEventArgs e)
    {
        _dragging = false; Relayout();
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
            Value = Snap(v);
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
