using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Data;
using Avalonia.Layout;
using Avalonia.Media;

namespace S1mp1e.Controls;

/// <summary>
/// macOS "pop-up button": shows the selected option as a blue value + up/down chevron.
/// Derives from Button (free Click / hover). It does NOT own its menu — clicking raises
/// <see cref="OpenRequested"/> and the host window shows a single shared liquid-glass menu
/// (a real refractive LiquidGlassSurface living in the window's own visual tree, which a
/// Popup could never be). Options are a '|'-separated string so Chinese labels are safe.
/// </summary>
public class GlassSelect : Button
{
    public static readonly StyledProperty<string?> OptionsProperty =
        AvaloniaProperty.Register<GlassSelect, string?>(nameof(Options));

    public static readonly StyledProperty<int> SelectedIndexProperty =
        AvaloniaProperty.Register<GlassSelect, int>(
            nameof(SelectedIndex), 0, defaultBindingMode: BindingMode.TwoWay);

    public string? Options { get => GetValue(OptionsProperty); set => SetValue(OptionsProperty, value); }
    public int SelectedIndex { get => GetValue(SelectedIndexProperty); set => SetValue(SelectedIndexProperty, value); }

    public string[] Items =>
        string.IsNullOrEmpty(Options) ? Array.Empty<string>() : Options!.Split('|');

    public string SelectedText
    {
        get
        {
            var it = Items;
            return (SelectedIndex >= 0 && SelectedIndex < it.Length) ? it[SelectedIndex] : string.Empty;
        }
    }

    /// <summary>Raised on click — the host opens the shared glass menu anchored to this control.</summary>
    public event EventHandler<GlassSelect>? OpenRequested;
    /// <summary>Raised when the selected index changes (host writes it back after a pick).</summary>
    public event EventHandler? SelectionChanged;

    public GlassSelect()
    {
        Click += (_, _) => OpenRequested?.Invoke(this, this);
    }

    protected override void OnApplyTemplate(Avalonia.Controls.Primitives.TemplateAppliedEventArgs e)
    {
        base.OnApplyTemplate(e);
        BuildContent();
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == OptionsProperty || change.Property == SelectedIndexProperty)
        {
            BuildContent();
            if (change.Property == SelectedIndexProperty)
                SelectionChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    // value (blue, semibold) + blue up/down chevron — the collapsed pop-up-button look.
    private void BuildContent()
    {
        var value = new TextBlock
        {
            Text = SelectedText,
            FontWeight = FontWeight.SemiBold,
            VerticalAlignment = VerticalAlignment.Center,
        };
        value.Bind(TextBlock.ForegroundProperty, this.GetResourceObservable("Accent"));
        value.Bind(TextBlock.FontSizeProperty, this.GetObservable(FontSizeProperty));

        var chevron = new Avalonia.Controls.Shapes.Path
        {
            Stretch = Stretch.None,
            StrokeThickness = 1.7,
            StrokeLineCap = PenLineCap.Round,
            StrokeJoin = PenLineJoin.Round,
            Data = Geometry.Parse("M0,4 L4,0.5 L8,4 M0,7.5 L4,11 L8,7.5"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0),
        };
        chevron.Bind(Avalonia.Controls.Shapes.Path.StrokeProperty, this.GetResourceObservable("Accent"));

        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(value);
        row.Children.Add(chevron);
        Content = row;
    }
}
