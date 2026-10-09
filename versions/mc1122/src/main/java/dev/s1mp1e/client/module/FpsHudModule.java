package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;

/**
 * A glass FPS pill so the player doesn't need the full F3 overlay. Reads only
 * {@link Minecraft#getDebugFPS()} — pure client telemetry, no game state.
 *
 * <p>Ported from mc1211's {@code FpsHudModule}; 1.12.2 is immediate-mode so the text
 * is drawn inside a {@link GlStateManager} matrix (translate + scale) rather than a
 * {@code DrawContext}, exactly like {@link ArmorHudModule}. The background is REAL
 * liquid glass via {@link HudGlass#glassBox} at absolute coords, so it lines up with
 * the text drawn at the same coords in the HUD pass.
 */
public final class FpsHudModule extends Module implements HudBounds, HudRenderer {

    private static final int PAD = 3;

    public final Setting posX   = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY   = add(Setting.integer("Y", 4, 0, 4000));
    public final Setting scale  = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting color  = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting suffix = add(Setting.bool("Show 'FPS'", true));
    public final Setting bg     = add(Setting.bool("Background", true));
    public int lastW = 24, lastH = 12;

    public FpsHudModule() { super("FpsHUD", "HUD"); this.enabled = true; }

    @Override
    public void renderHud() {
        // visibility (incl. the fade-out after switching off) is decided by HudRenderDispatcher via HudFade; no enabled-guard
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;

        String s = Minecraft.getDebugFPS() + (suffix.boolValue ? " FPS" : "");
        int tw = Math.round(GlassFont.width(s));
        int th = Math.round(GlassFont.height());
        int boxW = tw + PAD * 2, boxH = th + PAD * 2;
        float sc = (float) scale.doubleValue;
        lastW = Math.round(boxW * sc);
        lastH = Math.round(boxH * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) x0, (float) y0, 0f);
            GlStateManager.scale(sc, sc, 1f);
            dev.s1mp1e.client.hud.HudText.draw(s, PAD, PAD, color.colorValue, true);
        } finally {
            GlStateManager.popMatrix();
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
