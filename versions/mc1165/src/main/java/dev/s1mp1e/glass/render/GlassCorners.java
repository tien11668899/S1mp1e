package dev.s1mp1e.glass.render;

/**
 * One corner radius for every NEW liquid-glass surface: the HUD hotbar's (user rule "圓角都要一樣" / R2).
 *
 * <p>The 1.16.5 Fabric port of LiquidGlass26's {@code GlassCorners}. The glass shader
 * ({@code glass.fsh}) computes {@code radius = min(halfW, halfH) * 0.5 * cornerKnob}, so the same
 * corner knob gives a different absolute radius on every element size. This helper turns the hotbar's
 * radius into the corner knob a given rectangle needs, so any surface built through it has exactly the
 * hotbar corner radius in GUI px.
 *
 * <p>Unlike 26.2 (whose glass quad packs the corner into a colour BYTE) this line's
 * {@link GlassRenderer#glass} takes the corner knob directly as a {@code float} in {@code [0,1]} (the
 * vertex RED channel), so this helper returns that float — {@link #hotbarCorner} is the analogue of
 * 26.2's {@code knobByte}/{@code withHotbarCorner}.
 *
 * <p>The hotbar strip on 1.16.5 is 22 GUI px tall, drawn under the {@link HudLayout#SCALE} (1.15x) pose
 * scale about its bottom-centre with corner knob 1.0 ({@code InGameHudMixin}):
 * {@code (22 * 1.15 / 2) * 0.5 * 1.0 = 6.325} GUI px absolute. Rectangles whose short side is below
 * {@code 4 * HOTBAR_RADIUS} cannot reach that radius geometrically and become capsules (knob clamped
 * to 1.0) — exactly as in 26.2.
 *
 * <p><b>Do NOT apply this to surfaces that already exist</b> (hotbar, inventory/creative panel, config
 * panel, buttons, sliders, tooltip): their radius is already correct and must not change (R2). Use it
 * only for new pieces (the status-effect strip, and later the creative tab pills / boss-bar capsule).
 */
public final class GlassCorners {

    private GlassCorners() {}

    /** The hotbar glass strip's corner radius in GUI px (22 px tall * 1.15 pose scale / 4). */
    public static final float HOTBAR_RADIUS = 22.0f * HudLayout.SCALE / 4.0f;

    /**
     * The corner knob (vertex RED, {@code [0,1]}) that gives a {@code w x h} glass rect drawn at
     * identity pose the hotbar corner radius. Clamped to 1.0 (full capsule) for rects too small to
     * reach it, matching 26.2's {@code knobByte} clamp.
     */
    public static float hotbarCorner(float w, float h) {
        float m = Math.min(w, h);
        if (m <= 0.0f) return 1.0f;
        return Math.min(1.0f, 4.0f * HOTBAR_RADIUS / m);
    }

    /** Alias of {@link #hotbarCorner} — the corner knob a {@code w x h} rect needs for the hotbar radius.
     *  Named to match the BATCH-B HUD helpers ({@code HudGlass.glassBoxHotbar}) 1:1 across versions. */
    public static float knob(float w, float h) {
        return hotbarCorner(w, h);
    }

    /**
     * The ABSOLUTE hotbar corner radius in GUI px a {@code w x h} rect ends up with — the hotbar radius,
     * clamped to a full capsule ({@code min(w,h)/4}) when the rect is too small to reach it. Used by the
     * flat-fallback {@code roundRect} paths so a fallback matches the glass corner exactly.
     */
    public static float radiusPx(float w, float h) {
        float m = Math.min(w, h);
        if (m <= 0.0f) return HOTBAR_RADIUS;
        return Math.min(HOTBAR_RADIUS, m / 4.0f);
    }
}
