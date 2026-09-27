package dev.s1mp1e.client.hud;

import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.module.ChromaHudModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/**
 * The single text seam for S1mp1e HUD modules: draws like {@link GlassFont#drawARGB}, but when
 * {@link ChromaHudModule} is on the colour becomes the animated chroma hue.
 *
 * <p>Chroma is sampled at SCREEN positions (the local draw point pushed through {@code g.pose()}), so a module that
 * translates/scales its text still lines up with the global rainbow sweep. With a per-character wave the string is
 * drawn ONCE as a {@link Component} whose characters each carry their own {@link Style} colour — vanilla's text
 * renderer takes RGB from the style and alpha from the passed colour ({@code Font.PreparedTextBuilder
 * .getTextColor}), so alpha, shadow and glyph advance/kerning all stay exactly as for a plain string.
 *
 * <p>Only HUD modules call this; the config GUI keeps using {@link GlassFont} directly, so it never goes chroma.
 */
public final class HudText {

    private HudText() {}

    private static final Vector2f TMP = new Vector2f();   // render thread only

    /** Same contract as {@link GlassFont#drawARGB}: an all-zero alpha byte is promoted to opaque. */
    public static void draw(GuiGraphicsExtractor g, String s, float x, float y, int argb, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        if (!ChromaHudModule.textActive()) {
            GlassFont.drawARGB(g, s, x, y, argb, shadow);
            return;
        }
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) a = 255;
        if (a < 8) return;   // GlassFont's invisibility cut-off
        int ix = Math.round(x), iy = Math.round(y);
        Matrix3x2f pose = g.pose();
        Font font = Minecraft.getInstance().font;

        if (!ChromaHudModule.perCharacter()) {
            pose.transformPosition(ix, iy, TMP);
            g.text(font, s, ix, iy, (a << 24) | ChromaHudModule.textRgbAt(TMP.x, TMP.y), shadow);
            return;
        }

        MutableComponent line = Component.empty();
        float adv = 0f;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            float w = font.width(ch);
            pose.transformPosition(ix + adv + w * 0.5f, iy, TMP);   // hue at the glyph's centre
            line.append(Component.literal(ch).withStyle(Style.EMPTY.withColor(ChromaHudModule.textRgbAt(TMP.x, TMP.y))));
            adv += w;
            i += Character.charCount(cp);
        }
        g.text(font, line, ix, iy, (a << 24) | 0xFFFFFF, shadow);
    }
}
