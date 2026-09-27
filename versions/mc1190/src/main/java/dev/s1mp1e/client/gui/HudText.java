package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vector4f;

/**
 * The single text seam for S1mp1e HUD modules: draws exactly like {@link GlassFont#drawARGB}, but when
 * {@link ChromaHudModule} is on the colour becomes the animated chroma hue. This is the 26.2 {@code hud/HudText}
 * ported to the 1.19.2 [MatrixStack] family (26.2's {@code Matrix3x2f} pose becomes the 4x4 {@link MatrixStack}).
 *
 * <p>Chroma is sampled at SCREEN positions (the local draw point pushed through the current pose), so a module that
 * translates/scales its text still lines up with the global rainbow sweep. With a per-character wave the string is
 * drawn ONCE as a {@link Text} whose characters each carry their own {@link Style} colour - the 1.19.2 text renderer
 * takes RGB from the style and alpha from the passed colour, so alpha, shadow and glyph advance/kerning all stay
 * exactly as for a plain string.
 *
 * <p>Only HUD modules call this; the config GUI keeps using {@link GlassFont} directly, so it never goes chroma. The
 * frosted glass knobs are untouched - frost stays neutral (fair-play recolour of what the client already draws).
 */
public final class HudText {

    private HudText() {}

    private static final Vector4f TMP = new Vector4f();   // render thread only

    /** Same contract as {@link GlassFont#drawARGB}: an all-zero alpha byte is promoted to opaque. */
    public static void draw(MatrixStack matrices, String s, float x, float y, int argb, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        if (!ChromaHudModule.textActive()) {
            GlassFont.drawARGB(matrices, s, x, y, argb, shadow);
            return;
        }
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) a = 255;
        if (a < 8) return;   // GlassFont's invisibility cut-off
        int ix = Math.round(x), iy = Math.round(y);
        Matrix4f m = matrices.peek().getPositionMatrix();
        TextRenderer font = MinecraftClient.getInstance().textRenderer;

        if (!ChromaHudModule.perCharacter()) {
            project(m, ix, iy);
            GlassFont.draw(matrices, s, ix, iy, ChromaHudModule.textRgbAt(TMP.getX(), TMP.getY()), a / 255f, shadow);
            return;
        }

        MutableText line = Text.empty();
        float adv = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            float w = font.getWidth(ch);
            project(m, ix + adv + w * 0.5f, iy);   // hue at the glyph's centre
            int rgb = ChromaHudModule.textRgbAt(TMP.getX(), TMP.getY());
            line.append(Text.literal(ch).setStyle(Style.EMPTY.withColor(rgb)));
            adv += w;
            i += Character.charCount(cp);
        }
        int argbOut = (a << 24) | 0xFFFFFF;
        if (shadow) font.drawWithShadow(matrices, line, ix, iy, argbOut);
        else        font.draw(matrices, line, ix, iy, argbOut);
    }

    /** Push a local (x,y) through the pose into {@link #TMP} (screen scaled-GUI px). */
    private static void project(Matrix4f m, float x, float y) {
        TMP.set(x, y, 0f, 1f);
        TMP.transform(m);
    }
}
