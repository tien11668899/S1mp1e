package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * A glass FPS pill so the player doesn't need the full F3 overlay. Reads only
 * {@code MinecraftClient.getCurrentFps()} — pure client telemetry, no game state.
 */
public final class FpsHudModule extends Module implements HudBounds, HudRenderer {

    private static final int PAD = 3;

    public final Setting posX   = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY   = add(Setting.integer("Y", 4, 0, 4000));
    public final Setting scale   = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting color   = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting suffix  = add(Setting.bool("Show 'FPS'", true));
    public final Setting bg      = add(Setting.bool("Background", true));
    public int lastW = 24, lastH = 12;

    public FpsHudModule() { super("FpsHUD", "HUD"); this.enabled = true; }

    @Override
    public void renderHud(DrawContext ctx) {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        String s = mc.getCurrentFps() + (suffix.boolValue ? " FPS" : "");
        int tw = Math.round(GlassFont.width(s));
        int th = Math.round(GlassFont.height());
        int boxW = tw + PAD * 2, boxH = th + PAD * 2;
        float sc = (float) scale.doubleValue;
        lastW = Math.round(boxW * sc);
        lastH = Math.round(boxH * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
        // REAL liquid glass background at absolute coords; text on top via the ctx matrix (which also
        // carries the scale). Both are identity-based in the HUD pass so they align. See HudGlass.glassBox.
        if (bg.boolValue) HudGlass.glassBox(ctx, x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        ctx.getMatrices().push();
        try {
            ctx.getMatrices().translate(x0, y0, 0f);
            ctx.getMatrices().scale(sc, sc, 1f);
            GlassFont.drawARGB(ctx, s, PAD, PAD, color.colorValue, true);
        } finally {
            ctx.getMatrices().pop();
        }
    }

    // ---- HudBounds ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : 24; }
    public int hudH() { return lastH > 0 ? lastH : 12; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }
}
