package dev.s1mp1e.glass.render;

import net.minecraft.client.gui.DrawContext;

/**
 * The status-effect list beside the survival / creative inventory as ONE continuous liquid-glass strip
 * (user choice "A 合成一整條"), like the hotbar stood on its side. The 1.20.1 port of 26.2's {@code render/GlassEffects}.
 *
 * <p>{@code EffectsInInventoryGlassMixin} calls {@link #strip} once, before any effect icon or text is drawn, then
 * suppresses vanilla's per-box background sprite. In 1.20.1 the vanilla box width is uniform (120 wide / 32 compact), so
 * the strip is a single plate of that width and the whole list height, with the {@link GlassCorners hotbar corner radius}
 * (R2) and a faint separator at the top of each entry after the first. Icons, text, positions, hit areas and the
 * compact-mode tooltip are untouched.
 *
 * <p>R4: the strip is a frame-primary surface (it must show the world beneath it), so the caller reuses the fresh backdrop
 * the container panel already grabbed this frame (or grabs one) rather than folding onto a stale snapshot.
 */
public final class GlassEffects {
    private GlassEffects() {}

    /** AA / edge-refraction bleed around the strip, GUI px (the small-card pad). */
    private static final float PAD = 8f;
    /** Faint separator between two entries (same tint as the creative tab separators). */
    private static final int SEP_ARGB = 0x24000000;
    /** Separators stop short of the strip's sides, like the hotbar slot grooves. */
    private static final int SEP_INSET = 6;

    /**
     * The whole effect list as ONE continuous vertical glass strip: a single plate from {@code (x0,y0)} to {@code (x1,y1)}
     * with the hotbar corner radius, plus a faint separator at the top of every entry after the first
     * ({@code y0 + i*spacing}). Must be called before any entry's icon/text so the strip stays underneath them.
     *
     * @return {@code false} when the glass pipeline is not usable (caller keeps the vanilla per-box sprites)
     */
    public static boolean strip(DrawContext ctx, int x0, int y0, int x1, int y1, int spacing, int count, float opacity) {
        if (x1 <= x0 || y1 <= y0 || count <= 0) return false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable() || !SceneCapture.hasBackdrop()) return false;
        float corner = GlassCorners.cornerScale(x1 - x0, y1 - y0);
        // Panel material: frost .5, no lift, the hotbar corner. (GlassRenderer.panel uses the container 0.19 corner; here
        // we want the hotbar corner per R2, so call glass() directly with the same frost/lift as a panel.)
        GlassRenderer.glass(x0, y0, x1, y1, PAD, corner, 0f, opacity, GlassRenderer.FROST_PANEL);
        int sepA = Math.round(((SEP_ARGB >>> 24) & 0xFF) * opacity);
        int sepArgb = (sepA << 24) | (SEP_ARGB & 0xFFFFFF);
        for (int i = 1; i < count; i++) {
            int sy = y0 + i * spacing;
            GlassEffects.sep(ctx, x0 + SEP_INSET, sy - 1, x1 - SEP_INSET, sy, sepArgb);
        }
        return true;
    }

    private static void sep(DrawContext ctx, int x0, int y0, int x1, int y1, int argb) {
        if (x1 <= x0 || y1 <= y0) return;
        if (GlassProgram.roundUsable()) GlassRenderer.roundRect(x0, y0, x1, y1, 0.5f, argb);
        else ctx.fill(x0, y0, x1, y1, argb);
    }
}
