package dev.s1mp1e.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Text for the 1.16.5 config GUI. Wraps MC's own {@code TextRenderer}: the
 * MatrixStack-era equivalent of mc1201's {@code DrawContext.drawText} — this
 * version threads a {@link MatrixStack} through instead. On 1.16.5 the draws
 * are immediate (there is no DrawContext batching), so text lands on top of
 * whatever was painted before it, in call order.
 */
public final class GlassFont {

    private GlassFont() {}

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    public static void draw(MatrixStack m, String s, float x, float y, int rgb, float alpha, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        int a = Math.round(alpha * 255f);
        if (a < 8) return;   // 1.16.5's TextRenderer forces alpha < 4 opaque; keep mc1201's a<8 skip
        int argb = (Math.min(255, a) << 24) | (rgb & 0xFFFFFF);
        TextRenderer tr = mc().textRenderer;
        if (shadow) tr.drawWithShadow(m, s, Math.round(x), Math.round(y), argb);
        else        tr.draw(m, s, Math.round(x), Math.round(y), argb);
    }

    /** Draw with a packed ARGB colour (the form the colour Settings store). An
     *  all-zero alpha byte is promoted to opaque, matching the module HUDs. */
    public static void drawARGB(MatrixStack m, String s, float x, float y, int argb, boolean shadow) {
        int a = (argb >>> 24) & 255;
        if (a == 0) a = 255;
        draw(m, s, x, y, argb & 0xFFFFFF, a / 255f, shadow);
    }

    public static float width(String s) { return s == null ? 0 : mc().textRenderer.getWidth(s); }
    public static float height() { return mc().textRenderer.fontHeight; }
}
