using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Rendering.SceneGraph;
using Avalonia.Skia;
using SkiaSharp;

namespace S1mp1e.Controls;

/// <summary>
/// Overlay that paints TWO Skia composite effects on top of the ScrollViewer
/// underneath — feed it a viewport snapshot via <see cref="SetSnapshot"/>:
///
///   1. **Motion blur** (anisotropic Y-only Skia blur) — active while scrolling
///      fast; strength driven by <c>motionSigmaY</c>.
///   2. **Edge blur** — always-on top/bottom bands where content is progressively
///      blurred + faded as it approaches the viewport edge (iOS scroll look).
/// </summary>
public sealed class MotionBlurLayer : Control
{
    private Bitmap? _snapshot;
    private double _motionSigmaY;
    private double _topScale = 1, _bottomScale = 1;

    /// <summary>Multipliers (0–1) for how much of the edge blur+fade to show on
    /// each edge — 0 = fully sharp (used when scroll is exactly at that
    /// boundary so the first/last content isn't hidden). Set from MainWindow
    /// as the ScrollViewer's Offset changes.</summary>
    public void SetEdgeScales(double topScale, double bottomScale)
    {
        topScale = Math.Clamp(topScale, 0, 1);
        bottomScale = Math.Clamp(bottomScale, 0, 1);
        if (Math.Abs(_topScale - topScale) < 0.005 && Math.Abs(_bottomScale - bottomScale) < 0.005) return;
        _topScale = topScale; _bottomScale = bottomScale;
        InvalidateVisual();
    }

    /// <summary>Pixels near top / bottom edge that get the fade-blur (outermost extent).</summary>
    private const float EdgeBand = 80f;
    /// <summary>Progressive blur layers: (bandFromEdgePx, sigma).
    /// Compositing multiple bands makes sigma grow as you approach the edge —
    /// outermost pixels get the strongest blur, inner boundary is crisp. Each
    /// layer masks with a gradient so blend seams are invisible.</summary>
    private static readonly (float band, float sigma)[] EdgeLayers =
    {
        (80f,  2f),   // wide, mild — always visible across the fade region
        (48f,  6f),   // middle
        (24f, 12f),   // tight strip nearest the edge
        (10f, 20f),   // very tip: heavy blur
    };

    public void SetSnapshot(Bitmap? snapshot, double motionSigmaY)
    {
        _snapshot = snapshot;
        _motionSigmaY = motionSigmaY;
        InvalidateVisual();
    }

    public override void Render(DrawingContext ctx)
    {
        if (_snapshot is null) return;
        ctx.Custom(new BlurCompositeOp(new Rect(Bounds.Size), _snapshot, _motionSigmaY, _topScale, _bottomScale));
    }

    private sealed class BlurCompositeOp : ICustomDrawOperation
    {
        private readonly Rect _bounds;
        private readonly Bitmap _bitmap;
        private readonly double _motionSigmaY;
        private readonly double _topScale, _bottomScale;

        public BlurCompositeOp(Rect bounds, Bitmap bitmap, double motionSigmaY, double topScale, double bottomScale)
        {
            _bounds = bounds; _bitmap = bitmap; _motionSigmaY = motionSigmaY;
            _topScale = topScale; _bottomScale = bottomScale;
        }

        public Rect Bounds => _bounds;
        public bool HitTest(Point p) => false;
        public bool Equals(ICustomDrawOperation? other) => false;
        public void Dispose() { }

