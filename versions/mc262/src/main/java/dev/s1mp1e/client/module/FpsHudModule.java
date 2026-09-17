package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eHudCtx;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A glass FPS pill so the player doesn't need the full debug overlay. Reads only {@code Minecraft.getFps()} —
 * pure client telemetry, no game state.
 *
 * <p>26.2 port of mc1211's module: {@code DrawContext} → {@link GuiGraphicsExtractor} (via
 * {@link S1mp1eHudCtx}), the 4×4 matrix stack → the 2D {@code g.pose()} (no z), and mc1211's custom
 * {@code GlassFont} → the vanilla {@link Font} ({@code width}/{@code lineHeight}/{@code g.text}). F1 is
 * handled once by {@code HudDriverMixin}, so there is no per-module hidden-HUD check.
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
    public void renderHud(S1mp1eHudCtx c) {
        if (!enabled) return;
        GuiGraphicsExtractor g = c.g();
        Font font = c.font();

        String s = Minecraft.getInstance().getFps() + (suffix.boolValue ? " FPS" : "");
        int tw = font.width(s);
        int th = font.lineHeight;
        int boxW = tw + PAD * 2, boxH = th + PAD * 2;
        float sc = (float) scale.doubleValue;
        lastW = Math.round(boxW * sc);
        lastH = Math.round(boxH * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
        // Glass background at absolute coords; text on top under the pose (which also carries the scale).
        // Both are enqueued into the same frame, glass first, so the text composites over the glass.
        if (bg.boolValue) HudGlass.glassBox(g, x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        g.pose().pushMatrix();
        try {
            g.pose().translate(x0, y0);
            g.pose().scale(sc, sc);
            g.text(font, s, PAD, PAD, color.colorValue, true);
        } finally {
            g.pose().popMatrix();
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
