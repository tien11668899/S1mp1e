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
        GlStateManager.disableBlend();
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

    /** Draw text with shadow at panel alpha; skips when the effective alpha < 8
     *  (FontRenderer treats alpha 0 as opaque, so very-faint text must be dropped). */
    public static void label(String s, float x, float y, int rgb, float alpha) {
        int a = clampByte(alpha);
        if (a < 8) return;
        mc().fontRendererObj.drawStringWithShadow(s, x, y, (a << 24) | (rgb & 0xFFFFFF));
    }

    public static void labelNoShadow(String s, float x, float y, int rgb, float alpha) {
        int a = clampByte(alpha);
        if (a < 8) return;
        mc().fontRendererObj.drawString(s, Math.round(x), Math.round(y), (a << 24) | (rgb & 0xFFFFFF), false);
    }

    public static int strW(String s) { return mc().fontRendererObj.getStringWidth(s); }
    public static int fontH() { return mc().fontRendererObj.FONT_HEIGHT; }

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
