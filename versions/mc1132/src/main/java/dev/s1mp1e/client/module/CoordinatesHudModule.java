package dev.s1mp1e.client.module;

import dev.s1mp1e.client.hud.HudText;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.glass.compat.Mc1132;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

/**
 * A glass coordinates read-out: your X / Y / Z and (optionally) the direction you face. Reads only
 * {@code mc.player}'s own position and facing — information already on your screen — so it is pure
 * fair-play telemetry, never a locator for anything you can't already see.
 *
 * <p>1.13.2 has no {@code DrawContext}; the text sits inside a {@link GlStateManager} matrix (translate +
 * scale) — the mc189 immediate-mode recipe — over a {@link HudGlass#glassBox} background at the same
 * absolute coords, dispatched by {@code HudDispatch} from the {@code InGameHud.render} TAIL. Position
 * comes from the public {@code Entity.x/y/z} doubles and facing from
 * {@code Entity.getHorizontalDirection()} (the 1.13.2 names, javap-verified).
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
    public void renderHud() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || Mc1132.hudHidden()) return;

        int x = MathHelper.floor(mc.player.x);
        int y = MathHelper.floor(mc.player.y);
        int z = MathHelper.floor(mc.player.z);
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
        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) x0, (float) y0, 0f);
            GlStateManager.scale(sc, sc, 1f);
            HudText.draw(l1, PAD, PAD, color.colorValue, true);
            if (l2 != null) HudText.draw(l2, PAD, PAD + LINE, color.colorValue, true);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** Compass facing + the axis it moves you along (matches F3's convention). */
    private static String facingLabel(MinecraftClient mc) {
        Direction d = mc.player.getHorizontalDirection();
        if (d == Direction.NORTH) return "北 (-Z)";
        if (d == Direction.SOUTH) return "南 (+Z)";
        if (d == Direction.EAST)  return "東 (+X)";
        if (d == Direction.WEST)  return "西 (-X)";
        return d == null ? "" : d.getName();
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
