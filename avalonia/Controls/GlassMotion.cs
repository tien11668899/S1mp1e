using System;
using System.Diagnostics;

namespace S1mp1e.Controls;

/// <summary>
/// Apple-style motion for the launcher's liquid-glass controls — a C# port of the in-game
/// <c>dev.s1mp1e.client.gui.Motion</c>, so the launcher slider moves exactly like the game's.
///
/// Springs use SwiftUI's <c>Spring(duration:bounce:)</c> parametrisation (ω = 2π/duration, k = ω²,
/// ζ = 1 − bounce, c = 2ζω, mass 1), integrated with semi-implicit Euler in substeps of at most 1/240 s
/// off the real frame time, so motion is identical at any refresh rate. Durations and the lens shape
/// were measured frame by frame from the user's iOS 26 recordings.
/// </summary>
internal static class GlassMotion
{
    /// <summary>Grab: white pill → glass lens (the recording snaps in 50–80 ms).</summary>
    public const double MorphInS = 0.13;
    /// <summary>Release: lens → white pill (collapses monotonically over ~250–270 ms).</summary>
    public const double MorphOutS = 0.28;
    /// <summary>Thumb settling onto its value after release (UIKit's default: 0.5 s, damping 1).</summary>
    public const double SettleS = 0.50;
    /// <summary>Thumb catching up after a jump (track click, typed value).</summary>
    public const double JumpS = 0.24;
    /// <summary>A press shorter than this is a tap; the lens then lingers for <see cref="TapHoldS"/>.</summary>
    public const double TapS = 0.15, TapHoldS = 0.20;

    /// <summary>Pressed lens vs the rest pill (recording: 111×72 capsule → 164×114 rounded rect,
    /// corner radius 0.94 × its half-height).</summary>
    public const double LensW = 1.48, LensH = 1.58, LensCorner = 0.94;
    /// <summary>Drag stretch follows a filtered speed and settles on a slightly bouncy spring.</summary>
    public const double SpeedTauS = 0.06, StretchS = 0.28, StretchBounce = 0.5;

    public static double Clamp01(double v) => v < 0 ? 0 : (v > 1 ? 1 : v);

    /// <summary>UIScrollView's rubber band: how far to draw something dragged <paramref name="overshoot"/> past its end.</summary>
    public static double RubberBand(double overshoot, double dim)
    {
        if (overshoot <= 0 || dim <= 0) return 0;
        return (1 - 1 / (overshoot * 0.55 / dim + 1)) * dim;
    }

    /// <summary>Stretch λ for a filtered drag speed: width × λ, height ÷ λ (the measured lens keeps its area).</summary>
    public static double StretchTarget(double speedPx, double pillWidthPx)
    {
        double s = pillWidthPx <= 0 ? 0 : speedPx / pillWidthPx;
        return 1 + 0.18 * (1 - Math.Exp(-s / 6.3));
    }

    /// <summary>Lens at full morph for stretch λ: width / height scale and corner radius as a fraction of the
    /// half-height (relaxing to a capsule as the stretch saturates, as in the recording).</summary>
    public static (double w, double h, double corner) LensShape(double lambda)
    {
        double l = Math.Clamp(lambda, 0.85, 1.30);
        return (LensW * l, LensH / l, Math.Min(1, LensCorner + (1 - LensCorner) * Clamp01((l - 1) / 0.18)));
    }

    public static double Ema(double dt, double tau) => 1 - Math.Exp(-dt / Math.Max(1e-4, tau));

    /// <summary>Per-control frame clock: seconds since the previous tick (1/60 on the first), clamped to 50 ms.</summary>
    public sealed class Clock
    {
        private long _last;
        public double Tick()
        {
            long now = Stopwatch.GetTimestamp();
            double dt = _last == 0 ? 1.0 / 60 : (now - _last) / (double)Stopwatch.Frequency;
            _last = now;
            return dt < 0 ? 0 : Math.Min(dt, 0.05);
        }
        public void Reset() => _last = 0;
    }

    /// <summary>Critically damped (or bouncy) spring with mass 1; retargeting keeps the velocity.</summary>
    public sealed class Spring
    {
        private double _k, _c;
        public double X, V, Target;

        public Spring(double durationS, double initial) { Tune(durationS, 0); Snap(initial); }

        public Spring Tune(double durationS, double bounce)
        {
            double w = 2 * Math.PI / Math.Max(0.01, durationS);
            double zeta = bounce >= 0 ? 1 - bounce : 1 / (1 + bounce);
            _k = w * w;
            _c = 2 * zeta * w;
            return this;
        }

        public void Snap(double p) { X = p; V = 0; Target = p; }
        public Spring Retarget(double t) { Target = t; return this; }

        public void Update(double dt)
        {
            if (dt <= 0) return;
            dt = Math.Min(dt, 0.05);
            int n = Math.Max(1, (int)Math.Ceiling(dt * 240));
            double h = dt / n;
            for (int i = 0; i < n; i++)
            {
                double a = -_k * (X - Target) - _c * V;
                V += a * h;
                X += V * h;
            }
            if (!double.IsFinite(X) || !double.IsFinite(V)) Snap(Target);
        }

        /// <summary>Trims a velocity that would carry a critically damped spring across its goal.</summary>
        public void CapOvershoot()
        {
            double d = X - Target;
            if (d == 0 || V == 0 || Math.Sign(V) == Math.Sign(d)) return;
            double vMax = Math.Sqrt(_k) * Math.Abs(d);
            if (Math.Abs(V) > vMax) V = Math.Sign(V) * vMax;
        }

        /// <summary>For a release onto a snapped value: also drops a velocity still pointing away from the goal.</summary>
        public void SettleMonotonic()
        {
            double d = X - Target;
            if (d != 0 && V != 0 && Math.Sign(V) == Math.Sign(d)) V = 0;
            CapOvershoot();
        }

        /// <summary>Within <paramref name="eps"/> of the goal and nearly still: snaps onto it and reports true.</summary>
        public bool Settle(double eps)
        {
            if (Math.Abs(X - Target) < eps && Math.Abs(V) < eps * 20) { X = Target; V = 0; return true; }
            return false;
        }
    }
}
