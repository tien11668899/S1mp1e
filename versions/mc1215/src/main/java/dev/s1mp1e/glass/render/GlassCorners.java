package dev.s1mp1e.glass.render;

/**
 * One corner radius for every NEW liquid-glass piece: the HUD hotbar's (user rule R2 "圓角都要一樣").
 *
 * <p>This is the 1.21.1 (DrawContext / core-profile) port of 26.2's {@code GlassCorners}. The physics is
 * identical — the glass shaders compute {@code radius = min(halfW, halfH) * 0.5 * cornerFrac} (see
 * {@code glass.fsh}: {@code radius = min(halfPx.x, halfPx.y) * CORNER_FRAC * vColor.r} with {@code CORNER_FRAC = 0.5}),
 * so the same knob gives a different ABSOLUTE radius on every element size. This helper turns the hotbar's radius into
 * the corner FRACTION a given rectangle needs, so any surface built through it has exactly the hotbar corner in GUI px.
 *
 * <p>Unlike 26.2 (whose glass quad carries a colour-int with the corner packed into a byte, 0..255) this line's
 * {@link GlassRenderer#glass}/{@link GlassRenderer#lens} take the corner as a FLOAT fraction 0..1 directly, so this
 * helper returns the float fraction rather than a byte. {@link GlassRenderer#roundRect} instead takes a radius in px,
 * so {@link #hotbarRadiusPx} is provided for scrims / solid fills.
 *
 * <p>The hotbar strip is 22 GUI px tall, drawn under a 1.15x pose scale ({@code InGameHudMixin.SCALE}) with corner
 * knob 1.0: {@code (22 * 1.15 / 2) * 0.5 * 1.0 = 6.325} GUI px — identical geometry to 26.2 (measured 6.29 in DevShot).
 * Rectangles whose short side is below {@code 4 * HOTBAR_RADIUS} cannot reach it and become capsules (fraction clamped
 * to 1.0). Sizes passed in must be plain GUI px at identity pose (the pose the rect is submitted under).
 *
 * <p><b>Do NOT apply this to surfaces that already exist as glass in this version</b> (hotbar strip + selector pill,
 * inventory / creative / container panels, config panel, buttons, sliders, tooltips) — their radius is already correct
 * and must not change (R2). Use it only for the NEW pieces: creative tab pills (B), the effect strip (F), the boss-bar
 * capsule (G3, batch B) and any other new glass piece.
 */
public final class GlassCorners {

    private GlassCorners() {}

    /** The hotbar glass strip's corner radius in GUI px: 22 px tall * 1.15 pose scale / 4 = 6.325. */
    public static final float HOTBAR_RADIUS = 22.0f * 1.15f / 4.0f;

    /**
     * Corner FRACTION (0..1, the {@code vColor.r} knob {@link GlassRenderer#glass}/{@link GlassRenderer#lens} take)
     * that gives a {@code w x h} glass rect exactly the hotbar radius. Clamped to 1.0 (capsule) for small rects.
     */
    public static float hotbarCornerFrac(float w, float h) {
        return cornerFrac(w, h, HOTBAR_RADIUS);
    }

    /** Corner FRACTION that gives a {@code w x h} glass rect the absolute {@code radiusPx} radius (clamped 0..1). */
    public static float cornerFrac(float w, float h, float radiusPx) {
        float m = Math.max(1.0f, Math.min(w, h));
        float f = 4.0f * radiusPx / m;   // invert radius = (m/2) * 0.5 * f  ->  f = 4*radius/m
        return f < 0.0f ? 0.0f : (f > 1.0f ? 1.0f : f);
    }

    /** Absolute hotbar radius in px, clamped to the rect's half-size — for {@link GlassRenderer#roundRect} (px input). */
    public static float hotbarRadiusPx(float w, float h) {
        return Math.min(HOTBAR_RADIUS, Math.min(w, h) / 2.0f);
    }
}
