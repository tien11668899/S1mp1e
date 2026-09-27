package dev.s1mp1e.glass.anim;

/**
 * A plain 0..1 opacity ramp.
 *
 * <p>Deliberately dumb: one timestamp, linear interpolation, and every
 * {@link #to(float)} starts a fresh leg from the previous leg's endpoint. It
 * does NOT sample the live value to continue an in-flight fade — that
 * "interruptible" behaviour made the value depend on when it happened to be
 * read, which is exactly the kind of order-dependence that produced bugs.
 * Here {@link #value()} is a pure function of the clock and the last
 * {@link #to} call, so it reads the same no matter how often it is queried.
 */
public final class Fade {

    private final float durationMs;   // default leg length
    private float legMs;              // length of the leg in flight

    private float value;              // current opacity
    private float from;               // opacity this leg began at
    private float target;             // where this leg is heading
    private long  legStart;           // nanos at leg start, 0 = settled

    public Fade(float initial, float durationMs) {
        this.value = initial;
        this.from = initial;
        this.target = initial;
        this.durationMs = durationMs;
        this.legMs = durationMs;
    }

    /** Ramp to {@code t} (0 or 1) over the default duration. */
    public void to(float t) {
        to(t, durationMs);
    }

    /** Ramp to {@code t} over a specific duration. */
    public void to(float t, float ms) {
        if (t == target) return;
        from = target;                // previous endpoint — NOT the live value
        target = t;
        legMs = ms <= 0f ? 1f : ms;
        legStart = System.nanoTime();
    }

    /**
     * Ramp to {@code t} starting from wherever the value STANDS RIGHT NOW,
     * instead of from the previous leg's endpoint.
     *
     * <p>For a fade that gets interrupted constantly — the slot hover pill, the
     * quick-craft drag highlight — {@link #to} is wrong: dragging the cursor
     * across the gap between two slot rows fires {@code to(0)}, and re-entering
     * a slot ~30 ms later fires {@code to(1)} with {@code from = target = 0},
     * so the pill snaps from ~0.7 straight down to 0 and climbs again. That
     * snap is a visible flicker, and where it snaps from varies with frame
     * timing.
     *
     * <p>Keep using {@link #to} for fades that must stay a pure function of the
     * clock (screen open/close, panel ghost, tooltip); use this one only where
     * being interruptible is the point.
     */
    public void retarget(float t) {
        retarget(t, durationMs);
    }

    /** {@link #retarget(float)} over a specific duration. */
    public void retarget(float t, float ms) {
        if (t == target) return;
        from = value();               // live value — continue, don't restart
        target = t;
        legMs = ms <= 0f ? 1f : ms;
        legStart = System.nanoTime();
    }

    /** Jump straight there, cancelling any leg in flight. */
    public void snap(float t) {
        value = t; from = t; target = t; legStart = 0L; legMs = durationMs;
    }

    /** Current opacity. Pure function of the clock; safe to call repeatedly. */
    public float value() {
        if (legStart == 0L) return value;
        float p = (System.nanoTime() - legStart) / 1.0e6f / legMs;
        if (p >= 1f) {
            value = target;
            legStart = 0L;
            return value;
        }
        value = from + (target - from) * p;   // linear
        return value;
    }

    public float target()   { return target; }
    public boolean isIdle() { return legStart == 0L; }
    /** True while anything is visible at all. */
    public boolean isVisible() { return value() > 0.004f; }
}
