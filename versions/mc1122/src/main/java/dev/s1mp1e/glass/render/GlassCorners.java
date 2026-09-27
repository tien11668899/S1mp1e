package dev.s1mp1e.glass.render;

/**
 * Feature (I) — the hotbar corner-radius helper (PORT_SPEC &sect;2I, rule R2).
 *
 * <p>Every <em>new</em> glass piece this port adds (the status-effect strip, the
 * creative tab pills, the boss-bar capsule, ...) must round its corners with the
 * SAME absolute radius the HUD hotbar strip uses, and existing surfaces (hotbar,
 * inventory panel, config panel, buttons, sliders, tooltips) must keep the radius
 * they already have. This helper computes that radius for the 1.12.2 render
 * family; it never touches an existing surface.
 *
 * <h3>Where 6.325 px comes from</h3>
 * {@link dev.s1mp1e.glass.hook.GlassHudHandler} draws the hotbar strip with
 * {@code GlassRenderer.glass(..., corner=1.0, ...)} on a 182&times;22 rect, under a
 * {@code SCALE = 1.15} pose about the bar's bottom-centre. {@code glass.fsh} rounds
 * a quad by
 * <pre>radius_px = min(halfW, halfH) &middot; CORNER_FRAC &middot; cornerKnob</pre>
 * with {@code CORNER_FRAC = 0.5}. For the strip that is
 * {@code min(182,22)/2 &middot; 0.5 &middot; 1.0 = 5.5} px in the strip's own space,
 * and the 1.15&times; pose scales it to {@code 5.5 &middot; 1.15 = 6.325} absolute
 * GUI px (measured 6.29 in DevShot). This matches the reference
 * {@code HOTBAR_RADIUS = 22&middot;1.15/4} exactly.
 *
 * <h3>Turning a radius into a corner knob</h3>
 * A new piece is drawn WITHOUT the 1.15 pose (normal GUI space), so its absolute
 * radius must be 6.325 px directly. Inverting {@code glass.fsh}:
 * <pre>cornerKnob = 4 &middot; R / min(w, h)</pre>
 * clamped to {@code [0,1]} — a rect whose short side is &le; {@code 4&middot;R}
 * becomes a full capsule ({@code knob = 1}). This is the float 0..1 knob the
 * 1.12.2 {@link GlassRenderer#glass} call takes (26.2 packs the same number into a
 * byte; the maths is identical).
 */
public final class GlassCorners {

    private GlassCorners() {}

    /** The hotbar strip's absolute corner radius, in GUI px (see class doc). */
    public static final float HOTBAR_RADIUS = 6.325f;

    /** {@code glass.fsh}'s fixed corner fraction (radius = min(half)&middot;this&middot;knob). */
    private static final float CORNER_FRAC = 0.5f;

    /**
     * The GLASS/LENS corner knob (0..1) that gives a {@code w&times;h} rect the
     * hotbar radius {@link #HOTBAR_RADIUS}, for a piece drawn in normal GUI space
     * (no hotbar pose scale). Rects narrower than {@code 4&middot;HOTBAR_RADIUS}
     * clamp to a capsule.
     *
     * <p>Pass this straight into {@link GlassRenderer#glass}'s {@code corner}
     * argument (or {@link GlassRenderer#lens}'s).
     */
    public static float hotbarCorner(float w, float h) {
        float m = Math.min(w, h);
        if (m <= 0f) return 1f;
        float knob = HOTBAR_RADIUS / (m * 0.5f * CORNER_FRAC); // = 4R / min(w,h)
        return knob < 0f ? 0f : (knob > 1f ? 1f : knob);
    }

    /**
     * The hotbar radius as an absolute px value, clamped to a rect's half-size —
     * for the AA SDF primitives that take a radius directly
     * ({@link GlassRenderer#roundRect}, {@code GlassWidgets.fillRound}), such as
     * the effect-strip separators.
     */
    public static float hotbarRadiusPx(float w, float h) {
        float half = Math.min(w, h) * 0.5f;
        return Math.min(half, HOTBAR_RADIUS);
    }
}
