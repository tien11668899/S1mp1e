package dev.s1mp1e.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Apple-style text for the S1mp1e GUI. Renders the SAME {@code PingFangTC-Semibold.otf}
 * the launcher ships, so the mod and the launcher share one typeface (full CJK). Each
 * glyph is rasterised once (Graphics2D, grayscale AA) at the current GUI scale into its
 * own GL texture and cached; strings are drawn as tinted textured quads.
 *
 * <p>If the OTF can't be loaded (missing / bad JRE), every method transparently falls
 * back to Minecraft's bitmap {@code FontRenderer}, so the GUI is never left textless.
 *
 * <p>All calls happen on the render thread inside {@code drawScreen}. GL discipline
 * mirrors the rest of {@link GlassWidgets}: front-facing quads (the GUI pass culls
 * back faces), blend left enabled on exit, and {@link GlassWidgets#resetColorCache()}
 * after the tint so later glass/text draws aren't multiplied by a stale colour.
 */
public final class GlassFont {

    private static final float GUI_H = 9.0f;   // logical text size in GUI px (~matches MC's 9)
    private static final int   PAD   = 2;      // physical-px slack around each rasterised glyph

    private static Font base, fallback;
    private static boolean triedLoad, ok;

    private static int scale = 0;
    private static Font derived, derivedFb;
    private static FontMetrics fm, fmFb;
    private static int ascentP, lineP;
    private static final Map<Character, Glyph> cache = new HashMap<Character, Glyph>();
    /** AA + fractional-metrics context, used to size each glyph to its real ink bounds. */
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    /** One rasterised glyph. w/h = texture size (physical px); ix/iy = ink bounds relative to
     *  the pen origin/baseline (physical px, iy negative = above baseline); advGui = pen advance. */
    private static final class Glyph { int tex, w, h, ix, iy; float advGui; }

    private GlassFont() {}

    private static Minecraft mc() { return Minecraft.getMinecraft(); }

    private static void load() {
        if (triedLoad) return;
        triedLoad = true;
        InputStream in = null;
        try {
            in = GlassFont.class.getResourceAsStream("/assets/s1mp1e/fonts/PingFangTC-Semibold.otf");
            if (in == null) { ok = false; return; }
            base = Font.createFont(Font.TRUETYPE_FONT, in);
            fallback = new Font("SansSerif", Font.PLAIN, 12);   // covers symbols PingFang lacks
            ok = true;
        } catch (Throwable t) {
            ok = false;
            System.out.println("[S1mp1e] PingFang load failed, using MC font: " + t);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** @return true if the OTF path is usable; also (re)builds the derived font on a scale change. */
    private static boolean ready() {
        load();
        if (!ok) return false;
        int sf = Math.max(1, new ScaledResolution(mc()).getScaleFactor());
        if (sf != scale || derived == null) rebuild(sf);
        return true;
    }

    private static void rebuild(int sf) {
        // GlStateManager.deleteTexture (not raw GL11.glDeleteTextures) so the cached
        // texture binding is cleared alongside the GL delete.
        for (Glyph g : cache.values()) { try { GlStateManager.deleteTexture(g.tex); } catch (Throwable ignored) {} }
        cache.clear();
        scale = sf;
        float pts = GUI_H * sf;
        derived   = base.deriveFont(pts);
        derivedFb = fallback.deriveFont(pts);
        fm   = metrics(derived);
        fmFb = metrics(derivedFb);
        ascentP = fm.getAscent();
        lineP   = fm.getAscent() + fm.getDescent();
    }

    private static FontMetrics metrics(Font f) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = probe.createGraphics();
        g.setFont(f);
        FontMetrics m = g.getFontMetrics();
        g.dispose();
        return m;
    }

    private static Glyph glyph(char c) {
        Glyph gl = cache.get(Character.valueOf(c));
        if (gl != null) return gl;

        boolean useBase = derived.canDisplay(c);
        Font f = useBase ? derived : derivedFb;
        FontMetrics gfm = useBase ? fm : fmFb;
        int adv = Math.max(1, gfm.charWidth(c));

        // Size the glyph texture to its ACTUAL rendered ink box (not the font's generic
        // ascent/descent), so descenders/tall glyphs are never clipped.
        Rectangle ink = f.createGlyphVector(FRC, String.valueOf(c)).getPixelBounds(FRC, 0f, 0f);
        gl = new Glyph();
        gl.advGui = adv / (float) scale;
        if (ink.width <= 0 || ink.height <= 0) {   // blank glyph (space etc.) — advance only
            gl.tex = 0; gl.w = 0; gl.h = 0; gl.ix = 0; gl.iy = 0;
            cache.put(Character.valueOf(c), gl);
            return gl;
        }
        int w = ink.width + PAD * 2;
        int h = ink.height + PAD * 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(f);
        g.setColor(Color.WHITE);
        // Place the ink so its top-left (ink.x, ink.y) lands at (PAD, PAD): baseline row = PAD - ink.y.
        g.drawString(String.valueOf(c), PAD - ink.x, PAD - ink.y);
        g.dispose();

        gl.tex = upload(img, w, h);
        gl.w = w; gl.h = h; gl.ix = ink.x; gl.iy = ink.y;
        cache.put(Character.valueOf(c), gl);
        return gl;
    }

    private static int upload(BufferedImage img, int w, int h) {
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int i = 0; i < px.length; i++) {
            int a = (px[i] >>> 24) & 0xFF;   // white glyph on transparent → alpha carries coverage
            buf.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) a);
        }
        buf.flip();
        int tex = GL11.glGenTextures();
        GlStateManager.bindTexture(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return tex;
    }

    // ---- public API, all in GUI px (top-left origin, matching MC's label convention) ----

    public static float height() {
        if (!ready()) return mc().fontRendererObj.FONT_HEIGHT;
        return lineP / (float) scale;
    }

    public static float width(String s) {
        if (s == null || s.length() == 0) return 0f;
        if (!ready()) return mc().fontRendererObj.getStringWidth(s);
        float w = 0f;
        for (int i = 0; i < s.length(); i++) w += glyph(s.charAt(i)).advGui;
        return w;
    }

    /** Advance width of a single character in GUI px (0 if the OTF isn't ready). */
    public static float charWidth(char c) {
        if (!ready()) return 0f;
        return glyph(c).advGui;
    }

    /** True when the PingFang path is live (used by the global FontRenderer replacement). */
    public static boolean available() { return ready(); }

    /** Draw with a packed ARGB colour, treating alpha 0 as opaque (matches FontRenderer,
     *  so the HUD modules' colour settings can be handed over unchanged). */
    public static void drawARGB(String s, float x, float y, int argb, boolean shadow) {
        int aa = (argb >>> 24) & 0xFF;
        draw(s, x, y, argb & 0xFFFFFF, aa == 0 ? 1f : aa / 255f, shadow);
    }

    public static void draw(String s, float x, float y, int rgb, float alpha, boolean shadow) {
        if (s == null || s.length() == 0) return;
        int a = Math.round(alpha * 255f);
        if (a < 4) return;
        if (!ready()) {
            int argb = (Math.min(255, a) << 24) | (rgb & 0xFFFFFF);
            if (shadow) mc().fontRendererObj.drawStringWithShadow(s, x, y, argb);
            else mc().fontRendererObj.drawString(s, Math.round(x), Math.round(y), argb, false);
            GlassWidgets.resetColorCache();
            return;
        }
        if (shadow) run(s, x + 0.6f, y + 0.6f, 0x000000, alpha * 0.55f);
        run(s, x, y, rgb, alpha);
    }

    private static void run(String s, float x, float y, int rgb, float alpha) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float a = alpha < 0f ? 0f : (alpha > 1f ? 1f : alpha);
        float sc = (float) scale;
        float baseY = y + ascentP / sc;   // shared baseline; y is the text top (MC convention)

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableAlpha();
        GlStateManager.color(r, g, b, a);

        float penX = x;
        for (int i = 0; i < s.length(); i++) {
            Glyph gl = glyph(s.charAt(i));
            if (gl.tex != 0) {
                // place the ink box by its real bounds relative to the pen/baseline
                float qx0 = penX + (gl.ix - PAD) / sc, qy0 = baseY + (gl.iy - PAD) / sc;
                float qx1 = qx0 + gl.w / sc, qy1 = qy0 + gl.h / sc;
                GlStateManager.bindTexture(gl.tex);
                // front-facing (TL→BL→BR→TR) so the GUI's back-face cull keeps it
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(qx0, qy0);
                GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(qx0, qy1);
                GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(qx1, qy1);
                GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(qx1, qy0);
                GL11.glEnd();
            }
            penX += gl.advGui;
        }

        GlStateManager.enableAlpha();
        GlassWidgets.resetColorCache();
    }
}
