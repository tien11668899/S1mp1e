package dev.s1mp1e.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Text for the S1mp1e HUD modules and config GUI — a thin wrapper over the vanilla {@code Font}.
 *
 * <p>26.2 port of mc1211's {@code GlassFont}: {@code DrawContext.drawText(textRenderer, ...)} →
 * {@link GuiGraphicsExtractor#text}, {@code textRenderer.getWidth/fontHeight} → {@code font.width/lineHeight}.
 * Call sites only change {@code ctx} → {@code g}. The PingFang look still comes from the resource override
 * ({@code assets/minecraft/font/default.json}), which applies to this vanilla {@code Font}.
 */
public final class GlassFont {

    private GlassFont() {}

    private static Minecraft mc() { return Minecraft.getInstance(); }

    public static void draw(GuiGraphicsExtractor g, String s, float x, float y, int rgb, float alpha, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        int a = Math.round(alpha * 255f);
        if (a < 8) return;
        int argb = (Math.min(255, a) << 24) | (rgb & 0xFFFFFF);
        g.text(mc().font, s, Math.round(x), Math.round(y), argb, shadow);
    }

    /** Draw with a packed ARGB colour (the form the colour Settings store). An all-zero alpha byte is promoted
     *  to opaque, matching the module HUDs. */
    public static void drawARGB(GuiGraphicsExtractor g, String s, float x, float y, int argb, boolean shadow) {
        int a = (argb >>> 24) & 255;
        if (a == 0) a = 255;
        draw(g, s, x, y, argb & 0xFFFFFF, a / 255f, shadow);
    }

    public static float width(String s) { return s == null ? 0 : mc().font.width(s); }
    public static float height() { return mc().font.lineHeight; }
}
