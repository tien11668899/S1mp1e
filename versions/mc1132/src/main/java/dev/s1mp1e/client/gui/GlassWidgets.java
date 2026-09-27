package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.glass.compat.Mc1132;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Stateless painter + hit-test toolkit shared by the config screen and the HUD editor.
 * Draws in the mod's liquid-glass style via {@link GlassRenderer}, with a solid fallback
 * whenever the glass shader is unavailable (GL2.0-less GPU or a missing scene backdrop) so
 * the UI is never invisible.
 *
 * <p>This is mc189's immediate-mode toolkit adapted to the 1.13.2 API: the render-primitive
 * plumbing (Tessellator/BufferBuilder, {@code GlStateManager}) is the 1.13.2 form, but every
 * call into {@link GlassRenderer} is byte-for-byte the same as mc189's because the render
 * layer already carries the identical API. The one behavioural change from mc189 is
 * {@link #panel}, which follows the mc1211 recipe (real refractive glass + a light
 * readability scrim whose corner radius is {@code min(w,h) * 0.0475}).
 *
 * <p>1.13.2 deltas vs the mc1144 template: {@code GlStateManager.clearCurrentColor()} is
 * the no-arg {@code clearColor()} here, and {@link #beginScissor} goes through the
 * {@link Mc1132} bridge ({@code scaleFactor()}/{@code fbH()}) because there is no mapped
 * {@code mc.window} on Legacy-yarn 1.13.2.
 */
public final class GlassWidgets {

    private GlassWidgets() {}

    /** True when a frosted glass panel can actually render this frame. */
    public static boolean glassReady() {
        return GlassProgram.usable() && SceneCapture.hasBackdrop();
    }

    /**
     * Refractive liquid-glass container panel with a light dark readability scrim on top
     * (mc1211 recipe): the backdrop-sampling GLASS program (refraction + frost + a faint
     * grounding edge shadow), then a whisper-thin scrim so text still seats. Falls back to a
     * solid frosted-dark rounded fill when the glass pipeline can't draw.
     */
    public static void panel(float x0, float y0, float x1, float y1, float alpha) {
        if (GlassProgram.usable() && SceneCapture.hasBackdrop()) {
            GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL,
                                0.19f, 0f, alpha, GlassRenderer.FROST_PANEL);
            // Very light dark scrim. Radius tracks the GLASS body corner (0.0475 of the min
            // side) so no un-scrimmed crescent shows at the rounded corners.
            if (GlassProgram.roundUsable())
                GlassRenderer.roundRect(x0, y0, x1, y1, Math.min(x1 - x0, y1 - y0) * 0.0475f,
                                        (clampByte(alpha * 0.16f) << 24) | 0x1C1C1E);
            return;
        }
        // No glass program / no backdrop: opaque frosted dark fill fallback.
        int argb = (clampByte(alpha * 0.58f) << 24) | 0x1C1C1E;
        if (GlassProgram.roundUsable()) GlassRenderer.roundRect(x0, y0, x1, y1, 14f, argb);
        else fillRoundSmooth(x0, y0, x1, y1, argb, 14f);
        resetColorCache();
    }

    /** Glass capsule (button/chip/toggle track). corner 1 = full capsule. */
    public static void capsule(float x0, float y0, float x1, float y1,
                               float corner, float lift, float alpha, boolean enabled) {
        if (GlassProgram.btnUsable()) {
            GlassRenderer.button(x0, y0, x1, y1, corner, lift, alpha, enabled);
        } else {
            int a = clampByte(alpha * (0.10f + 0.14f * lift + 0.12f));
            drawRect(x0, y0, x1, y1, (a << 24) | 0xFFFFFF);
            resetColorCache();
        }
    }

    /**
     * iOS-26 scroll-edge effect for a scroll list: a progressive blur + dark fade at
     * the top and bottom, each fading IN with how far that end can still scroll
     * ({@code topK}/{@code botK} in 0..1). Call AFTER the list content is flushed into
     * the framebuffer and a fresh composite backdrop has been grabbed
     * ({@code SceneCapture.forceGrab()}). Uses the dedicated EDGE program (rounded outer
     * corners); falls back to the shipped {@link #edgeFade} (menu_blur EdgeMode=1) when
     * the EDGE program is unavailable.
     */
    public static void scrollEdges(float x0, float yTop, float x1, float yBot,
                                   float ext, float topK, float botK, float alpha) {
        // Apple iOS-26 scroll edge = a VARIABLE BLUR + a soft gradient: the content stays
        // visible but progressively blurs toward the edge, with only a whisper of dimming
        // (NOT a dark band, NOT a fade-to-black). So blur dominates and DIM is tiny.
        final float RADIUS = 18f, DIM = 0.04f;
        if (GlassProgram.edgeUsable() && SceneCapture.hasBackdrop()) {
            if (topK > 0.001f)
                GlassRenderer.edgeBand(x0, yTop, x1, yTop + ext, true,  RADIUS, DIM, alpha * clamp01(topK));
            if (botK > 0.001f)
                GlassRenderer.edgeBand(x0, yBot - ext, x1, yBot, false, RADIUS, DIM, alpha * clamp01(botK));
            return;
        }
        // Fallback: the menu_blur EdgeMode=1 path — same [1 6 15 20 15 6 1]/64 kernel,
        // feathered corners via smoothstep instead of the SDF; no-op without a backdrop.
        if (topK > 0.001f) edgeFade(x0, yTop, x1, yTop + ext, true,  RADIUS, DIM, alpha * clamp01(topK));
        if (botK > 0.001f) edgeFade(x0, yBot - ext, x1, yBot, false, RADIUS, DIM, alpha * clamp01(botK));
    }

    /**
     * The iOS-26 Liquid Glass KNOB (switch knob / slider thumb): a solid white pill at rest
     * that, as {@code morph} goes 0 -> 1, grows into a clear refracting lens and back — the
     * same composition as the 26.2 client, drawn immediately in call order, back to front.
     */
    public static void knobLens(float cx, float cy, float hw, float hh, float morph, float lensScale,
                                float trackX0, float trackX1, float trackHalfH, float splitX,
                                int colLeft, int colRight, float alpha) {
        knobLens(cx, cy, hw, hh, morph, lensScale, lensScale, 1f, trackX0, trackX1, trackHalfH, splitX, colLeft, colRight, alpha);
    }

    /**
     * {@link #knobLens} with separate width / height multipliers and a corner radius at full
     * morph (the slider lens is a wide rounded rectangle that stretches with drag speed,
     * see {@code Motion.lensShape}).
     *
     * @param cornerFrac lens corner radius at full morph as a fraction of its half-height
     *                   (1 = capsule); the rest knob is always a capsule
     */
    public static void knobLens(float cx, float cy, float hw, float hh, float morph,
                                float scaleW, float scaleH, float cornerFrac,
                                float trackX0, float trackX1, float trackHalfH, float splitX,
                                int colLeft, int colRight, float alpha) {
        float m = clamp01(morph);
        float lw = hw * (1f + (scaleW - 1f) * m), lh = hh * (1f + (scaleH - 1f) * m);
        float lx0 = cx - lw, lx1 = cx + lw, ly0 = cy - lh, ly1 = cy + lh;
        float r = lh * (1f + (cornerFrac - 1f) * m);                   // capsule at rest -> rounded rect when lifted
        float cornerKnob = clamp01(r / Math.max(0.001f, Math.min(lw, lh)));   // BTN: radius = min(half) x corner

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
                fillRound(lx0, ly0, lx1, ly1, (clampByte(glassA * 0.34f) << 24) | 0xF2F3F5, r);
            }
            // the band runs edge to edge so only the top and bottom show refraction bands; a capsule band of
            // half-height bandH < lh spanning [lx0, lx1] always lies inside the lens capsule
            float bandH = Math.min(trackHalfH * 0.95f, lh * 0.76f);   // clip to the track: overhang shows glass, not track colour
            float bx0 = Math.max(trackX0, lx0), bx1 = Math.min(trackX1, lx1);
            if (bx1 - bx0 > 0.5f) {
                fillRound(bx0, cy - bandH, bx1, cy + bandH, scaleAlpha(colRight, glassA), bandH);
                if (!Float.isNaN(splitX) && splitX > bx0) {
                    float fx1 = Math.min(bx1, Math.max(bx0 + 2f * bandH, splitX));
                    fillRound(bx0, cy - bandH, fx1, cy + bandH, scaleAlpha(colLeft, glassA), bandH);
                }
            }
            // outline: the lens shader already carries a faint Fresnel rim + soft shadow, so with a real lens we add
            // NOTHING extra. Only the fallback draws a light rim so the frosted-white body still has an edge.
            if (!drewLens) capsule(lx0, ly0, lx1, ly1, cornerKnob, 0.10f, glassA * 0.40f, true);
        }

        float whiteA = alpha * (1f - m);
        if (whiteA > 0.004f) fillRound(lx0, ly0, lx1, ly1, (clampByte(whiteA) << 24) | 0xFFFFFF, r);
    }

    /** {@code argb} with its alpha byte multiplied by {@code k} (0..1). */
    public static int scaleAlpha(int argb, float k) {
        int a = Math.round(((argb >>> 24) & 0xFF) * clamp01(k));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Linear-interpolate two ARGB colours ({@code t} in [0,1]); interpolates alpha too. */
    public static int lerpArgb(int c0, int c1, float t) {
        if (t <= 0f) return c0;
        if (t >= 1f) return c1;
        int a = lerpB((c0 >>> 24) & 255, (c1 >>> 24) & 255, t);
        int r = lerpB((c0 >> 16) & 255, (c1 >> 16) & 255, t);
        int g = lerpB((c0 >> 8) & 255, (c1 >> 8) & 255, t);
        int b = lerpB(c0 & 255, c1 & 255, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpB(int a, int b, float t) { return a + Math.round((b - a) * t); }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // ---- primitive rects ----

    /** Solid ARGB rect. Immediate-mode front-facing quad (never {@code DrawableHelper.fill},
     *  which ENDS with {@code disableBlend()} — we must leave blend enabled for the text and
     *  glass drawn right after). */
    public static void drawRect(float x0, float y0, float x1, float y1, int argb) {
        gradient(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), argb, argb, argb, argb);
    }

    /** Coloured rounded fill with true AA corners. Prefers the ROUND SDF program (matches
     *  mc1211/mc262); falls back to the smooth triangle-fan fill when the shader is
     *  unavailable (GL2.0-less GPU or a failed link). Colours can't use the white-only glass. */
    public static void fillRound(float x0, float y0, float x1, float y1, int argb, float r) {
        if (GlassProgram.roundUsable()) {
            GlassRenderer.roundRect(x0, y0, x1, y1, r, argb);
            return;
        }
        fillRoundSmooth(x0, y0, x1, y1, argb, r);
    }

    /** Smooth (non-jagged) rounded-rect fill via a triangle fan with real corner arcs.
     *  Colours can't use the white-only glass shader, so this gives clean rounded
     *  toggles/sliders/swatches without the stair-stepping of {@link #fillRound}. */
    public static void fillRoundSmooth(float x0, float y0, float x1, float y1, int argb, float r) {
        r = Math.min(r, Math.min((x1 - x0) / 2f, (y1 - y0) / 2f));
        if (r < 0.75f) { drawRect(x0, y0, x1, y1, argb); resetColorCache(); return; }
        float a = (argb >>> 24) / 255f, cr = (argb >> 16 & 255) / 255f,
              cg = (argb >> 8 & 255) / 255f, cb = (argb & 255) / 255f;
        GlStateManager.disableTexture();
        GlStateManager.enableBlend();
        GlStateManager.disableAlphaTest();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GL11.glColor4f(cr, cg, cb, a);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f((x0 + x1) / 2f, (y0 + y1) / 2f);
        // Perimeter walked TL->BL->BR->TR — the SAME front-facing winding as gradient()/
        // GlassRenderer.batchQuad()/drawRect. The GUI pass runs with GL_CULL_FACE on,
        // so a reversed (back-facing) fan would be culled to nothing.
        arc(x0 + r, y0 + r, r, 270f, 180f);   // top-left
        arc(x0 + r, y1 - r, r, 180f,  90f);   // bottom-left
        arc(x1 - r, y1 - r, r,  90f,   0f);   // bottom-right
        arc(x1 - r, y0 + r, r,   0f, -90f);   // top-right
        GL11.glVertex2f(x0 + r, y0);          // close back to the first perimeter point
        GL11.glEnd();
        // Leave GL_BLEND enabled (MC's expected baseline, as GlassRenderer.endBatch does) so
        // translucent FontRenderer text drawn right after still alpha-blends during panel fades.
        GlStateManager.enableAlphaTest();
        GlStateManager.enableTexture();
        resetColorCache();
    }

    /** Soft edge shadow beneath a rounded control — the "very faint edge shadow under the
     *  glass" Apple grounds controls with. A few expanding, low-alpha rounded fills offset
     *  slightly down; the control drawn on top hides the inner darkening so only a soft
     *  halo shows around/under it. {@code strength} scales the overall darkness (~0..1). */
    public static void dropShadow(float x0, float y0, float x1, float y1, float radius, float strength) {
        float dy = 1.2f;
        for (int i = 4; i >= 1; i--) {
            float e = i * 1.35f;                          // outward expansion per layer
            int a = clampByte(strength * 0.06f);
            if (a <= 0) continue;
            fillRoundSmooth(x0 - e, y0 - e + dy, x1 + e, y1 + e + dy, (a << 24), radius + e);
        }
    }

    private static void arc(float cx, float cy, float r, float aDeg, float bDeg) {
        int seg = 7;
        for (int i = 0; i <= seg; i++) {
            double t = Math.toRadians(aDeg + (bDeg - aDeg) * i / seg);
            GL11.glVertex2f(cx + (float) Math.cos(t) * r, cy + (float) Math.sin(t) * r);
        }
    }

    /** 1px inner border. */
    public static void border(float x0, float y0, float x1, float y1, int argb) {
        drawRect(x0, y0, x1, y0 + 1, argb);
        drawRect(x0, y1 - 1, x1, y1, argb);
        drawRect(x0, y0, x0 + 1, y1, argb);
        drawRect(x1 - 1, y0, x1, y1, argb);
    }

    /** Vertical gradient (top colour -> bottom colour), ARGB. */
    public static void gradientV(float x0, float y0, float x1, float y1, int top, int bottom) {
        gradient(x0, y0, x1, y1, top, top, bottom, bottom);
    }

    /** Horizontal gradient (left colour -> right colour), ARGB. */
    public static void gradientH(float x0, float y0, float x1, float y1, int left, int right) {
        gradient(x0, y0, x1, y1, left, right, right, left);
    }

    /** Four-corner gradient: colours for TL, TR, BR, BL. */
    public static void gradient(float x0, float y0, float x1, float y1,
                                int tl, int tr, int br, int bl) {
        GlStateManager.disableTexture();
        GlStateManager.enableBlend();
        GlStateManager.disableAlphaTest();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder bb = t.getBuffer();
        bb.begin(GL11.GL_QUADS, VertexFormats.POSITION_COLOR);
        vtx(bb, x0, y0, tl);
        vtx(bb, x0, y1, bl);
        vtx(bb, x1, y1, br);
        vtx(bb, x1, y0, tr);
        t.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        // Leave GL_BLEND enabled (MC's expected baseline) so translucent text/glass drawn
        // right after still blends — matching GlassRenderer.endBatch and fillRoundSmooth.
        GlStateManager.enableAlphaTest();
        GlStateManager.enableTexture();
        resetColorCache();
    }

    private static void vtx(BufferBuilder bb, float x, float y, int argb) {
        bb.vertex(x, y, 0.0D)
          .color(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24)
          .next();
    }

    // ---- text ----

    /** Draw text (PingFang via {@link GlassFont}) with shadow at panel alpha; skips when
     *  the effective alpha is negligible. */
    public static void label(String s, float x, float y, int rgb, float alpha) {
        if (clampByte(alpha) < 8) return;
        GlassFont.draw(s, x, y, rgb & 0xFFFFFF, alpha, true);
    }

    public static void labelNoShadow(String s, float x, float y, int rgb, float alpha) {
        if (clampByte(alpha) < 8) return;
        GlassFont.draw(s, x, y, rgb & 0xFFFFFF, alpha, false);
    }

    public static int strW(String s) { return Math.round(GlassFont.width(s)); }
    public static int fontH() { return Math.round(GlassFont.height()); }

    // ---- misc ----

    /** Re-sync GlStateManager's colour cache after a raw drawRect/gradient run, exactly as
     *  GlassRenderer.endBatch does (hard rule 5) — a RAW glColor4f puts the real GL colour
     *  back to white (the raw vertex colours bypassed the cache), then clearCurrentColor
     *  drops the cache's stale value so the next textured/glass draw isn't multiplied by it. */
    public static void resetColorCache() {
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GlStateManager.clearColor();
    }

    public static boolean inside(int mx, int my, float x0, float y0, float x1, float y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    private static int clampByte(float a) {
        int v = Math.round(a * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ---- scroll-edge fallback + scissor (GUI px -> physical px, bottom-left origin) ----

    /** iOS-26 "scroll edge effect": a progressive blur + adaptive dim that dissolves list
     *  content toward the edge. Re-grab the finished UI FIRST ({@code SceneCapture.forceGrab()}),
     *  then call this per list edge. {@code topEdge=true} -> strongest at y0. {@code strength}
     *  (0..1) fades the whole effect in as the list scrolls. No-op without the blur program. */
    public static void edgeFade(float x0, float y0, float x1, float y1, boolean topEdge,
                                float radius, float dim, float strength) {
        if (strength <= 0.01f || !GlassProgram.blurUsable() || !SceneCapture.hasBackdrop()) return;
        // Raw GL only inside the push/pop region — GlStateManager here would desync its
        // cache against what glPopAttrib restores (mirrors MenuBackdrop.draw's discipline).
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                        | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, SceneCapture.texture());
        GL11.glColor4f(1f, 1f, 1f, strength > 1f ? 1f : strength);   // vColor.a = effect strength
        GlassProgram.bind(GlassProgram.BLUR);
        GlassProgram.setEdgeBlur(radius, dim);
        // texcoord.y = 0 at the dissolve EDGE, 1 at the inner boundary (drives the shader ramp)
        float tyTop = topEdge ? 0f : 1f;
        float tyBot = topEdge ? 1f : 0f;
        GL11.glBegin(GL11.GL_QUADS);            // front-facing TL->BL->BR->TR
        GL11.glTexCoord2f(0f, tyTop); GL11.glVertex2f(x0, y0);
        GL11.glTexCoord2f(0f, tyBot); GL11.glVertex2f(x0, y1);
        GL11.glTexCoord2f(1f, tyBot); GL11.glVertex2f(x1, y1);
        GL11.glTexCoord2f(1f, tyTop); GL11.glVertex2f(x1, y0);
        GL11.glEnd();
        GlassProgram.unbind();
        GL11.glDepthMask(true);
        GL11.glPopAttrib();
        GlStateManager.bindTexture(0);
        resetColorCache();
    }

    public static void beginScissor(float x0, float y0, float x1, float y1) {
        double sf = Mc1132.scaleFactor();
        int x = (int) Math.round(x0 * sf);
        int w = (int) Math.round((x1 - x0) * sf);
        int h = (int) Math.round((y1 - y0) * sf);
        int y = Mc1132.fbH() - (int) Math.round(y1 * sf);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, y, Math.max(0, w), Math.max(0, h));
    }

    public static void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
