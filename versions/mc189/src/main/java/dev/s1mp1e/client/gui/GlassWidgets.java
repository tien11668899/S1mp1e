package dev.s1mp1e.client.gui;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Stateless painter + hit-test toolkit shared by the config screen and the HUD
 * editor. Draws in the mod's liquid-glass style via {@link GlassRenderer}, with a
 * solid fallback whenever the glass shader is unavailable (GL2.0-less GPU or a
 * missing scene backdrop) so the UI is never invisible.
 */
public final class GlassWidgets {

    private GlassWidgets() {}

    private static Minecraft mc() { return Minecraft.getMinecraft(); }

    /** True when a frosted glass panel can actually render this frame. */
    public static boolean glassReady() {
        return GlassProgram.usable() && SceneCapture.hasBackdrop();
    }

    /**
     * Frosted glass panel at [x0,y0,x1,y1] (GUI px). Falls back to a solid dark
     * panel with a hairline border when the glass pipeline can't draw.
     */
    public static void panel(float x0, float y0, float x1, float y1, float alpha) {
        if (glassReady()) {
            GlassRenderer.panel(x0, y0, x1, y1, alpha);
        } else {
            int a = clampByte(alpha * 0.86f);
            drawRect(x0, y0, x1, y1, (a << 24) | 0x1C1C1E);
            int b = clampByte(alpha * 0.25f);
            border(x0, y0, x1, y1, (b << 24) | 0xFFFFFF);
            resetColorCache();
        }
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

    // ---- primitive rects ----

    public static void drawRect(float x0, float y0, float x1, float y1, int argb) {
        Gui.drawRect(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), argb);
    }

    /** Cheap rounded-corner fill (stadium): a full-height centre band plus vertically
     *  inset left/right bands, so corners read as rounded without the glass shader.
     *  Colours (accent fills, toggle tracks, swatches) can't use the white-only glass. */
    public static void fillRound(float x0, float y0, float x1, float y1, int argb, float r) {
        float rr = Math.min(r, Math.min((x1 - x0) / 2f, (y1 - y0) / 2f));
        drawRect(x0 + rr, y0, x1 - rr, y1, argb);
        drawRect(x0, y0 + rr, x0 + rr, y1 - rr, argb);
        drawRect(x1 - rr, y0 + rr, x1, y1 - rr, argb);
        // one bevel step on each corner for a softer edge
        float h = rr * 0.5f;
        drawRect(x0 + h, y0 + h, x0 + rr, y0 + rr, argb);
        drawRect(x1 - rr, y0 + h, x1 - h, y0 + rr, argb);
        drawRect(x0 + h, y1 - rr, x0 + rr, y1 - h, argb);
        drawRect(x1 - rr, y1 - rr, x1 - h, y1 - h, argb);
        resetColorCache();
    }

    /** Smooth (non-jagged) rounded-rect fill via a triangle fan with real corner arcs.
     *  Colours can't use the white-only glass shader, so this gives clean rounded
     *  toggles/sliders/swatches without the stair-stepping of {@link #fillRound}. */
    public static void fillRoundSmooth(float x0, float y0, float x1, float y1, int argb, float r) {
        r = Math.min(r, Math.min((x1 - x0) / 2f, (y1 - y0) / 2f));
        if (r < 0.75f) { drawRect(x0, y0, x1, y1, argb); resetColorCache(); return; }
        float a = (argb >>> 24) / 255f, cr = (argb >> 16 & 255) / 255f,
              cg = (argb >> 8 & 255) / 255f, cb = (argb & 255) / 255f;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glColor4f(cr, cg, cb, a);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f((x0 + x1) / 2f, (y0 + y1) / 2f);
        // Perimeter walked TL→BL→BR→TR — the SAME front-facing winding as gradient()/
        // GlassRenderer.batchQuad()/Gui.drawRect. The GUI pass runs with GL_CULL_FACE on,
        // so a reversed (back-facing) fan would be culled to nothing.
        arc(x0 + r, y0 + r, r, 270f, 180f);   // top-left
        arc(x0 + r, y1 - r, r, 180f,  90f);   // bottom-left
        arc(x1 - r, y1 - r, r,  90f,   0f);   // bottom-right
        arc(x1 - r, y0 + r, r,   0f, -90f);   // top-right
        GL11.glVertex2f(x0 + r, y0);          // close back to the first perimeter point
        GL11.glEnd();
        // Leave GL_BLEND enabled (MC's expected baseline, as GlassRenderer.endBatch does) so
        // translucent FontRenderer text drawn right after still alpha-blends during panel fades.
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
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

    /** Vertical gradient (top colour → bottom colour), ARGB. */
    public static void gradientV(float x0, float y0, float x1, float y1, int top, int bottom) {
        gradient(x0, y0, x1, y1, top, top, bottom, bottom);
    }

    /** Horizontal gradient (left colour → right colour), ARGB. */
    public static void gradientH(float x0, float y0, float x1, float y1, int left, int right) {
        gradient(x0, y0, x1, y1, left, right, right, left);
    }

    /** Four-corner gradient: colours for TL, TR, BR, BL. */
    public static void gradient(float x0, float y0, float x1, float y1,
                                int tl, int tr, int br, int bl) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        vtx(wr, x0, y0, tl);
        vtx(wr, x0, y1, bl);
        vtx(wr, x1, y1, br);
        vtx(wr, x1, y0, tr);
        t.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        // Leave GL_BLEND enabled (MC's expected baseline) so translucent text/glass drawn
        // right after still blends — matching GlassRenderer.endBatch and fillRoundSmooth.
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        resetColorCache();
    }

    private static void vtx(WorldRenderer wr, float x, float y, int argb) {
        wr.pos(x, y, 0.0D)
          .color(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24)
          .endVertex();
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

    /** Re-sync GlStateManager's colour cache after a raw drawRect/gradient run,
     *  exactly as GlassRenderer.endBatch does — else the next textured/glass draw
     *  gets multiplied by a stale colour. */
    public static void resetColorCache() {
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static boolean inside(int mx, int my, float x0, float y0, float x1, float y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    private static int clampByte(float a) {
        int v = Math.round(a * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ---- scissor (GUI px → physical px, bottom-left origin) ----

    /** iOS-26 "scroll edge effect": a progressive blur + adaptive dim that dissolves list
     *  content toward the edge. Re-grab the finished UI FIRST ({@code SceneCapture.forceGrab()}),
     *  then call this per list edge. {@code topEdge=true} → strongest at y0. {@code strength}
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
        GL11.glBegin(GL11.GL_QUADS);            // front-facing TL→BL→BR→TR
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
        ScaledResolution sr = new ScaledResolution(mc());
        int sf = sr.getScaleFactor();
        int x = Math.round(x0 * sf);
        int w = Math.round((x1 - x0) * sf);
        int h = Math.round((y1 - y0) * sf);
        int y = mc().displayHeight - Math.round(y1 * sf);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, y, Math.max(0, w), Math.max(0, h));
    }

    public static void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
