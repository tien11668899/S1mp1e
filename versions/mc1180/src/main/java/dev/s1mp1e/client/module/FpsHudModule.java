package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.hud.HudText;
import dev.s1mp1e.glass.mixin.MinecraftClientFpsAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;

/**
 * A glass FPS pill so the player doesn't need the full F3 overlay. Reads only the client's own frame
 * rate — pure client telemetry, no game state.
 *
 * <p><b>1.18.2 note.</b> {@code MinecraftClient.getCurrentFps()} does not exist here, so the value comes
 * from {@link MinecraftClientFpsAccessor#s1mp1e$currentFps()} (the {@code currentFps} static field),
 * falling back to parsing the leading integer of {@code mc.fpsDebugString} if that ever throws. Drawing is
 * adapted from 1.20.1: no {@code DrawContext} — the pill background and text take the {@link MatrixStack}.
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
    public void renderHud(MatrixStack matrices) {
        // visibility (incl. the fade-out after switching off) is decided by the HUD driver via HudFade
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        String s = fps(mc) + (suffix.boolValue ? " FPS" : "");
        int tw = Math.round(GlassFont.width(s));
        int th = Math.round(GlassFont.height());
        int boxW = tw + PAD * 2, boxH = th + PAD * 2;
        float sc = (float) scale.doubleValue;
        lastW = Math.round(boxW * sc);
        lastH = Math.round(boxH * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
        // REAL liquid glass background at absolute coords; text on top via the matrix (which also
        // carries the scale). Both are identity-based in the HUD pass so they align. See HudGlass.glassBox.
        if (bg.boolValue) HudGlass.glassBox(matrices, x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        matrices.push();
        try {
            matrices.translate(x0, y0, 0f);
            matrices.scale(sc, sc, 1f);
            HudText.draw(matrices, s, PAD, PAD, color.colorValue, true);
        } finally {
            matrices.pop();
        }
    }

    /** Current FPS via the accessor; on any failure, the leading integer of {@code fpsDebugString}. */
    private static int fps(MinecraftClient mc) {
        try {
            return MinecraftClientFpsAccessor.s1mp1e$currentFps();
        } catch (Throwable ignored) {
            try {
                String dbg = mc.fpsDebugString;
                if (dbg != null) {
                    int i = 0;
                    while (i < dbg.length() && Character.isDigit(dbg.charAt(i))) i++;
                    if (i > 0) return Integer.parseInt(dbg.substring(0, i));
                }
            } catch (Throwable ignored2) { }
            return 0;
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
