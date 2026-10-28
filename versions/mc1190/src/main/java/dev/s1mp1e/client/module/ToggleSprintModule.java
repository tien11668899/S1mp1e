package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.gui.HudText;
import dev.s1mp1e.client.gui.S1mp1eHudEditScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;

/**
 * Toggle sprint / sneak with a glass status pill. This is NOT an input automation of our own: vanilla already ships
 * "Sprint: Toggle" / "Sneak: Toggle" ({@code GameOptions.getSprintToggled/getSneakToggled}), implemented by
 * {@link net.minecraft.client.option.StickyKeyBinding} asking a {@code toggleGetter} supplier on every key edge.
 * {@code ToggleKeyOptionsMixin} widens those two suppliers to "vanilla option OR this module's setting", so the
 * game's own toggle logic does the work and options.txt is never rewritten behind the player's back. The pill only
 * reports the state the key bindings are already in.
 *
 * <p>Turning a toggle off while it is latched would leave the key "down" until the next physical press; {@link #tick()}
 * (end of client tick) releases it on that edge, the same thing vanilla's own option change does.
 */
public final class ToggleSprintModule extends Module implements HudBounds, HudRenderer {

    private static final int PAD = 3;

    private static ToggleSprintModule instance;

    public final Setting sprint = add(Setting.bool("Toggle sprint", true));
    public final Setting sneak  = add(Setting.bool("Toggle sneak", false));
    public final Setting status = add(Setting.bool("Show status", true));
    public final Setting posX   = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY   = add(Setting.integer("Y", 20, 0, 4000));
    public final Setting scale  = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting color  = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting bg     = add(Setting.bool("Background", true));
    public int lastW = 60, lastH = 12;

    private static boolean wasSprintToggle, wasSneakToggle;

    public ToggleSprintModule() { super("ToggleSprint", "HUD"); instance = this; }

    /** Read by the widened {@code sprintKey} supplier on every sprint-key edge. */
    public static boolean sprintToggle() {
        ToggleSprintModule m = instance;
        return m != null && m.enabled && m.sprint.boolValue;
    }

    /** Read by the widened {@code sneakKey} supplier on every sneak-key edge. */
    public static boolean sneakToggle() {
        ToggleSprintModule m = instance;
        return m != null && m.enabled && m.sneak.boolValue;
    }

    /** Client tick: when our toggle switches off (module or setting) and vanilla's own toggle is off too, release a
     *  latched key so the player doesn't keep sprinting / sneaking with nothing held. */
    public static void tick() {
        GameOptions o = MinecraftClient.getInstance().options;
        if (o == null) return;
        boolean sp = sprintToggle(), sn = sneakToggle();
        if (wasSprintToggle && !sp && !o.getSprintToggled().getValue()) o.sprintKey.setPressed(false);
        if (wasSneakToggle && !sn && !o.getSneakToggled().getValue()) o.sneakKey.setPressed(false);
        wasSprintToggle = sp;
        wasSneakToggle = sn;
    }

    /** The pill text for the current state, or null when there is nothing to report. */
    private String label() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null) return null;
        GameOptions o = mc.options;
        // In toggle mode the binding stays down after the key is let go, so "down + toggle mode" reads as latched.
        if (o.sneakKey.isPressed() && (sneakToggle() || o.getSneakToggled().getValue())) return "潛行（切換）";
        if (p.isSneaking()) return "潛行";
        if (o.sprintKey.isPressed() && (sprintToggle() || o.getSprintToggled().getValue())) return "疾跑（切換）";
        if (p.isSprinting()) return "疾跑";
        return null;
    }

    @Override
    public void renderHud(MatrixStack matrices) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!status.boolValue || mc.player == null || mc.options.hudHidden) return;
        String s = label();
        if (s == null) {
            // Nothing to report — except in the HUD layout editor, which needs something to drag.
            if (!(mc.currentScreen instanceof S1mp1eHudEditScreen)) return;
            s = "疾跑（切換）";
        }
        int tw = Math.round(GlassFont.width(s));
        int th = Math.round(GlassFont.height());
        float sc = (float) scale.doubleValue;
        lastW = Math.round((tw + PAD * 2) * sc);
        lastH = Math.round((th + PAD * 2) * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
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

    // ---- HudBounds ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : 60; }
    public int hudH() { return lastH > 0 ? lastH : 12; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }
}
