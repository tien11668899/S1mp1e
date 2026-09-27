package dev.s1mp1e.glass.render;

/**
 * One corner radius for every NEW liquid-glass surface: the HUD hotbar's (user rule R2 "圓角都要一樣").
 *
 * <p>The 1.17.1 core-profile {@code glass.fsh} computes {@code radius = min(halfW, halfH) * CORNER_FRAC * cornerKnob}
 * with {@code CORNER_FRAC = 0.5} (i.e. {@code radius = min(w,h) * 0.25 * knob}), so the same knob gives a DIFFERENT
 * absolute radius on every element size. This helper turns the hotbar's radius into the knob a given rectangle needs,
 * so any surface built through it has exactly the hotbar corner in GUI px.
 *
 * <p>The hotbar strip is 22 GUI px tall, drawn under the {@link HudLayout#SCALE} (1.15x) model-view scale with the
 * GLASS corner knob 1.0: {@code (22 * 1.15 / 2) * 0.5 * 1.0 = 6.325} GUI px. Rectangles whose short side is below
 * {@code 4 * HOTBAR_RADIUS} cannot reach it and become capsules (knob clamped to 1.0).
 *
 * <p>Unlike 26.2 (whose glass colour packs the corner as a byte), the 1.17.1 {@link GlassRenderer#glass} takes the
 * corner knob as a plain {@code float}, so {@link #knob(float, float)} returns that float directly. Sizes passed in
 * must be in the same units as the pose the rect is submitted under (plain GUI px at identity pose).
 *
 * <p><b>Do NOT apply this to existing surfaces</b> (hotbar, inventory/config panels, buttons, sliders, tooltips) —
 * their radius is already correct and must not change (R2). It is only for the NEW BATCH-A/B pieces (effect strip,
 * creative tab pills, boss-bar capsule, …).
 */
public final class GlassCorners {

    private GlassCorners() {}

    /** The hotbar glass strip's corner radius in GUI px (22 tall x 1.15 scale, knob 1.0). */
    public static final float HOTBAR_RADIUS = 22.0f * HudLayout.SCALE / 4.0f;

    /**
     * The GLASS-program corner knob (0..1 of the half-size) that gives a {@code w x h} glass rect the hotbar radius.
     * Since {@code radius = min(w,h) * 0.25 * knob}, {@code knob = 4 * HOTBAR_RADIUS / min(w,h)}, clamped to 1
     * (a rect too small to reach the radius becomes a capsule).
     */
    public static float knob(float w, float h) {
        float m = Math.min(w, h);
        if (m <= 0f) return 1f;
        float k = 4f * HOTBAR_RADIUS / m;
        return k < 1f ? k : 1f;
    }

    /** The hotbar radius clamped to a {@code w x h} rect's half-size, in GUI px — for the ROUND program
     *  ({@link GlassRenderer#roundRect}) which takes a radius directly rather than a knob. */
    public static float radiusPx(float w, float h) {
        float half = Math.min(w, h) * 0.5f;
        return Math.min(HOTBAR_RADIUS, half);
    }
}
