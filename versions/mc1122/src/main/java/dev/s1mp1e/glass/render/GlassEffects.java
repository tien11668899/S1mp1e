package dev.s1mp1e.glass.render;

/**
 * Feature (F) — the status-effect list as ONE continuous glass strip.
 *
 * <p>The 1.12.2 potion boxes beside the survival / creative inventory are drawn by
 * {@code InventoryEffectRenderer.drawActivePotionEffects} as one 140&times;32
 * {@code drawTexturedModalRect} per effect, stacked every {@code spacing} px. This
 * replaces the whole stack with a single frosted glass plate (like the hotbar
 * stood on its side) plus faint separators between entries; the vanilla icon /
 * name / time positions and text are untouched and drawn on top by the caller.
 *
 * <p>Corner radius is the HUD hotbar radius via {@link GlassCorners} (rule R2 —
 * this is a NEW glass piece). The plate is a frame-primary surface, so the caller
 * must have a FRESH backdrop grabbed this frame before calling in (rule R4); this
 * class only draws.
 *
 * <p>Unlike the modern reference there is no compact/icon-only column in 1.12.2
 * (vanilla always shifts {@code guiLeft} right to make room for the full 140-wide
 * boxes), and 1.12.2's inventory effect list has NO expiry blink (the blink lives
 * only in the HUD overlay, not this drawer), so neither is reproduced here.
 */
public final class GlassEffects {

    private GlassEffects() {}

    /** 26.2's separator colour + inset. */
    private static final int   SEP_ARGB  = 0x24000000;
    private static final float SEP_INSET = 6f;
    /** 26.2's small-card shadow pad for the strip. */
    private static final float PAD       = 8f;

    /**
     * Draw one continuous glass strip covering {@code count} entries of height 32
     * spaced {@code spacing} px apart, starting at {@code (x0, y0)} and
     * {@code w = x1 - x0} wide. Separators are laid at each entry top after the
     * first. {@code alpha} is the whole-strip opacity (screen-open fade).
     */
    public static void strip(float x0, float y0, float x1, float y1,
                             float spacing, int count, float alpha) {
        if (alpha <= 0.004f) return;
        float w = x1 - x0, h = y1 - y0;
        if (w <= 0f || h <= 0f) return;

        // One frosted plate with the hotbar corner radius. Same knob contract as
        // the container panel (frost 0.5, no lift), only the corner comes from
        // GlassCorners so a new piece obeys R2 without changing existing surfaces.
        float corner = GlassCorners.hotbarCorner(w, h);
        GlassRenderer.glass(x0, y0, x1, y1, PAD, corner, 0f, alpha, GlassRenderer.FROST_PANEL);

        // Faint separators between entries (skip the first top edge). Drawn with
        // the AA rounded-rect SDF (no backdrop -> never flickers), alpha scaled by
        // the strip fade so they appear together with the plate.
        if (count > 1) {
            int baseA = (SEP_ARGB >>> 24) & 0xFF;
            int sepA = Math.round(baseA * (alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha)));
            int sepArgb = (sepA << 24) | (SEP_ARGB & 0xFFFFFF);
            float sx0 = x0 + SEP_INSET, sx1 = x1 - SEP_INSET;
            if (sx1 - sx0 > 1f) {
                for (int i = 1; i < count; i++) {
                    float sy = y0 + i * spacing;
                    GlassRenderer.roundRect(sx0, sy, sx1, sy + 1f, 0.5f, sepArgb);
                }
            }
        }
    }
}
