using System;
using Avalonia;
using Avalonia.Media;
using SkiaSharp;

namespace LiquidGlassAvaloniaUI
{
    /// <summary>
    /// Continuous-curvature ("squircle") outlines shared by every pass of a <see cref="LiquidGlassSurface"/> whose
    /// <see cref="LiquidGlassSurface.CornerExponent"/> is above 2: the lens SDF, the highlight normal, the shadow, the
    /// clip and (via <see cref="CreateSquircleGeometry"/>) the host's own clip / rim geometry all use the same
    /// superellipse corner |x/a|^n + |y/a|^n = 1, so nothing shows a seam. n = 2 is the ordinary circular corner;
    /// iOS's continuous corner of radius R (smoothing 0.66) fits n ≈ 2.8 with a ≈ 1.31 R.
    /// </summary>
    public static class LiquidGlassShapes
    {
        /// <summary>Samples one superellipse corner quadrant as points, from the start edge tangent to the end edge tangent.</summary>
        private static void SampleCorner(double cx, double cy, double sx, double sy, double a, double n, bool fromTop, int segs, Span<(double X, double Y)> pts)
        {
            for (int i = 0; i <= segs; i++)
            {
                double th = Math.PI / 2 * i / segs;
                // the quadrant is swept from the horizontal edge (θ where the point sits on the top/bottom edge) to the
                // vertical edge, or the reverse, so consecutive corners join clockwise
                double t = fromTop ? Math.PI / 2 - th : th;
                double c = Math.Pow(Math.Cos(t), 2.0 / n), s = Math.Pow(Math.Sin(t), 2.0 / n);
                pts[i] = (cx + sx * a * c, cy + sy * a * s);
            }
        }

        /// <summary>Corner centres (inset by a) and direction signs, clockwise from the top-left corner.</summary>
        private static (double cx, double cy, double sx, double sy, bool fromTop)[] Corners(double left, double top, double right, double bottom, double a) => new[]
        {
            (left + a,  top + a,    -1.0, -1.0, false),   // TL: from the left edge up to the top edge
            (right - a, top + a,     1.0, -1.0, true),    // TR: from the top edge down to the right edge
            (right - a, bottom - a,  1.0,  1.0, false),   // BR: from the right edge down to the bottom edge
            (left + a,  bottom - a, -1.0,  1.0, true),    // BL: from the bottom edge up to the left edge
        };

        /// <summary>Skia path of the squircle-cornered rectangle (clockwise), corner semi-axis <paramref name="a"/>, exponent <paramref name="n"/>.</summary>
        public static SKPath CreateSquirclePath(SKRect r, float a, float n, int segs = 12)
        {
            a = Math.Max(0f, Math.Min(a, Math.Min(r.Width, r.Height) / 2f));
            SKPath path = new();
            if (a <= 0.01f || n <= 2.001f)
            {
                using SKRoundRect rr = new(r, a, a);
                path.AddRoundRect(rr, SKPathDirection.Clockwise);
                return path;
            }

            Span<(double X, double Y)> pts = stackalloc (double X, double Y)[segs + 1];
            bool first = true;
            foreach (var (cx, cy, sx, sy, fromTop) in Corners(r.Left, r.Top, r.Right, r.Bottom, a))
            {
                SampleCorner(cx, cy, sx, sy, a, n, fromTop, segs, pts);
                if (first) { path.MoveTo((float)pts[0].X, (float)pts[0].Y); first = false; }
                else path.LineTo((float)pts[0].X, (float)pts[0].Y);
                AppendCatmullRom(pts, segs, (c1, c2, p) => path.CubicTo((float)c1.X, (float)c1.Y, (float)c2.X, (float)c2.Y, (float)p.X, (float)p.Y));
            }
            path.Close();
            return path;
        }

        /// <summary>Avalonia geometry twin of <see cref="CreateSquirclePath"/> for a host's Clip / Path.Data.</summary>
        public static Geometry CreateSquircleGeometry(Rect r, double a, double n, int segs = 12)
        {
            a = Math.Max(0, Math.Min(a, Math.Min(r.Width, r.Height) / 2));
            if (a <= 0.01 || n <= 2.001)
                return new RectangleGeometry(r) { RadiusX = a, RadiusY = a };

            var geo = new StreamGeometry();
            using (StreamGeometryContext ctx = geo.Open())
            {
                Span<(double X, double Y)> pts = stackalloc (double X, double Y)[segs + 1];
                bool first = true;
                foreach (var (cx, cy, sx, sy, fromTop) in Corners(r.Left, r.Top, r.Right, r.Bottom, a))
                {
                    SampleCorner(cx, cy, sx, sy, a, n, fromTop, segs, pts);
                    if (first) { ctx.BeginFigure(new Point(pts[0].X, pts[0].Y), true); first = false; }
                    else ctx.LineTo(new Point(pts[0].X, pts[0].Y));
                    AppendCatmullRom(pts, segs, (c1, c2, p) => ctx.CubicBezierTo(new Point(c1.X, c1.Y), new Point(c2.X, c2.Y), new Point(p.X, p.Y)));
                }
                ctx.EndFigure(true);
            }
            return geo;
        }

        /// <summary>Converts the sampled polyline to cubic Béziers (Catmull-Rom → Bézier, tangents from the neighbours).</summary>
        private static void AppendCatmullRom(Span<(double X, double Y)> pts, int segs, Action<(double X, double Y), (double X, double Y), (double X, double Y)> cubic)
        {
            for (int i = 0; i < segs; i++)
            {
                var p0 = i > 0 ? pts[i - 1] : pts[i];
                var p1 = pts[i];
                var p2 = pts[i + 1];
                var p3 = i + 2 <= segs ? pts[i + 2] : pts[i + 1];
                var c1 = (p1.X + (p2.X - p0.X) / 6, p1.Y + (p2.Y - p0.Y) / 6);
                var c2 = (p2.X - (p3.X - p1.X) / 6, p2.Y - (p3.Y - p1.Y) / 6);
                cubic(c1, c2, p2);
            }
        }
    }
}
