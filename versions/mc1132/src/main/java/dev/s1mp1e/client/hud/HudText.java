package dev.s1mp1e.client.hud;

import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import org.lwjgl.opengl.GL11;

/**
 * The single text seam for S1mp1e HUD modules (H2): draws exactly like {@link GlassFont#drawARGB} until
 * {@link ChromaHudModule} is on, at which point the colour becomes the animated chroma hue.
 *
 * <p>Chroma is sampled at SCREEN positions - the local draw point pushed through the current GL model-view
 * (1.13.2 has no {@code MatrixStack}: a module that translates / scales its HUD text does so on the GL matrix) - so
 * every module lines up with the one global diagonal rainbow sweep. With a per-character wave each glyph is drawn
 * on its own at its advance (1.13.2 has no RGB {@code Style} colour, that is 1.16+), coloured at its own screen
 * centre; with wave 0 every element shows the same, uniformly cycling hue.
 *
 * <p>Only HUD modules route through here; the config GUI keeps drawing through {@link GlassFont} directly, so it
 * never goes chroma. Pure recolour of what the client already draws - fair play.
 */
public final class HudText {

    private HudText() {}

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

        int ix = Math.round(x), iy = Math.round(y);
        if (!ChromaHudModule.perCharacter()) {
            float[] sp = screen(ix, iy);
            GlassFont.drawARGB(s, ix, iy, (a << 24) | ChromaHudModule.textRgbAt(sp[0], sp[1]), shadow);
            return;
        }

        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        float adv = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            float w = font.getStringWidth(ch);
            float[] sp = screen(ix + adv + w * 0.5f, iy);   // hue at the glyph's centre
            GlassFont.drawARGB(ch, ix + adv, iy, (a << 24) | ChromaHudModule.textRgbAt(sp[0], sp[1]), shadow);
            adv += w;
            i += Character.charCount(cp);
        }
    }

    private static float[] screen(float x, float y) {
        try {
            GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, MV);
            // column-major 4x4: element (row, col) = MV[col*4 + row]; only the 2D affine part is needed.
            return new float[] { MV[0] * x + MV[4] * y + MV[12], MV[1] * x + MV[5] * y + MV[13] };
        } catch (Throwable t) {
            return new float[] { x, y };
        }
    }
}
