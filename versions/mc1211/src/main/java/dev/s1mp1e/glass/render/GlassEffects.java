package dev.s1mp1e.glass.render;

import net.minecraft.client.gui.DrawContext;

/**
 * Liquid glass for the status-effect boxes vanilla draws beside the survival / creative inventory
 * ({@code AbstractInventoryScreen.drawStatusEffects} — the panel to the right of the inventory that lists each active
 * potion effect). 1.21.1 (DrawContext / core-profile) port of 26.2's {@code GlassEffects}.
 *
 * <p>Vanilla paints each effect box with the {@code effect_background_large/small} dark rounded sprite in
 * {@code drawStatusEffectBackgrounds}. {@code EffectsInInventoryGlassMixin} replaces the whole per-box loop with ONE
 * continuous vertical glass strip (user choice "A 合成一整條" — like the hotbar stood on its side). Everything else
 * vanilla does is left untouched: the effect ICONS ({@code drawStatusEffectSprites}) and the name / remaining-time TEXT
 * ({@code drawStatusEffectDescriptions}) are separate methods drawn AFTER the strip, so they stay on top and readable;
 * the COMPACT-layout hover tooltip goes through the normal tooltip path, which {@code ScreenTooltipMixin} already
 * promotes to the very top layer (rule R1).
 *
 * <p>The strip is a single refracting glass plate with the hotbar corner radius ({@link GlassCorners}, rule R2), plus a
 * faint separator at the top of every entry after the first. Every call gates on {@link GlassProgram#usable()}; a caller
 * that gets {@code false} keeps the vanilla sprites so a box never vanishes. It is a frame-primary surface, so the caller
 * grabs a FRESH backdrop ({@link SceneCapture#grabNow()}) before it (rule R4).
 */
public final class GlassEffects {

    private GlassEffects() {}

    /** AA / edge-refraction bleed around the strip, GUI px — the small-card pad the tooltip uses. */
    private static final float PAD = 8f;
    /** Faint separator between two entries (same tint as the creative tab / hotbar slot grooves). */
    private static final int SEP_ARGB = 0x24000000;
    /** Separators stop short of the strip's sides, like the hotbar slot grooves. */
    private static final int SEP_INSET = 6;

    /**
     * The whole effect list as ONE continuous vertical glass strip: a single plate from {@code (x0,y0)} to
     * {@code (x1,y1)} with the hotbar corner radius, plus a faint separator at the top of every entry after the first
     * ({@code y0 + i*spacing}). Must be drawn before any entry icon/text so it stays underneath them.
     *
     * @return {@code false} when the glass pipeline is not usable (caller keeps the vanilla per-box sprites)
     */
    public static boolean strip(DrawContext ctx, int x0, int y0, int x1, int y1, int spacing, int count, float fade) {
        if (x1 <= x0 || y1 <= y0 || count <= 0) return false;
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return false;

        float w = x1 - x0, h = y1 - y0;
        float cornerFrac = GlassCorners.hotbarCornerFrac(w, h);
        // ONE refracting plate, panel material (frost .5, no lift), hotbar corner radius (R2).
        GlassRenderer.glass(x0, y0, x1, y1, PAD, cornerFrac, 0f, fade, GlassRenderer.FROST_PANEL);

        // Separators: 1px tall, radius 0.5, inset from the sides, alpha scaled by the panel fade.
        int sepA = Math.round(((SEP_ARGB >>> 24) & 0xFF) * (fade < 0f ? 0f : (fade > 1f ? 1f : fade)));
        int argb = (sepA << 24) | (SEP_ARGB & 0xFFFFFF);
        if (sepA > 0) {
            for (int i = 1; i < count; i++) {
                float sy = y0 + i * spacing;
                GlassRenderer.roundRect(x0 + SEP_INSET, sy - 1f, x1 - SEP_INSET, sy, 0.5f, argb);
            }
        }
        return true;
    }
}
