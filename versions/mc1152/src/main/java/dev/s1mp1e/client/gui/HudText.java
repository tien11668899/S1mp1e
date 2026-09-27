package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import org.lwjgl.opengl.GL11;

/**
 * The single text seam for S1mp1e HUD modules (H2): draws like {@link GlassFont#drawARGB}, but when
 * {@link ChromaHudModule} is on the colour becomes the animated chroma hue.
 *
 * <p>Chroma is sampled at SCREEN positions, so a module that translates/scales its text still lines up with
 * the global rainbow sweep. 26.2 reads the glyph's screen point from the {@code Matrix3x2f} GUI pose; 1.15.2 has
 * no such pose — the GUI/HUD pass runs in fixed-function GL with the module's {@code RenderSystem.translate/scale}
 * baked straight into the GL model-view — so this reads {@code GL_MODELVIEW_MATRIX} and transforms the local draw
 * point through it to recover the same scaled-GUI screen coordinate. With a per-character wave the string is drawn
 * one glyph at a time, each glyph coloured by the hue at its own screen centre; alpha, shadow and glyph advance stay
 * exactly as for a plain {@link GlassFont} string (each glyph is drawn through {@link GlassFont#draw}).
 *
 * <p>Only HUD modules call this; the config GUI keeps using {@link GlassFont} directly, so it never goes chroma.
 */
public final class HudText {

    private HudText() {}

    /** Scratch for the 4x4 GL model-view (render thread only). */
    private static final float[] MV = new float[16];

    /** Same contract as {@link GlassFont#drawARGB}: an all-zero alpha byte is promoted to opaque. */
    public static void draw(String s, float x, float y, int argb, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        if (!ChromaHudModule.textActive()) {
            GlassFont.drawARGB(s, x, y, argb, shadow);
            return;
        }
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) a = 255;
        if (a < 8) return;   // GlassFont's invisibility cut-off
        final float alpha = a / 255f;

        if (!ChromaHudModule.perCharacter()) {
            float[] sp = screen(x, y);
            GlassFont.draw(s, x, y, ChromaHudModule.textRgbAt(sp[0], sp[1]), alpha, shadow);
            return;
        }

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        float adv = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            float w = tr.getStringWidth(ch);
            float[] sp = screen(x + adv + w * 0.5f, y);   // hue at the glyph's centre
            GlassFont.draw(ch, x + adv, y, ChromaHudModule.textRgbAt(sp[0], sp[1]), alpha, shadow);
            adv += w;
            i += Character.charCount(cp);
        }
    }

    /** Transform a local GUI point through the current GL model-view -> scaled-GUI screen coords. */
    private static float[] screen(float x, float y) {
        try {
            GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, MV);
            // column-major 4x4: element (row, col) = MV[col*4 + row]; we only need the 2D affine part.
            float sx = MV[0] * x + MV[4] * y + MV[12];
            float sy = MV[1] * x + MV[5] * y + MV[13];
            return new float[] { sx, sy };
        } catch (Throwable t) {
            return new float[] { x, y };
        }
    }
}
