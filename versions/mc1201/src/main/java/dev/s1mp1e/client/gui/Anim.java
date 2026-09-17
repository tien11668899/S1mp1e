package dev.s1mp1e.client.gui;

/**
 * A clock-based eased scalar — the shared "banking" animator for sliding UI
 * (the category-tab highlight, the module-selection highlight). Each {@link #to}
 * launches a fresh leg from the live value with a gentle ease-out-back overshoot,
 * so a target change mid-slide continues smoothly instead of snapping. {@link #value()}
 * is a pure function of {@link System#nanoTime()} and the last {@code to}/{@code snap},
 * so it reads the same however often it is queried in a frame.
 */
public final class Anim {

    private static final float DURATION_MS = 220f;

    private float from, to, settled;
    private long  legStart;   // nanos; 0L = settled

    public Anim(float initial) { this.from = this.to = this.settled = initial; this.legStart = 0L; }

    /** Slide to {@code t}, continuing from wherever the value stands right now. */
    public void to(float t) {
        if (t == to) return;
        from = value();
        to = t;
        legStart = System.nanoTime();
    }

    /** Jump straight there, cancelling any leg in flight (e.g. on a list rebuild). */
    public void snap(float t) { from = to = settled = t; legStart = 0L; }

    public float value() {
        if (legStart == 0L) return settled;
        float lin = (System.nanoTime() - legStart) / 1.0e6f / DURATION_MS;
        if (lin >= 1f) { legStart = 0L; settled = to; return to; }
        return from + (to - from) * easeBack(lin);
    }

    /** Ease-out-back: 0→1 with one gentle overshoot, settling exactly at 1. */
    private static float easeBack(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        final float c1 = 1.2f;            // overshoot strength (~+6%)
        final float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
}
