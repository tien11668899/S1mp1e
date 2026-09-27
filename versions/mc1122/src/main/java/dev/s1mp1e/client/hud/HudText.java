package dev.s1mp1e.client.hud;

import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * The single text seam for S1mp1e HUD modules (feature H2): draws exactly like
 * {@link GlassFont#drawARGB} until {@link ChromaHudModule} is on, then the colour becomes the
 * animated chroma hue.
 *
 * <p>1.12.2 port of 26.2's {@code hud/HudText}. 26.2 reads a {@code Matrix3x2f} pose to turn a
 * module's LOCAL draw point into a SCREEN position (so the diagonal chroma wave lines up across
 * every HUD element even when a module translates/scales its text). 1.12.2 is immediate-mode with
 * no per-call pose object, so the same screen position is recovered from the live GL modelview
 * matrix ({@code GL_MODELVIEW_MATRIX}) — the HUD pass runs under the scaled-GUI ortho projection,
 * so the modelview transform of a local point is exactly the scaled-GUI screen px the wave is
 * defined in.
 *
 * <p>With a per-character wave the string is drawn glyph-by-glyph, each glyph coloured by the
 * chroma hue sampled at its own screen centre (26.2 builds one {@code Component} whose chars carry
 * per-char {@code Style} colours; 1.12.2 has no such API in a HUD context, so it draws each glyph
 * with {@link GlassFont#draw}, which keeps the same advance/shadow/alpha). Alpha 0 is promoted to
 * opaque, matching {@link GlassFont#drawARGB}.
 *
 * <p>Only HUD modules call this; the config GUI keeps using {@link GlassFont} directly, so it never
 * goes chroma. The glass frost is never touched — only the text colour changes (fair-play).
 */
public final class HudText {

    private HudText() {}

    /** Scratch modelview readback, render thread only. */
    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);
    private static final float[] M = new float[16];

    /** Same contract as {@link GlassFont#drawARGB}: an all-zero alpha byte is promoted to opaque. */
    public static void draw(String s, float x, float y, int argb, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        if (!ChromaHudModule.textActive()) {
            GlassFont.drawARGB(s, x, y, argb, shadow);
            return;
        }
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) a = 255;
        if (a < 4) return;                 // GlassFont's invisibility cut-off
        float alpha = a / 255f;

        readModelview();

        if (!ChromaHudModule.perCharacter()) {
            float sx = screenX(x, y), sy = screenY(x, y);
            GlassFont.draw(s, x, y, ChromaHudModule.textRgbAt(sx, sy), alpha, shadow);
            return;
        }

        boolean glass = GlassFont.available();
        Minecraft mc = Minecraft.getMinecraft();
        float penX = x;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            float w = glass ? GlassFont.charWidth(c) : mc.fontRenderer.getCharWidth(c);
            float cx = penX + w * 0.5f;      // hue at the glyph's centre
            int rgb = ChromaHudModule.textRgbAt(screenX(cx, y), screenY(cx, y));
            GlassFont.draw(String.valueOf(c), penX, y, rgb, alpha, shadow);
            penX += w;
        }
    }

    // ---- local -> screen (scaled-GUI px) via the live GL modelview ---------

    private static void readModelview() {
        try {
            MAT.clear();
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MAT);
            for (int i = 0; i < 16; i++) M[i] = MAT.get(i);
        } catch (Throwable t) {
            // Identity fallback: chroma still cycles, only the diagonal offset is dropped.
            for (int i = 0; i < 16; i++) M[i] = (i % 5 == 0) ? 1f : 0f;
        }
    }

    /** x' = m00*x + m10*y + m30 (column-major 4x4). */
    private static float screenX(float x, float y) { return M[0] * x + M[4] * y + M[12]; }
    /** y' = m01*x + m11*y + m31. */
    private static float screenY(float x, float y) { return M[1] * x + M[5] * y + M[13]; }
}
