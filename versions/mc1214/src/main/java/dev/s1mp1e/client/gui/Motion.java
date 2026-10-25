package dev.s1mp1e.client.gui;

/**
 * Apple-style motion for the liquid-glass controls (config switch / slider and the vanilla menu sliders).
 * Identical in every version: no Minecraft types.
 *
 * <p>Springs use SwiftUI's {@code Spring(duration:bounce:)} parametrisation: {@code ω = 2π / duration},
 * {@code k = ω²}, {@code ζ = 1 − bounce}, {@code c = 2ζω}, mass 1. Every control uses {@code bounce = 0}
 * (critically damped). The user's iOS 26 recordings show no overshoot anywhere; the "liquid" look comes from
 * the lens bulge and its speed stretch, not from ringing.
 *
 * <p>Integration is semi-implicit Euler with substeps of at most 1/240 s, driven by the REAL frame time
 * ({@link Clock}), so the motion is the same at 30 fps and 240 fps. Retargeting keeps the velocity, so an
 * interrupted animation carries on smoothly instead of restarting.
 */
public final class Motion {
    private Motion() {}

    /** Grab: white pill → glass lens. The recording snaps in 50–80 ms. */
    public static final float MORPH_IN_S  = 0.13f;
    /** Release: glass lens → white pill. The recording collapses monotonically over ~250–270 ms. */
    public static final float MORPH_OUT_S = 0.28f;
    /** Thumb settling onto its value after release (UIKit's default spring: 0.5 s, damping 1). */
    public static final float SETTLE_S    = 0.50f;
    /** Thumb catching up after a jump (track click, arrow-key step, typed value). */
    public static final float JUMP_S      = 0.24f;
    /** Switch knob travel (flip tempo from the recordings, critically damped). */
    public static final float TRAVEL_S    = 0.30f;
    /** Hover highlight ease. */
    public static final float HOVER_S     = 0.22f;

    /** Presses shorter than this count as a tap; the lens then lingers for {@link #TAP_HOLD_S} before re-forming. */
    public static final float TAP_S      = 0.15f;
    public static final float TAP_HOLD_S = 0.20f;

    /**
     * UIScrollView's rubber band: how far (px) to draw something dragged {@code overshootPx} past its end.
     * It approaches {@code dimPx} but never reaches it (c = 0.55).
     */
    public static float rubberBand(float overshootPx, float dimPx) {
        if (overshootPx <= 0f || dimPx <= 0f) return 0f;
        return (1f - 1f / (overshootPx * 0.55f / dimPx + 1f)) * dimPx;
    }

    /**
     * Pressed slider lens vs the rest pill, fitted sub-pixel to the recording (rest 111×72 capsule, pressed
     * 164×114): 1.48× wide, 1.58× tall, and a rounded RECTANGLE rather than a capsule, with corner radius
     * 0.94 × its half-height (flat-ish top and bottom, big round corners).
     */
    public static final float LENS_W = 1.48f, LENS_H = 1.58f, LENS_CORNER = 0.94f;
    /** Drag stretch follows a filtered speed and settles on a slightly bouncy spring: the lens lags speed by
     *  ~130–160 ms and briefly overshoots tall/narrow when the drag reverses. */
    public static final float SPEED_TAU_S = 0.06f, STRETCH_S = 0.28f, STRETCH_BOUNCE = 0.5f;

    /**
     * Stretch factor λ for a filtered drag speed. Width × λ, height ÷ λ, so the area stays constant (measured
     * W·H constant within 3 % across all speeds). Saturates near 1.18, around 17 pill-widths per second.
     */
    public static float stretchTarget(float speedPx, float pillWidthPx) {
        float s = pillWidthPx <= 0f ? 0f : speedPx / pillWidthPx;
        return 1f + 0.18f * (1f - (float) Math.exp(-s / 6.3f));
    }

    /** Lens at full morph for stretch {@code lambda}: {@code out} = {width scale, height scale, corner fraction of the
     *  half-height}. The corners relax to a full capsule as the stretch saturates, as they do in the recording. */
    public static void lensShape(float lambda, float[] out) {
        float l = Math.max(0.85f, Math.min(1.30f, lambda));
        out[0] = LENS_W * l;
        out[1] = LENS_H / l;
        out[2] = Math.min(1f, LENS_CORNER + (1f - LENS_CORNER) * clamp01((l - 1f) / 0.18f));
    }

    /** Exponential smoothing factor for a time constant. */
    public static float ema(float dt, float tauS) {
        return 1f - (float) Math.exp(-dt / Math.max(1e-4f, tauS));
    }

    public static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** Per-widget frame clock: seconds since the previous tick (1/60 on the first), clamped to 50 ms. */
    public static final class Clock {
        private long last;
        public float tick() {
            long now = System.nanoTime();
            float dt = last == 0L ? 1f / 60f : (now - last) / 1.0e9f;
            last = now;
            return dt < 0f ? 0f : Math.min(dt, 0.05f);
        }
    }

    /** Critically damped (or bouncy) spring with mass 1. */
    public static final class Spring {
        private float k, c;
        public float x, v, target;

        public Spring(float durationS, float initial) {
            tune(durationS, 0f);
            snap(initial);
        }

        /** Re-tunes stiffness/damping and keeps the position and velocity. */
        public Spring tune(float durationS, float bounce) {
            float w = (float) (2.0 * Math.PI / Math.max(0.01f, durationS));
            float zeta = bounce >= 0f ? 1f - bounce : 1f / (1f + bounce);
            k = w * w;
            c = 2f * zeta * w;
            return this;
        }

        public void snap(float p) { x = p; v = 0f; target = p; }

        /** New goal; the velocity carries over. */
        public Spring retarget(float t) { target = t; return this; }

        public void update(float dt) {
            if (dt <= 0f) return;
            dt = Math.min(dt, 0.05f);
            int n = Math.max(1, (int) Math.ceil(dt * 240f));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                float a = -k * (x - target) - c * v;
                v += a * h;
                x += v * h;
            }
            if (!Float.isFinite(x) || !Float.isFinite(v)) snap(target);
        }

        /**
         * A critically damped spring only crosses its goal when it already moves toward it faster than ω·|distance|.
         * Trims such a velocity (never reverses it), so a hand-off from a faster spring, e.g. a track-click glide
         * still in flight when the button is released, settles without overshooting.
         */
        public void capOvershoot() {
            float d = x - target;
            if (d == 0f || v == 0f || Math.signum(v) == Math.signum(d)) return;
            float vMax = (float) Math.sqrt(k) * Math.abs(d);
            if (Math.abs(v) > vMax) v = Math.signum(v) * vMax;
        }

        /**
         * For a release onto a snapped value: besides {@link #capOvershoot}, drops a velocity that still points AWAY
         * from the goal (the thumb had already passed the notch while chasing the pointer), which would otherwise
         * carry it further out and back. The settle is then monotonic, as in the recordings.
         */
        public void settleMonotonic() {
            float d = x - target;
            if (d != 0f && v != 0f && Math.signum(v) == Math.signum(d)) v = 0f;
            capOvershoot();
        }

        /** Within {@code eps} of the goal and nearly still: snaps onto it and reports true. */
        public boolean settle(float eps) {
            if (Math.abs(x - target) < eps && Math.abs(v) < eps * 20f) { x = target; v = 0f; return true; }
            return false;
        }
    }
}
