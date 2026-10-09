package dev.s1mp1e.client.module;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.hud.HudText;
import dev.s1mp1e.glass.mixin.MinecraftClientFpsAccessor;
import net.minecraft.client.MinecraftClient;

/**
 * A glass FPS pill so the player doesn't need the full F3 overlay. Reads only the client's own frame rate —
 * pure client telemetry, no game state.
 *
 * <p>1.14.4 has no {@code MinecraftClient.getCurrentFps()}: the value is the private static field
 * {@code currentFps}, read via {@link MinecraftClientFpsAccessor}. If that accessor is unavailable (mixin
 * failed to apply) the leading integer of {@code mc.fpsDebugString} is parsed instead, so the readout never
 * breaks the HUD pass.
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
    public void renderHud() {
        // visibility (incl. the fade-out after switching off) is decided by the HUD driver via HudFade; no enabled-guard
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        String s = readFps(mc) + (suffix.boolValue ? " FPS" : "");
        int tw = Math.round(GlassFont.width(s));
        int th = Math.round(GlassFont.height());
        int boxW = tw + PAD * 2, boxH = th + PAD * 2;
        float sc = (float) scale.doubleValue;
        lastW = Math.round(boxW * sc);
        lastH = Math.round(boxH * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
        // REAL liquid glass background at absolute coords; text on top via the MatrixStack (which also
        // carries the scale). Both are identity-based in the HUD pass so they align. See HudGlass.glassBox.
        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translated(x0, y0, 0f);
            GlStateManager.scalef(sc, sc, 1f);
            HudText.draw(s, PAD, PAD, color.colorValue, true);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** The client frame rate, via the static field accessor, falling back to the F3 debug string. */
    private static int readFps(MinecraftClient mc) {
        try {
            return MinecraftClientFpsAccessor.s1mp1e$currentFps();
        } catch (Throwable t) {
            try {
                String d = mc.fpsDebugString;
                if (d != null) {
                    int i = 0, n = d.length();
                    while (i < n && (d.charAt(i) < '0' || d.charAt(i) > '9')) i++;
                    int j = i;
                    while (j < n && d.charAt(j) >= '0' && d.charAt(j) <= '9') j++;
                    if (j > i) return Integer.parseInt(d.substring(i, j));
                }
            } catch (Throwable t2) { /* ignore — 0 is a safe display */ }
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
