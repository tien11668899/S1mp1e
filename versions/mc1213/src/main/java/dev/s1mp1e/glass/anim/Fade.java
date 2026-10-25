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
