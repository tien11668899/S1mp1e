package dev.s1mp1e.glass.anim;

/**
 * Ported verbatim from LiquidGlass26. Semi-implicit Euler, which is stable only
 * while {@code dt < 2/omega} — at omega 55 that is 36ms, below a dropped-frame
 * delta — so callers MUST sub-step at 1/120s and NaN-guard, exactly as 26.2
 * does. {@link #advance} does both for you.
 */
public final class Spring {
    private float value, target, velocity;
    public final float omega;    // rad/s
    public final float damping;  // 1.0 = critical

    public Spring(float initial, float omega, float dampingRatio) {
        this.value = initial; this.target = initial;
        this.omega = omega; this.damping = dampingRatio;
    }

    public void  setTarget(float t) { this.target = t; }
    public float target()   { return target; }
    public float value()    { return value; }
    public void  snap(float v) { value = v; target = v; velocity = 0f; }

    public void update(float dt) {
        float x = value - target;
        float k = omega * omega;
        float c = 2f * damping * omega;
        float a = -k * x - c * velocity;
        velocity += a * dt;
        value    += velocity * dt;
    }

    /** Sub-stepped advance with the NaN guard 26.2 learned to need. */
    public void advance(float dtSeconds) {
        float rem = Math.min(dtSeconds, 0.05f);
        while (rem > 0f) {
            float h = Math.min(rem, 1f / 120f);
            update(h);
            rem -= h;
        }
        if (!isFinite(value) || !isFinite(velocity)) snap(target);
    }

    private static boolean isFinite(float f) {
        return !Float.isNaN(f) && !Float.isInfinite(f);
    }

    // ---- measured Apple constants (frame-by-frame from real footage) -------
    public static final float OMEGA_SLOW = 27f;  // tooltip morph
    public static final float OMEGA_MED  = 30f;  // trailing edge
    public static final float OMEGA_LEAD = 45f;  // tooltip position
    public static final float OMEGA_SNAP = 55f;  // leading edge
    public static final float DAMPING    = 1.0f;
    public static final int   FADE_MS    = 150;
}
