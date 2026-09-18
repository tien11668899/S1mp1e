package dev.s1mp1e.client.gui;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;

/**
 * Core-profile painter for the 1.21.1 config GUI.
 *
 * <p>The container {@link #panel} is REAL liquid glass — the backdrop-sampling GLASS
 * program (refraction + gaussian frost + a faint grounding edge shadow, the user's
 * rule 4) with a dark readability scrim laid over it (rule 2), so text stays legible.
 * It no longer flickers because the screen re-captures its backdrop deterministically
 * every frame (see {@code S1mp1eConfigScreen}: renderBackground → flush → grabNow →
 * panel), instead of reusing a time-deduped grab from a different render stage.
 *
 * <p>Interior chips/highlights use the BTN program (frosted white capsule) and small
 * solid fills use the ROUND program (true SDF anti-aliased corners) — NEITHER samples
 * the backdrop, so they never flicker. Sharp lines and text go through the DrawContext,
 * which flushes on top of the batch — so labels always sit above the glass.
 */
public final class GlassWidgets {

    private GlassWidgets() {}

    // ---- rounded fills (batch, true AA corners) ----

    /** Refractive liquid-glass container panel with a dark readability scrim on top. */
    public static void panel(DrawContext ctx, float x0, float y0, float x1, float y1, float alpha) {
        if (GlassProgram.usable() && SceneCapture.hasBackdrop()) {
            // Real glass: refraction + frost + faint edge shadow over the captured
            // (blurred, dimmed) backdrop. corner 0.19 ≈ 14px on this panel size.
            GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL,
                                0.19f, 0f, alpha, GlassRenderer.FROST_PANEL);
            // Very light dark scrim — the user wants the panel MORE see-through (like the
            // hotbar glass, which has no scrim), so keep this to a whisper: just enough to
            // seat the text, letting the refracted backdrop read through. Radius tracks the
            // GLASS body corner (0.0475 of the min side) so no un-scrimmed crescent shows.
            if (GlassProgram.roundUsable())
                GlassRenderer.roundRect(x0, y0, x1, y1, Math.min(x1 - x0, y1 - y0) * 0.0475f,
                                        (clampByte(alpha * 0.16f) << 24) | 0x1C1C1E);
            return;
        }
        // No glass program / no backdrop: opaque frosted dark fill fallback.
        int argb = (clampByte(alpha * 0.58f) << 24) | 0x1C1C1E;
        if (GlassProgram.roundUsable()) GlassRenderer.roundRect(x0, y0, x1, y1, 14f, argb);
        else fillCheap(ctx, x0, y0, x1, y1, argb, 14f);
    }

    /**
     * iOS-26 scroll-edge effect for a scroll list: a progressive blur + dark fade at
     * the top and bottom, each fading IN with how far that end can still scroll
     * ({@code topK}/{@code botK} in 0..1). Call AFTER the list content is flushed into
     * the framebuffer and a fresh composite backdrop has been grabbed. No-op if the
     * EDGE program or a backdrop is unavailable.
     */
    public static void scrollEdges(float x0, float yTop, float x1, float yBot,
                                   float ext, float topK, float botK, float alpha) {
        if (!GlassProgram.edgeUsable() || !SceneCapture.hasBackdrop()) return;
        // Apple iOS-26 scroll edge = a VARIABLE BLUR + a soft gradient: the content stays
        // visible but progressively blurs toward the edge, with only a whisper of dimming
        // (NOT a dark band, NOT a fade-to-black). So blur dominates and DIM is tiny.
        final float RADIUS = 18f, DIM = 0.04f;
        if (topK > 0.001f)
            GlassRenderer.edgeBand(x0, yTop, x1, yTop + ext, true,  RADIUS, DIM, alpha * clamp01(topK));
        if (botK > 0.001f)
            GlassRenderer.edgeBand(x0, yBot - ext, x1, yBot, false, RADIUS, DIM, alpha * clamp01(botK));
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** Frosted white highlight / chip capsule (BTN program — AA, no backdrop). */
    public static void capsule(DrawContext ctx, float x0, float y0, float x1, float y1,
                               float corner, float lift, float alpha, boolean enabled) {
        if (GlassProgram.btnUsable()) {
            GlassRenderer.button(x0, y0, x1, y1, corner, lift, alpha, enabled);
        } else {
            int a = clampByte(alpha * (0.12f + 0.18f * lift));
            fillCheap(ctx, x0, y0, x1, y1, (a << 24) | 0xFFFFFF, (y1 - y0) / 2f * corner);
        }
    }

    /** Coloured rounded fill with true AA corners. */
    public static void fillRound(DrawContext ctx, float x0, float y0, float x1, float y1, int argb, float r) {
        if (GlassProgram.roundUsable()) GlassRenderer.roundRect(x0, y0, x1, y1, r, argb);
        else fillCheap(ctx, x0, y0, x1, y1, argb, r);
    }
    /** Alias kept for the shared widget code. */
    public static void fillRoundSmooth(DrawContext ctx, float x0, float y0, float x1, float y1, int argb, float r) {
        fillRound(ctx, x0, y0, x1, y1, argb, r);
    }

    /**
     * The iOS-26 Liquid Glass KNOB (switch knob / slider thumb): a solid white pill at rest that, as {@code morph}
     * goes 0 → 1, grows into a clear refracting lens and back — the same composition as the 26.2 client, drawn
     * immediately in call order, back to front:
     * <ol>
     *   <li>a dark base — the dark card refracted into the lens (shows as dark bands above/below the track);</li>
     *   <li>the track seen through the lens: a slightly magnified band in the track colour(s), clipped to the lens
     *       and to the track ends; with a split, the left (filled) part gets its own rounded end at {@code splitX};</li>
     *   <li>a clear glass surface (BTN program: rim + soft drop shadow, faint body);</li>
     *   <li>the solid white knob on top at opacity {@code 1 - morph}.</li>
     * </ol>
     * The shape scales about its centre by {@code 1 + (lensScale - 1) * morph}.
     */
    public static void knobLens(DrawContext ctx, float cx, float cy, float hw, float hh, float morph, float lensScale,
                                float trackX0, float trackX1, float trackHalfH, float splitX,
                                int colLeft, int colRight, float alpha) {
        knobLens(ctx, cx, cy, hw, hh, morph, lensScale, lensScale, 1f, trackX0, trackX1, trackHalfH, splitX, colLeft, colRight, alpha);
    }

    /**
     * {@link #knobLens} with separate width / height multipliers and a corner radius at full morph (the slider lens
     * is a wide rounded rectangle that stretches with drag speed, see {@link Motion#lensShape}).
     *
     * @param cornerFrac lens corner radius at full morph as a fraction of its half-height (1 = capsule); the rest
     *                   knob is always a capsule
     */
    public static void knobLens(DrawContext ctx, float cx, float cy, float hw, float hh, float morph,
                                float scaleW, float scaleH, float cornerFrac,
                                float trackX0, float trackX1, float trackHalfH, float splitX,
                                int colLeft, int colRight, float alpha) {
        float m = clamp01(morph);
        float lw = hw * (1f + (scaleW - 1f) * m), lh = hh * (1f + (scaleH - 1f) * m);
        float lx0 = cx - lw, lx1 = cx + lw, ly0 = cy - lh, ly1 = cy + lh;
        float r = lh * (1f + (cornerFrac - 1f) * m);                   // capsule at rest → rounded rect when lifted
        float cornerKnob = clamp01(r / Math.max(0.001f, Math.min(lw, lh)));   // BTN: radius = min(half) × corner

        float glassA = alpha * m;
        if (glassA > 0.004f) {
            // REAL refracting lens: the world behind bent through the pill (LENS program — clear, not dark). It
            // has a full-capsule corner (unlike glass()), lift 0 so the Fresnel rim stays subtle, frost 0.65 for a
            // whisper of softening. Backdrop is grabbed before the GUI, so it refracts the world; the track is then
            // painted on top (below) as the magnified band.
            boolean drewLens = false;
            if (GlassProgram.lensUsable() && SceneCapture.hasBackdrop()) {
                GlassRenderer.lens(lx0, ly0, lx1, ly1, cornerKnob, 0f, glassA, 0.65f);
                drewLens = true;
            } else {
                // no glass program / no backdrop: a faint frosted-white body, NEVER dark
                fillRound(ctx, lx0, ly0, lx1, ly1, (clampByte(glassA * 0.34f) << 24) | 0xF2F3F5, r);
            }
            // the band runs edge to edge so only the top and bottom show refraction bands; a capsule band of
            // half-height bandH < lh spanning [lx0, lx1] always lies inside the lens capsule
            float bandH = Math.min(trackHalfH * 1.15f, lh * 0.76f);
            float bx0 = Math.max(trackX0, lx0), bx1 = Math.min(trackX1, lx1);
            if (bx1 - bx0 > 0.5f) {
                fillRound(ctx, bx0, cy - bandH, bx1, cy + bandH, scaleAlpha(colRight, glassA), bandH);
                if (!Float.isNaN(splitX) && splitX > bx0) {
                    float fx1 = Math.min(bx1, Math.max(bx0 + 2f * bandH, splitX));
                    fillRound(ctx, bx0, cy - bandH, fx1, cy + bandH, scaleAlpha(colLeft, glassA), bandH);
                }
            }
            // outline: the lens shader already carries a faint Fresnel rim + soft shadow, so with a real lens we add
            // NOTHING extra (the old glass_btn capsule at 0.65 read as an over-strong outline). Only the fallback
            // draws a light rim so the frosted-white body still has an edge.
            if (!drewLens) capsule(ctx, lx0, ly0, lx1, ly1, cornerKnob, 0.10f, glassA * 0.40f, true);
        }

        float whiteA = alpha * (1f - m);
        if (whiteA > 0.004f) fillRound(ctx, lx0, ly0, lx1, ly1, (clampByte(whiteA) << 24) | 0xFFFFFF, r);
    }

    /** {@code argb} with its alpha byte multiplied by {@code k} (0..1). */
    public static int scaleAlpha(int argb, float k) {
        int a = Math.round(((argb >>> 24) & 0xFF) * clamp01(k));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    // ---- sharp DrawContext primitives (draw on top of the batch) ----

    public static void fill(DrawContext ctx, float x0, float y0, float x1, float y1, int argb) {
        ctx.fill(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), argb);
    }
    public static void gradientV(DrawContext ctx, float x0, float y0, float x1, float y1, int top, int bottom) {
        ctx.fillGradient(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), top, bottom);
    }
    public static void border(DrawContext ctx, float x0, float y0, float x1, float y1, int argb) {
        fill(ctx, x0, y0, x1, y0 + 1, argb);
        fill(ctx, x0, y1 - 1, x1, y1, argb);
        fill(ctx, x0, y0, x0 + 1, y1, argb);
        fill(ctx, x1 - 1, y0, x1, y1, argb);
    }

    public static void label(DrawContext ctx, String s, float x, float y, int rgb, float alpha) {
        GlassFont.draw(ctx, s, x, y, rgb, alpha, true);
    }
    public static void labelNoShadow(DrawContext ctx, String s, float x, float y, int rgb, float alpha) {
        GlassFont.draw(ctx, s, x, y, rgb, alpha, false);
    }

    public static int strW(String s) { return Math.round(GlassFont.width(s)); }
    public static int fontH() { return Math.round(GlassFont.height()); }

    public static boolean inside(int mx, int my, float x0, float y0, float x1, float y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    // ---- cheap DrawContext stadium fallback (only when the glass program is off) ----
    private static void fillCheap(DrawContext ctx, float x0, float y0, float x1, float y1, int argb, float r) {
        float rr = Math.min(r, Math.min((x1 - x0) / 2f, (y1 - y0) / 2f));
        fill(ctx, x0 + rr, y0, x1 - rr, y1, argb);
        fill(ctx, x0, y0 + rr, x0 + rr, y1 - rr, argb);
        fill(ctx, x1 - rr, y0 + rr, x1, y1 - rr, argb);
    }

    private static int clampByte(float a) {
        int v = Math.round(a * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
