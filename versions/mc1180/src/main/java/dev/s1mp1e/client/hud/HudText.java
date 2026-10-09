package dev.s1mp1e.client.hud;

import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vector4f;

/**
 * The single text seam for S1mp1e HUD modules (H2): draws exactly like {@link GlassFont#drawARGB} until
 * {@link ChromaHudModule} is on, at which point the colour becomes the animated chroma hue.
 *
 * <p>Chroma is sampled at SCREEN positions — the local draw point pushed through the module's current
 * {@link MatrixStack} — so a module that translates/scales its HUD text still lines up with the one
 * global diagonal rainbow sweep. With a per-character wave the string is drawn ONCE as a
 * {@link net.minecraft.text.Text} whose characters each carry their own {@link Style} colour: vanilla's
 * {@code TextRenderer} takes the RGB from the style and the alpha from the passed colour argument, so
 * alpha, shadow and glyph advance/kerning are all unchanged. With wave 0 every element shows the same,
 * uniformly cycling hue.
 *
 * <p>Only HUD modules route through here; the config GUI keeps drawing through {@link GlassFont}
 * directly, so it never goes chroma. Pure recolour of what the client already draws — fair play.
 *
 * <p><b>1.18.2 note.</b> There is no {@code GuiGraphicsExtractor}/{@code Matrix3x2f} pose here (26.2):
 * the screen position comes from the 4×4 {@code MatrixStack} position matrix, and per-character colour
 * is set with {@link Style#withColor(net.minecraft.text.TextColor)} (available from 1.16), built from a
 * {@link LiteralText} per glyph.
 */
public final class HudText {

    private HudText() {}

    /** Same contract as {@link GlassFont#drawARGB}: an all-zero alpha byte is promoted to opaque. */
    public static void draw(MatrixStack matrices, String s, float x, float y, int argb, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        if (HudFade.alpha < 1F) {
            // promote "alpha 0 = opaque" BEFORE fading, or a fully faded label would read as opaque again
            if ((argb >>> 24 & 0xFF) == 0) argb |= 0xFF000000;
            argb = HudFade.argb(argb);
            if ((argb >>> 24 & 0xFF) < 8) return;
        }
        if (!ChromaHudModule.textActive()) {
            GlassFont.drawARGB(matrices, s, x, y, argb, shadow);
            return;
        }
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) a = 255;
        if (a < 8) return;   // GlassFont's invisibility cut-off

        int ix = Math.round(x), iy = Math.round(y);
        Matrix4f mat = matrices.peek().getPositionMatrix();
        TextRenderer font = MinecraftClient.getInstance().textRenderer;

        if (!ChromaHudModule.perCharacter()) {
            int rgb = ChromaHudModule.textRgbAt(screenX(mat, ix, iy), screenY(mat, ix, iy));
            GlassFont.drawARGB(matrices, s, ix, iy, (a << 24) | rgb, shadow);
            return;
        }

        // Per-character wave: one Text, each glyph coloured at its own SCREEN centre; alpha from the arg.
        MutableText line = new LiteralText("");
        float adv = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            float w = font.getWidth(ch);
            float cx = ix + adv + w * 0.5f;
            int rgb = ChromaHudModule.textRgbAt(screenX(mat, cx, iy), screenY(mat, cx, iy));
            line.append(new LiteralText(ch).setStyle(Style.EMPTY.withColor(net.minecraft.text.TextColor.fromRgb(rgb))));
            adv += w;
            i += Character.charCount(cp);
        }
        int color = (a << 24) | 0xFFFFFF;
        if (shadow) font.drawWithShadow(matrices, line, ix, iy, color);
        else        font.draw(matrices, line, ix, iy, color);
    }

    private static final Vector4f TMP = new Vector4f();

    private static float screenX(Matrix4f mat, float px, float py) {
        TMP.set(px, py, 0f, 1f);
        TMP.transform(mat);
        return TMP.getX();
    }

    private static float screenY(Matrix4f mat, float px, float py) {
        TMP.set(px, py, 0f, 1f);
        TMP.transform(mat);
        return TMP.getY();
    }
}
