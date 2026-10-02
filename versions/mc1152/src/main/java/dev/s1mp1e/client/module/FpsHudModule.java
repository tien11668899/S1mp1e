package dev.s1mp1e.client.module;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.hud.HudText;
import dev.s1mp1e.glass.mixin.MinecraftClientFpsAccessor;
import net.minecraft.client.MinecraftClient;

/**
 * A glass FPS pill so the player doesn't need the full F3 overlay. Reads only the client's own frame
 * rate — pure client telemetry, no game state.
 *
 * <p>1.15.2 has no {@code MinecraftClient.getCurrentFps()}: the value is the private static field
 * {@code currentFps}, read via {@link MinecraftClientFpsAccessor}. If that accessor is unavailable the
 * leading integer of {@code mc.fpsDebugString} is parsed instead, so the readout never breaks the HUD pass.
 *
 * <p>1.15.2 has no {@code DrawContext}; the text sits inside a {@link RenderSystem} matrix
 * (translate + scale) — the mc189 immediate-mode recipe — over a REAL liquid-glass
 * {@link HudGlass#glassBox} background drawn at the same absolute coords, dispatched by
 * {@code InGameHudMixin} at the TAIL of {@code InGameHud.render(float)}.
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
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;

        String s = readFps(mc) + (suffix.boolValue ? " FPS" : "");
        int tw = Math.round(GlassFont.width(s));
        int th = Math.round(GlassFont.height());
        int boxW = tw + PAD * 2, boxH = th + PAD * 2;
        float sc = (float) scale.doubleValue;
        lastW = Math.round(boxW * sc);
        lastH = Math.round(boxH * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        RenderSystem.pushMatrix();
        try {
            RenderSystem.translatef((float) x0, (float) y0, 0f);
            RenderSystem.scalef(sc, sc, 1f);
            HudText.draw(s, PAD, PAD, color.colorValue, true);
        } finally {
            RenderSystem.popMatrix();
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
