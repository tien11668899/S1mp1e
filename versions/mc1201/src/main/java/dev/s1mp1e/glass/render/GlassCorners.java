package dev.s1mp1e.glass.render;

/**
 * One corner radius for every NEW liquid-glass surface: the HUD hotbar's (user rule "圓角都要一樣", spec R2).
 *
 * <p>The glass shader computes {@code radius = min(halfW, halfH) * 0.5 * cornerScale}, so the same corner scale gives a
 * DIFFERENT absolute radius on every element size. This helper turns the hotbar's radius into the corner scale a given
 * rectangle needs, so any surface built through it has exactly the hotbar corner in GUI px regardless of its size.
 *
 * <p>1.20.1 derivation (matches 26.2's {@code 22 * 1.15 / 4}): the glass hotbar strip is 22 GUI px tall
 * ({@code InGameHudMixin}: {@code stripY0 = bottom - 22}), drawn with corner 1.0 under a {@code SCALE = 1.15} pose scale.
 * The shader radius in that scaled space is {@code (22 / 2) * 0.5 * 1.0 = 5.5}, i.e. {@code 5.5 * 1.15 = 6.325} GUI px on
 * screen — identical to 26.2. Rectangles whose short side is below {@code 4 * HOTBAR_RADIUS} cannot reach that radius and
 * become capsules (scale clamped to 1.0).
 *
 * <p>Unlike 26.2 (whose glass quad's colour int carries a corner-radius BYTE), 1.20.1's {@link GlassRenderer#glass} takes
 * the corner as a 0..1 float scale directly, so this returns that scale.
 *
 * <p><b>Do NOT apply this to surfaces that already exist</b> (hotbar, inventory / config panels, buttons, sliders,
 * tooltips): their radius is already correct and must not change. Use it only for the NEW pieces of this round — the
 * creative tab pills (B), the effect strip (F), and any other new glass built this batch.
 */
public final class GlassCorners {
    private GlassCorners() {}

    /** The hotbar glass strip's corner radius in GUI px. */
    public static final float HOTBAR_RADIUS = 22f * 1.15f / 4f;   // 6.325

    /**
     * The corner SCALE (0..1, as {@link GlassRenderer#glass} wants it) that gives a {@code w x h} glass rect the hotbar
     * radius. {@code radius = min(w,h) * 0.25 * scale}, so {@code scale = 4 * HOTBAR_RADIUS / min(w,h)}, clamped to 1.
     */
    public static float cornerScale(float w, float h) {
        float m = Math.min(w, h);
        if (m <= 0f) return 1f;
        float k = 4f * HOTBAR_RADIUS / m;
        return k < 1f ? k : 1f;
    }

    /** {@link #cornerScale} under the name the 1.21.1 / 26.2 lines use (shared code is written against that name). */
    public static float hotbarCornerFrac(float w, float h) {
        return cornerFrac(w, h, HOTBAR_RADIUS);
    }

    /** Corner SCALE that gives a {@code w x h} glass rect the absolute {@code radiusPx} radius (clamped 0..1). */
    public static float cornerFrac(float w, float h, float radiusPx) {
        float m = Math.max(1.0f, Math.min(w, h));
        float f = 4.0f * radiusPx / m;   // invert radius = (m/2) * 0.5 * f  ->  f = 4*radius/m
        return f < 0.0f ? 0.0f : (f > 1.0f ? 1.0f : f);
    }

    /** {@link #radiusPx} under the 1.21.1 / 26.2 name. */
    public static float hotbarRadiusPx(float w, float h) {
        return Math.min(HOTBAR_RADIUS, Math.min(w, h) / 2.0f);
    }

    /** The same corner expressed as an absolute GUI-px radius clamped to the rect's half-size (for the ROUND primitive). */
    public static float radiusPx(float w, float h) {
        float half = Math.min(w, h) * 0.5f;
        return Math.min(half, HOTBAR_RADIUS);
    }
}