        public void Render(ImmediateDrawingContext context)
        {
            var lf = context.TryGetFeature<ISkiaSharpApiLeaseFeature>();
            if (lf is null) return;
            using var lease = lf.Lease();
            var canvas = lease.SkCanvas;

            using var skImage = BitmapToSkImage(_bitmap);
            if (skImage is null) return;

            var dst = SKRect.Create(
                (float)_bounds.X, (float)_bounds.Y,
                (float)_bounds.Width, (float)_bounds.Height);

            // (1) Motion blur across the whole viewport, only when moving fast
            if (_motionSigmaY >= 1.5)
            {
                using var motionFilter = SKImageFilter.CreateBlur(0.5f, (float)_motionSigmaY);
                using var motionPaint = new SKPaint { ImageFilter = motionFilter, IsAntialias = true };
                canvas.DrawImage(skImage, dst, motionPaint);
            }

            // Edge effects (blur + darken) — always on when a snapshot exists
            // (MainWindow only keeps snapshot alive if the page needs to scroll).
            // Not gated on motionSigmaY, so idle-but-scrollable pages still fade.

            // (2) Multi-layer edge blur — sigma grows toward the outermost pixels
            //     via progressively tighter bands each masked by DstIn gradient.
            //     Each edge scales by its scroll-position multiplier so the first
            //     row (offset=0) and the last row (offset=max) render sharp.
            foreach (var (band, sigma) in EdgeLayers)
            {
                if (_topScale > 0.02)
                    DrawEdgeBlur(canvas, skImage, dst, top: true,  bandExtent: band, sigma: sigma * (float)_topScale);
                if (_bottomScale > 0.02)
                    DrawEdgeBlur(canvas, skImage, dst, top: false, bandExtent: band, sigma: sigma * (float)_bottomScale);
            }
        }

        private static void DrawEdgeBlur(SKCanvas canvas, SKImage src, SKRect dst, bool top,
                                         float bandExtent, float sigma)
        {
            var bandY = top ? dst.Top : dst.Bottom - bandExtent;
            var band = new SKRect(dst.Left, bandY, dst.Right, bandY + bandExtent);
            if (band.Height <= 0) return;

            using (new SKAutoCanvasRestore(canvas))
            {
                canvas.ClipRect(band);
                canvas.SaveLayer(null);

                using var blur = SKImageFilter.CreateBlur(sigma, sigma);
                using var blurPaint = new SKPaint { ImageFilter = blur, IsAntialias = true };
                canvas.DrawImage(src, dst, blurPaint);

                // Gradient: alpha=1 at OUTER edge → 0 at inner boundary. Tighter
                // bands (smaller bandExtent) with higher sigma are hidden except
                // right at the edge, so the visible strongest-blur region is
                // narrow and only the outermost pixels.
                var gradStart = new SKPoint(0, top ? band.Top : band.Bottom);
                var gradEnd   = new SKPoint(0, top ? band.Bottom : band.Top);
                using var maskShader = SKShader.CreateLinearGradient(
                    gradStart, gradEnd,
                    new[] { SKColors.White, SKColors.Transparent },
                    null, SKShaderTileMode.Clamp);
                using var maskPaint = new SKPaint
                {
                    Shader = maskShader,
                    BlendMode = SKBlendMode.DstIn,
                };
                canvas.DrawRect(band, maskPaint);
                canvas.Restore();
            }
        }

        private static SKImage? BitmapToSkImage(Bitmap b)
        {
            var pixSize = b.PixelSize;
            var stride = pixSize.Width * 4;
            var buf = System.Buffers.ArrayPool<byte>.Shared.Rent(stride * pixSize.Height);
            try
            {
                var handle = System.Runtime.InteropServices.GCHandle.Alloc(
                    buf, System.Runtime.InteropServices.GCHandleType.Pinned);
                try
                {
                    b.CopyPixels(
                        new PixelRect(0, 0, pixSize.Width, pixSize.Height),
                        handle.AddrOfPinnedObject(), stride * pixSize.Height, stride);
                }
                finally { handle.Free(); }
                var info = new SKImageInfo(pixSize.Width, pixSize.Height,
                    SKColorType.Bgra8888, SKAlphaType.Premul);
                return SKImage.FromPixelCopy(info, buf, stride);
            }
            finally { System.Buffers.ArrayPool<byte>.Shared.Return(buf); }
        }
    }
}
