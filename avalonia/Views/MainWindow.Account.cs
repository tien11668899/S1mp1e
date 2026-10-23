using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Interactivity;
using Avalonia.Media.Imaging;
using S1mp1e.Services;

namespace S1mp1e.Views;

public partial class MainWindow
{
    private IReadOnlyList<CapeInfo> _capes = Array.Empty<CapeInfo>();
    private static readonly System.Net.Http.HttpClient _capeHttp = new() { Timeout = TimeSpan.FromSeconds(15) };

    /// <summary>
    /// The active account with a Minecraft token good for a few more minutes, refreshing it through the CLI when it is
    /// about to expire (the token lives ~24 h; skin and cape calls use it directly). Null when signed out, offline or
    /// the session needs a new sign-in — the reason is in <paramref name="why"/>.
    /// </summary>
    private async Task<(SavedAccount? acc, string? why)> FreshAccountAsync()
    {
        var acc = _cfg.Account;
        if (acc is null || string.IsNullOrEmpty(acc.McToken)) return (null, "請先登入");
        if (_captureMode || acc.McExpiresAt > DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 300) return (acc, null);
        var exe = ResolveItestExe();
        if (!File.Exists(exe)) return (acc, null);   // let the call try; it reports 401 itself
        try
        {
            var psi = new System.Diagnostics.ProcessStartInfo(exe)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = System.Text.Encoding.UTF8,
            };
            psi.ArgumentList.Add("refresh");
            using var p = System.Diagnostics.Process.Start(psi)!;
            var outTask = p.StandardOutput.ReadToEndAsync();
            _ = p.StandardError.ReadToEndAsync();
            await p.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(40));
            var output = (await outTask).Trim();
            if (output.Contains("AUTH_EXPIRED")) return (null, "登入已過期，請到「帳號」重新登入");
            if (output.Contains("AUTH_OFFLINE")) return (null, "連不上 Microsoft，請檢查網路後再試");
            if (!ReloadConfig()) return (null, "設定檔暫時無法讀取，請稍後再試");
            var now = _cfg.Account;
            return now is not null && now.Uuid == acc.Uuid ? (now, null) : (null, "帳號已切換，請再試一次");
        }
        catch (TimeoutException) { return (null, "更新登入逾時，請稍後再試"); }
        catch (Exception ex) { LogCrash(ex); return (acc, null); }
    }

    private async Task LoadCapesAsync()
    {
        if (CapeSub is null) return;
        var (acc, why) = await FreshAccountAsync();
        if (acc is null) { if (why is not null && _cfg.Account is not null) CapeSub.Text = why; return; }
        var uuid = acc.Uuid;
        CapeSub.Text = "載入中…";
        CapeChangeBtn.IsEnabled = false;
        try
        {
            var capes = await SkinService.GetCapesAsync(acc.McToken);
            // The player may have switched accounts while this loaded: never show A's capes under B.
            if (_cfg.Account?.Uuid != uuid) return;
            _capes = capes;
            await ShowCapesAsync();
        }
        catch (UnauthorizedAccessException) { if (_cfg.Account?.Uuid == uuid) CapeSub.Text = "登入已過期，重新登入後才能換披風"; }
        catch (Exception ex) { LogCrash(ex); if (_cfg.Account?.Uuid == uuid) CapeSub.Text = "無法載入披風"; }
    }

    private async Task ShowCapesAsync()
    {
        var active = _capes.FirstOrDefault(c => c.Active);
        CapeChangeBtn.IsEnabled = _capes.Count > 0;
        CapeSub.Text = _capes.Count == 0 ? "這個帳號沒有披風"
            : active is null ? $"未顯示披風 · 擁有 {_capes.Count} 件"
            : $"{CapeName(active)} · 擁有 {_capes.Count} 件";
        FadeInSource(CapeImage, active is null ? null : await LoadCapeFrontAsync(active.Url));
    }

    private static string CapeName(CapeInfo c) => string.IsNullOrEmpty(c.Alias) ? "披風" : c.Alias;

    /// <summary>The cape texture is 64×32 (or an HD multiple); its front face is the 10×16 block at (1,1).</summary>
    private static async Task<Bitmap?> LoadCapeFrontAsync(string url)
    {
        try
        {
            byte[] png = url.StartsWith("file:", StringComparison.OrdinalIgnoreCase)
                ? await File.ReadAllBytesAsync(new Uri(url).LocalPath)
                : await _capeHttp.GetByteArrayAsync(url);
            using var ms = new MemoryStream(png);
            var full = new Bitmap(ms);
            int s = Math.Max(1, full.PixelSize.Width / 64);
            var cropped = new CroppedBitmap(full, new PixelRect(1 * s, 1 * s, 10 * s, 16 * s));
            var rtb = new RenderTargetBitmap(new PixelSize(10 * s, 16 * s));
            using (var ctx = rtb.CreateDrawingContext())
                ctx.DrawImage(cropped, new Rect(0, 0, 10 * s, 16 * s));
            return rtb;
        }
        catch { return null; }
    }

    private void OnCapeChangeClick(object? sender, RoutedEventArgs e)
    {
        if (_capes.Count == 0) return;
        var items = _capes.Select(CapeName).Append("不顯示披風").ToArray();
        var activeIdx = _capes.ToList().FindIndex(c => c.Active);
        ShowMenuFor(CapeChangeBtn, items, activeIdx < 0 ? _capes.Count : activeIdx, pick => _ = PickCapeAsync(pick));
    }

    private async Task PickCapeAsync(int pick)
    {
        string? id = pick >= 0 && pick < _capes.Count ? _capes[pick].Id : null;
        if (_capes.FirstOrDefault(c => c.Active)?.Id == id) return;
        CapeSub.Text = "套用中…";
        var (acc, why) = await FreshAccountAsync();
        if (acc is null) { CapeSub.Text = why ?? "更換失敗"; return; }
        try
        {
            if (!_captureMode) await SkinService.SetActiveCapeAsync(acc.McToken, id);
            _capes = _capes.Select(c => c with { Active = c.Id == id }).ToList();
            await ShowCapesAsync();
            ShowToast(id is null ? "已隱藏披風" : $"已換成「{CapeName(_capes[pick])}」");
        }
        catch (UnauthorizedAccessException) { CapeSub.Text = "登入已過期，重新登入後才能換披風"; }
        catch (Exception ex) { LogCrash(ex); CapeSub.Text = "更換失敗"; }
    }
}
