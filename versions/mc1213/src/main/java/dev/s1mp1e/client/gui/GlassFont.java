package dev.s1mp1e.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Text for the 1.21.1 config GUI. Milestone 1 wraps MC's own {@code TextRenderer}
 * via {@link DrawContext#drawText} (proves the screen); the PingFang core-profile
 * glyph-texture renderer replaces this in Milestone 2 without changing callers.
 */
public final class GlassFont {

    private GlassFont() {}

    private static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    public static void draw(DrawContext ctx, String s, float x, float y, int rgb, float alpha, boolean shadow) {
        if (s == null || s.isEmpty()) return;
        int a = Math.round(alpha * 255f);
        if (a < 8) return;
        int argb = (Math.min(255, a) << 24) | (rgb & 0xFFFFFF);
        ctx.drawText(mc().textRenderer, s, Math.round(x), Math.round(y), argb, shadow);
    }

    /** Draw with a packed ARGB colour (the form the colour Settings store). An
     *  all-zero alpha byte is promoted to opaque, matching the module HUDs. */
    public static void drawARGB(DrawContext ctx, String s, float x, float y, int argb, boolean shadow) {
        int a = (argb >>> 24) & 255;
        if (a == 0) a = 255;
        draw(ctx, s, x, y, argb & 0xFFFFFF, a / 255f, shadow);
    }

    public static float width(String s) { return s == null ? 0 : mc().textRenderer.getWidth(s); }
    public static float height() { return mc().textRenderer.fontHeight; }
}
