using System;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using Avalonia.Media.Imaging;

namespace S1mp1e.Models;

/// <summary>
/// Per-row view-model for the Modrinth results list. INotifyPropertyChanged so
/// the async icon load and the 下載 button state flips re-render just that row
/// without rebuilding the entire ObservableCollection.
/// </summary>
public sealed class ModHitVm : INotifyPropertyChanged
{
    public string ProjectId { get; init; } = "";
    public string Title     { get; init; } = "";
    public string Subtitle  { get; init; } = "";
    public string? IconUrl  { get; init; }
    // Modrinth loader tags this mod supports (fabric/forge/neoforge/quilt/…).
    // Empty = unknown; treat as "everything" and let the resolve step filter.
    public string[] Loaders { get; init; } = System.Array.Empty<string>();

    private Bitmap? _icon;
    public Bitmap? Icon
    {
        get => _icon;
        set { if (!ReferenceEquals(_icon, value)) { _icon = value; OnChanged(); } }
    }

    private string _buttonLabel = "下載";
    public string ButtonLabel
    {
        get => _buttonLabel;
        set { if (_buttonLabel != value) { _buttonLabel = value; OnChanged(); } }
    }

    private bool _buttonEnabled = true;
    public bool ButtonEnabled
    {
        get => _buttonEnabled;
        set { if (_buttonEnabled != value) { _buttonEnabled = value; OnChanged(); } }
    }

    // Download progress ring — a Rectangle overlays the 下載 button; StrokeDashOffset
    // shrinks from RingPerimeter → 0 as Progress goes 0 → 1, drawing a full loop.
    // Button is a 72×28 pill (CornerRadius=14, i.e. half-height), so the perimeter is
    // stadium-shaped: 2·(width − height) + π·height = 2·44 + π·28 ≈ 176.
    public const double RingPerimeter = 176;
    private double _progress;
    public double Progress
    {
        get => _progress;
        set
        {
            var v = value < 0 ? 0 : value > 1 ? 1 : value;
            if (Math.Abs(_progress - v) < 0.001) return;
            _progress = v;
            OnChanged();
            OnChanged(nameof(DashOffset));
        }
    }
    public double DashOffset => RingPerimeter * (1 - _progress);

    private bool _isDownloading;
    public bool IsDownloading
    {
        get => _isDownloading;
        set { if (_isDownloading != value) { _isDownloading = value; OnChanged(); } }
    }

    private double _ringOpacity = 1;
    public double RingOpacity
    {
        get => _ringOpacity;
        set { if (Math.Abs(_ringOpacity - value) > 0.001) { _ringOpacity = value; OnChanged(); } }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
