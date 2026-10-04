package dev.s1mp1e.o.glass.render;

/**
 * The 1.8.9 counterpart of LiquidGlass26's {@code render/GlassCorners} (PORT_SPEC
 * feature I). One helper so every <b>new</b> glass piece gets <em>this version's
 * HUD hotbar corner radius</em> in absolute GUI px (rule R2), without touching the
 * corner radius of any surface that already exists in 1.8.9.
 *
 * <h3>Where the number comes from</h3>
 * 1.8.9's glass hotbar ({@code GlassHudHandler.onHotbar}) draws its base strip
 * {@code GlassRenderer.glass(x0, bottom-22, x1, bottom, PAD_PILL, corner=1.0, ...)}
 * under a {@code scale(1.15)} pose. The glass shader ({@code glass.fsh}) computes
 * <pre>radius = min(halfW, halfH) * CORNER_FRAC(0.5) * cornerKnob</pre>
 * so a 22 px-tall strip with knob 1.0 has local radius {@code (22/2)*0.5*1.0 = 5.5}
 * px, and under the 1.15x pose the ABSOLUTE radius is {@code 5.5 * 1.15 = 6.325}
 * GUI px — the same 6.325 the 26.2 spec measured.
 *
 * <h3>How a knob maps to that radius at an arbitrary size</h3>
 * The shader's radius scales with the element's short side, so the same knob is a
 * different absolute radius per size. To pin a {@code w x h} rect to the hotbar
 * radius we invert the shader:
 * <pre>cornerKnob(w,h) = min(1, HOTBAR_RADIUS / (min(w,h)/4)) = min(1, 4*R/min(w,h))</pre>
 * Rects whose short side is below {@code 4*R} become capsules (knob clamps to 1).
 *
 * <p>For the SDF fill primitive {@link GlassRenderer#roundRect} — which takes a
 * radius in px directly, not a knob — {@link #radiusPx} just returns the clamped
 * absolute radius.
 *
 * <p><b>Do NOT</b> apply this to existing surfaces (hotbar, inventory panel,
 * config panel, buttons, sliders, tooltips): their radius is already correct and
 * must not change. Use it only for the new BATCH-A/B pieces — the creative tab
 * pills (B), the effect strip (F) and later the boss-bar capsule (G3).
 */
public final class GlassCorners {

    private GlassCorners() {}

    /** The glass hotbar's corner radius in absolute GUI px (see class note). */
    public static final float HOTBAR_RADIUS = 6.325f;

    /** {@code glass.fsh}'s {@code CORNER_FRAC}; radius = min(halfW,halfH)*0.5*knob. */
    private static final float CORNER_FRAC = 0.5f;

    /**
     * The corner knob (0..1) to pass to {@link GlassRenderer#glass} so a
     * {@code w x h} rect drawn at pose-identity gets the hotbar corner radius.
     * Clamps to 1 (capsule) when the short side is too small to hold the radius.
     */
    public static float cornerKnob(float w, float h) {
        return knobFor(HOTBAR_RADIUS, w, h);
    }

    /** {@link #cornerKnob(float, float)} for an arbitrary target radius in px. */
    public static float knobFor(float radiusPx, float w, float h) {
        float shortSide = Math.max(1f, Math.min(w, h));
        float knob = radiusPx / (shortSide * 0.5f * CORNER_FRAC); // = 4*r/short
        if (knob < 0f) knob = 0f;
        if (knob > 1f) knob = 1f;
        return knob;
    }

    /**
     * The absolute hotbar corner radius in px, clamped to the rect's half-size,
     * for the {@link GlassRenderer#roundRect} fill primitive (which takes px, not
     * a knob).
     */
    public static float radiusPx(float w, float h) {
        return Math.min(HOTBAR_RADIUS, Math.max(0f, Math.min(w, h) / 2f));
    }
}
