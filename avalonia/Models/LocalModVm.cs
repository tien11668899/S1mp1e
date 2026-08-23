using System.ComponentModel;
using System.Runtime.CompilerServices;
using Avalonia.Media.Imaging;

namespace S1mp1e.Models;

/// <summary>
/// Row view-model for the local mods list. The <see cref="JarPath"/> mutates
/// when the user toggles enable/disable (rename foo.jar ↔ foo.jar.disabled),
/// so it's a settable property, not init-only.
/// </summary>
public sealed class LocalModVm : INotifyPropertyChanged
{
    private string _jarPath = "";
    public string JarPath
    {
        get => _jarPath;
        set { if (_jarPath != value) { _jarPath = value; OnChanged(); } }
    }

    public string Id          { get; init; } = "";
    public string Name        { get; init; } = "";
    public string Description { get; init; } = "";
    public Bitmap? Icon       { get; init; }

    private bool _enabled;
    public bool Enabled
    {
        get => _enabled;
        set { if (_enabled != value) { _enabled = value; OnChanged(); } }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
