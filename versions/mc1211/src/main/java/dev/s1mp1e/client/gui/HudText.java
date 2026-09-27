package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.joml.Matrix4f;

/**
 * The single text seam for S1mp1e HUD modules (H2): draws like {@link GlassFont#drawARGB}, but when
 * {@link ChromaHudModule} is on the colour becomes the animated chroma hue.
 *
 * <p>Ported from the 26.2 {@code dev.s1mp1e.client.hud.HudText}. The 26.2 line uses a 2-D
 * {@code Matrix3x2f} pose; the 1.21.1 DrawContext family carries a 4x4 {@link net.minecraft.client.util.math.MatrixStack},
 * so the SCREEN position of a glyph is the local draw point pushed through the top position matrix
 * ({@code m00*x + m10*y + m30}, {@code m01*x + m11*y + m31}). A module that translates/scales its text
 * (Fps/Coords push+scale) therefore still lines up with the global rainbow sweep.
 *
 * <p>With a per-character wave the string is drawn ONCE as a {@link Text} whose characters each carry
 * their own {@link Style} colour — vanilla's text renderer takes RGB from the style and alpha from the
 * passed colour, so alpha, shadow and glyph advance/kerning all stay exactly as for a plain string
 * (per-character {@code Style.withColor} works from 1.16).
 *
 * <p>Only HUD modules call this; the config GUI keeps using {@link GlassFont} directly, so it never
 * goes chroma.
 */
public final class HudText {

    private HudText() {}

    /** Same contract as {@link GlassFont#drawARGB}: an all-zero alpha byte is promoted to opaque. */
    public static void draw(DrawContext ctx, String s, float x, float y, int argb, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        if (!ChromaHudModule.textActive()) {
            GlassFont.drawARGB(ctx, s, x, y, argb, shadow);
            return;
        }
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) a = 255;
        if (a < 8) return;                        // GlassFont's invisibility cut-off
        int ix = Math.round(x), iy = Math.round(y);
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        Matrix4f pose = ctx.getMatrices().peek().getPositionMatrix();

        if (!ChromaHudModule.perCharacter()) {
            float sx = pose.m00() * ix + pose.m10() * iy + pose.m30();
            float sy = pose.m01() * ix + pose.m11() * iy + pose.m31();
            ctx.drawText(font, s, ix, iy, (a << 24) | ChromaHudModule.textRgbAt(sx, sy), shadow);
            return;
        }

        MutableText line = Text.empty();
        float adv = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            float w = font.getWidth(ch);
            float cx = ix + adv + w * 0.5f;       // hue at the glyph's centre
            float sx = pose.m00() * cx + pose.m10() * iy + pose.m30();
            float sy = pose.m01() * cx + pose.m11() * iy + pose.m31();
            line.append(Text.literal(ch).setStyle(
                    Style.EMPTY.withColor(TextColor.fromRgb(ChromaHudModule.textRgbAt(sx, sy)))));
            adv += w;
            i += Character.charCount(cp);
        }
        ctx.drawText(font, line, ix, iy, (a << 24) | 0xFFFFFF, shadow);
    }
}
