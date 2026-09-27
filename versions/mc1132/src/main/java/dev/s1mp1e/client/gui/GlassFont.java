package dev.s1mp1e.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

/**
 * Text for the 1.13.2 config GUI, HUD editor and module HUDs. This is the mc1201/mc1144
 * {@code TextRenderer} wrapper adapted to 1.13.2 — NOT mc189's 249-line AWT OTF rasteriser:
 * the whole-game PingFang look comes from the {@code assets/minecraft/font} TTF provider
 * (see {@code assets/s1mp1e/font/pingfangtc_semibold.ttf}, served through the mod's own
 * self-registered resource pack in {@code dev.s1mp1e.glass.compat}), so callers just draw
 * through MC's own {@link TextRenderer}.
 *
 * <p>1.13.2's {@code TextRenderer.draw} has the unmapped name {@code method_18355(String,
 * float, float, int argb)}; {@code drawWithShadow}/{@code getStringWidth}/{@code fontHeight}
 * keep their mapped names. {@code TextRenderer.drawLayer} forces the colour OPAQUE when the
 * top 6 alpha bits are clear (<code>(argb &amp; 0xFC000000) == 0</code>), so a nearly-
 * transparent draw would flash to full opacity. We skip the draw entirely once the effective
 * alpha is below ~8/255.
 */
public final class GlassFont {

    private GlassFont() {}

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    /** Draw {@code s} with an RGB colour and a separate 0..1 alpha; {@code shadow} adds the
     *  vanilla drop shadow. A negligible alpha is dropped (1.13.2 would force it opaque). */
    public static void draw(String s, float x, float y, int rgb, float alpha, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        int a = Math.round(alpha * 255f);
        if (a < 8) return;                       // 1.13.2 forces opaque when (argb & 0xFC000000) == 0
        int argb = (Math.min(255, a) << 24) | (rgb & 0xFFFFFF);
        TextRenderer tr = mc().textRenderer;
        if (shadow) tr.drawWithShadow(s, Math.round(x), Math.round(y), argb);
        else        tr.method_18355(s, Math.round(x), Math.round(y), argb);
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
}
