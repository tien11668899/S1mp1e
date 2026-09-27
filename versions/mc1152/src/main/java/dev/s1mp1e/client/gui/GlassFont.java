package dev.s1mp1e.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

/**
 * Text for the 1.15.2 config GUI, HUD editor and module HUDs. This is the mc1201
 * {@code TextRenderer} wrapper adapted to 1.15.2 (ported from mc1144) — NOT mc189's
 * 249-line AWT OTF rasteriser: the whole-game PingFang look comes from the
 * {@code assets/minecraft/font} TTF provider (see {@code assets/s1mp1e/font/pingfangtc_semibold.ttf}),
 * so callers just draw through MC's own {@link TextRenderer}.
 *
 * <p>1.15.2's {@code TextRenderer.draw}/{@code drawWithShadow} take {@code (String, float,
 * float, int argb)} and force the colour OPAQUE when the top 6 alpha bits are clear
 * (<code>(argb &amp; 0xFC000000) == 0</code>), so a nearly-transparent draw would flash to
 * full opacity. We skip the draw entirely once the effective alpha is below ~8/255.
 *
 * <p>1.15.2 delta vs 1.14.4: every vanilla text draw goes through
 * {@code VertexConsumerProvider.Immediate} with {@code RenderLayer.getText(id)}, whose
 * {@code TRANSLUCENT_TRANSPARENCY} phase ENDS with {@code RenderSystem.disableBlend()} +
 * {@code defaultBlendFunc()} (and its alpha / depth phases leave alpha test and depth test
 * off). The GUI code drawn right after our text (glass, fills, more translucent text) expects
 * blending on, so after each vanilla text call we put the blend state back
 * ({@code enableBlend} + the premultiplied-safe {@code blendFuncSeparate(770, 771, 1, 0)}).
 * We never leave blend disabled on the way out of a GUI draw path.
 */
public final class GlassFont {

    private GlassFont() {}

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    /** Draw {@code s} with an RGB colour and a separate 0..1 alpha; {@code shadow} adds the
     *  vanilla drop shadow. A negligible alpha is dropped (1.15.2 would force it opaque). */
    public static void draw(String s, float x, float y, int rgb, float alpha, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        int a = Math.round(alpha * 255f);
        if (a < 8) return;                       // 1.15.2 forces opaque when (argb & 0xFC000000) == 0
        int argb = (Math.min(255, a) << 24) | (rgb & 0xFFFFFF);
        TextRenderer tr = mc().textRenderer;
        try {
            if (shadow) tr.drawWithShadow(s, Math.round(x), Math.round(y), argb);
            else        tr.draw(s, Math.round(x), Math.round(y), argb);
        } finally {
            restoreBlend();
        }
    }

    /** Draw with a packed ARGB colour (the form the colour Settings store). An
     *  all-zero alpha byte is promoted to opaque, matching the module HUDs. */
    public static void drawARGB(String s, float x, float y, int argb, boolean shadow) {
        int a = (argb >>> 24) & 255;
        if (a == 0) a = 255;
        draw(s, x, y, argb & 0xFFFFFF, a / 255f, shadow);
    }

    public static float width(String s) { return s == null ? 0 : mc().textRenderer.getStringWidth(s); }
    public static float height() { return mc().textRenderer.fontHeight; }

    /** Undo the TEXT render layer's end action ({@code disableBlend} + {@code defaultBlendFunc})
     *  so translucent GUI draws that follow still blend (hard rule 5: never leave a GUI draw
     *  path with blend disabled). */
    public static void restoreBlend() {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(770, 771, 1, 0);
    }
}
