package dev.s1mp1e.client.module;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.glass.compat.Mc1132;
import net.minecraft.client.MinecraftClient;

/**
 * A glass FPS pill so the player doesn't need the full F3 overlay. Reads only
 * {@code MinecraftClient.getCurrentFps()} (static) — pure client telemetry, no game state.
 *
 * <p>1.13.2 has no {@code DrawContext}; the text sits inside a {@link GlStateManager} matrix
 * (translate + scale; the 1.13.2 names are {@code translate(FFF)}/{@code scale(FFF)}) — the mc189
 * immediate-mode recipe — over a REAL liquid-glass {@link HudGlass#glassBox} background drawn at the
 * same absolute coords, dispatched by {@code HudDispatch} from the {@code InGameHud.render} TAIL.
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
        if (mc.player == null || mc.world == null || Mc1132.hudHidden()) return;

        String s = MinecraftClient.getCurrentFps() + (suffix.boolValue ? " FPS" : "");
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
            GlassFont.drawARGB(s, PAD, PAD, color.colorValue, true);
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
