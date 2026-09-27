package dev.s1mp1e.glass.render;

import net.minecraft.client.gui.DrawContext;

/**
 * Shared liquid-glass surfaces for the SCREENS that are NOT container screens (advancements, book, stats, social).
 * The container-panel logic lives in {@code HandledScreenGlassMixin}; this is the
 * same refracting panel without the slot machinery, so a non-container screen mixin can enqueue one panel with the
 * identical look — the 1.20.1 (DrawContext, immediate-mode) counterpart of 26.2's {@code render/GlassSurface}.
 *
 * <p>The look is kept identical to the container panel ({@link GlassRenderer#panel}): the refracting GLASS program with
 * corner 0.19 and pad 12. Every call gates on {@link GlassProgram#usable()}; {@link #plate} returns {@code false} when
 * the pipeline is unusable so the caller can fall back to the vanilla texture (a surface never vanishes).
 *
 * <p><b>R4 (fresh backdrop / no self-sample):</b> a non-container screen has no per-frame primary grab like the HUD/
 * container panel, so each of these screens is a frame-primary surface and MUST take a fresh {@link SceneCapture#grabNow()}
 * the instant before its plate (its glass mixin does this) so it never folds onto a stale, wrong-stage snapshot → flicker.
 */
public final class GlassSurface {
    private GlassSurface() {}

    /** Container-panel corner scale (0.19 in this line) — keep new non-container panels visually identical to containers. */
    public static final float PANEL_CORNER = 0.19f;

    /**
     * Enqueue a refracting glass panel spanning [{@code x0},{@code y0}] .. [{@code x1},{@code y1}] (absolute scaled-GUI px)
     * at the given opacity 0..1. Returns {@code false} when the pipeline is unusable (caller draws the vanilla texture).
     */
    public static boolean plate(float x0, float y0, float x1, float y1, float opacity) {
        if (x1 <= x0 || y1 <= y0) return false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable() || !SceneCapture.hasBackdrop()) return false;
        GlassRenderer.panel(x0, y0, x1, y1, opacity);
        return true;
    }

    /** {@link #plate} or, when the pipeline is down, a flat rounded fallback fill so the surface never disappears. */
    public static void plateOrPaint(DrawContext ctx, float x0, float y0, float x1, float y1,
                                    float opacity, int fallbackArgb) {
        if (plate(x0, y0, x1, y1, opacity)) return;
        int a = Math.round(((fallbackArgb >>> 24) & 0xFF) * opacity);
        int argb = (a << 24) | (fallbackArgb & 0xFFFFFF);
        if (GlassProgram.roundUsable()) GlassRenderer.roundRect(x0, y0, x1, y1, 4f, argb);
        else ctx.fill(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), argb);
    }

    /**
     * A flat anti-aliased rounded rect — a readability scrim under content, or a light knob that must not itself refract.
     * Never samples the backdrop, so it never flickers.
     */
    public static void scrim(DrawContext ctx, float x0, float y0, float x1, float y1, float radius, int argb) {
        if (x1 <= x0 || y1 <= y0) return;
        if (GlassProgram.roundUsable()) GlassRenderer.roundRect(x0, y0, x1, y1, radius, argb);
        else ctx.fill(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), argb);
    }
}
