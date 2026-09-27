package dev.s1mp1e.glass.render;

/**
 * Liquid glass for the status-effect boxes vanilla draws beside the survival / creative inventory —
 * the 1.16.5 Fabric port of LiquidGlass26's {@code GlassEffects} + {@code EffectsInInventoryGlassMixin}
 * ("A 合成一整條"). The whole effect list becomes ONE continuous vertical glass strip, like the hotbar
 * stood on its side: uniform width, faint separators between entries, and the HUD hotbar corner radius
 * (via {@link GlassCorners}, R2). Vanilla icon / name / time positions and hit areas are unchanged.
 *
 * <p>On 1.16.5 the effect panel is always WIDE (the compact single-icon column is a 1.17+ feature) and
 * is drawn to the LEFT of the inventory ({@code x = this.x - 124}) via
 * {@code AbstractInventoryScreen.drawStatusEffectBackgrounds}, which blits one {@code 140 x 32} box per
 * entry stepping down by {@code spacing}. {@code EffectsInInventoryGlassMixin} enqueues this single
 * strip at that method's HEAD (before any box, icon or text) and skips the per-box blit, so the list
 * reads as one sheet. The strip is a FRAME-PRIMARY surface: its caller takes a fresh
 * {@link SceneCapture#grabNow()} so it never folds onto a stale backdrop / flickers at high fps (R4).
 *
 * <p>Every call gates on the glass pipeline; a caller that gets {@code false} draws the vanilla boxes so
 * the panel never vanishes.
 */
public final class GlassEffects {

    private GlassEffects() {}

    /** Vanilla's per-entry effect box width/height on 1.16.5 (the {@code drawStatusEffectBackgrounds} blit). */
    public static final int BOX_W = 140;
    public static final int BOX_H = 32;

    /** AA / edge-refraction bleed around the strip, in GUI px — the small-card pad (as {@code GlassTooltip}). */
    private static final float PAD = 8f;

    /** Faint hotbar-style separator between two entries (same tint as 26.2's creative tab separators). */
    private static final int SEP_ARGB = 0x24000000;
    /** Separators stop short of the strip's sides, like the hotbar slot grooves. */
    private static final int SEP_INSET = 6;

    /**
     * The whole effect list as ONE continuous vertical glass strip: a single refracting plate from
     * {@code (x0,y0)} to {@code (x1,y1)} in the inventory-panel material with the hotbar corner radius,
     * plus a faint separator at the top of every entry after the first ({@code y0 + i*spacing}).
     * Must be called before any entry's icon / text is drawn so the strip stays underneath them, and
     * after a fresh {@link SceneCapture#grabNow()} (the caller does that).
     *
     * @return {@code false} when the glass pipeline is not usable (caller keeps the vanilla per-box sprites).
     */
    public static boolean strip(int x0, int y0, int x1, int y1, int spacing, int count, float fade) {
        if (x1 <= x0 || y1 <= y0 || count <= 0) return false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return false;

        // One frosted plate, hotbar corner radius (R2 — new piece), panel material (frost .5, no lift).
        float corner = GlassCorners.hotbarCorner(x1 - x0, y1 - y0);
        GlassRenderer.glass(x0, y0, x1, y1, PAD, corner, 0f, fade, GlassRenderer.FROST_PANEL);

        // Faint AA separators (solid rounded rect via ROUND — no backdrop, never flickers), alpha scaled
        // by the panel fade so they appear/disappear with the strip.
        int baseA = (SEP_ARGB >>> 24) & 0xFF;
        int sepA = Math.round(baseA * fade) & 0xFF;
        if (sepA > 0) {
            int sepArgb = (sepA << 24) | (SEP_ARGB & 0xFFFFFF);
            for (int i = 1; i < count; i++) {
                int sy = y0 + i * spacing;
                GlassRenderer.roundRect(x0 + SEP_INSET, sy - 1, x1 - SEP_INSET, sy, 0.5f, sepArgb);
            }
        }
        return true;
    }
}
