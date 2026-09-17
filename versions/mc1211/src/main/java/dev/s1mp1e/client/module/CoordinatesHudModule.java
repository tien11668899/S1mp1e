package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Direction;

/**
 * A glass coordinates read-out: your X / Y / Z and (optionally) the direction you face. Reads only
 * {@code mc.player}'s own position and facing — information already on your screen — so it is pure
 * fair-play telemetry, never a locator for anything you can't already see.
 *
 * <p>Background is REAL liquid glass ({@link HudGlass#glassBox}); text sits on top through the ctx matrix,
 * which also carries the scale, so the two stay aligned in the HUD pass (both identity-based).
 */
public final class CoordinatesHudModule extends Module implements HudBounds, HudRenderer {

    private static final int PAD = 3, LINE = 10;

    public final Setting posX   = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY   = add(Setting.integer("Y", 20, 0, 4000));
    public final Setting scale  = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting color  = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting facing = add(Setting.bool("Show facing", true));
    public final Setting bg     = add(Setting.bool("Background", true));
    public int lastW = 60, lastH = 22;

    public CoordinatesHudModule() { super("CoordsHUD", "HUD"); this.enabled = false; }

    @Override
    public void renderHud(DrawContext ctx) {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        int x = (int) Math.floor(mc.player.getX());
        int y = (int) Math.floor(mc.player.getY());
        int z = (int) Math.floor(mc.player.getZ());
        String l1 = "X " + x + "   Y " + y + "   Z " + z;
        String l2 = facing.boolValue ? ("面向 " + facingLabel(mc)) : null;

        int tw = Math.round(GlassFont.width(l1));
        if (l2 != null) tw = Math.max(tw, Math.round(GlassFont.width(l2)));
        int th = Math.round(GlassFont.height());
        int rows = (l2 != null) ? 2 : 1;
        int boxW = tw + PAD * 2;
        int boxH = th + (rows - 1) * LINE + PAD * 2;
        float sc = (float) scale.doubleValue;
        lastW = Math.round(boxW * sc);
        lastH = Math.round(boxH * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
        if (bg.boolValue) HudGlass.glassBox(ctx, x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        ctx.getMatrices().push();
        try {
            ctx.getMatrices().translate(x0, y0, 0f);
            ctx.getMatrices().scale(sc, sc, 1f);
            GlassFont.drawARGB(ctx, l1, PAD, PAD, color.colorValue, true);
            if (l2 != null) GlassFont.drawARGB(ctx, l2, PAD, PAD + LINE, color.colorValue, true);
        } finally {
            ctx.getMatrices().pop();
        }
    }

    /** Compass facing + the axis it moves you along (matches F3's convention). */
    private static String facingLabel(MinecraftClient mc) {
        Direction d = mc.player.getHorizontalFacing();
        switch (d) {
            case NORTH: return "北 (-Z)";
            case SOUTH: return "南 (+Z)";
            case EAST:  return "東 (+X)";
            case WEST:  return "西 (-X)";
            default:    return d.getName();
        }
    }

    // ---- HudBounds ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : 60; }
    public int hudH() { return lastH > 0 ? lastH : 22; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }
}
