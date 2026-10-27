package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.HudBounds;
import dev.s1mp1e.o.client.HudRenderer;
import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import dev.s1mp1e.o.client.gui.GlassFont;
import dev.s1mp1e.o.client.gui.S1mp1eHudEditScreen;
import dev.s1mp1e.o.mixin.KeyBindingAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.render.platform.GlStateManager;

/**
 * Toggle sprint / sneak with a glass status pill (ported from 26.3). 26.3 rides vanilla's own "Sprint: Toggle" /
 * "Sneak: Toggle" options; 1.8.9 has none, so the toggle lives here: each press of the sprint or sneak key flips a
 * latch, and while a key is latched {@code KeyBindingMixin} reports it held. Vanilla's own sprint
 * ({@code LocalClientPlayerEntity}) and sneak ({@code KeyboardInput}) logic then does the rest exactly as if the key
 * were held down — sprint still needs forward movement and food, sneak still slows you. options.txt is never touched.
 *
 * <p>The press edge is read from the binding's raw state ({@link KeyBindingAccessor}), which vanilla only updates
 * in-world, so typing in chat or a sign never flips a latch. Switching the setting or the module off, or leaving the
 * world, releases the latch.
 */
public final class ToggleSprintModule extends Module implements HudBounds, HudRenderer {

    private static final int PAD = 3;

    private static ToggleSprintModule instance;

    public final Setting sprint = add(Setting.bool("Toggle sprint", true));
    public final Setting sneak  = add(Setting.bool("Toggle sneak", false));
    public final Setting status = add(Setting.bool("Show status", true));
    public final Setting posX   = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY   = add(Setting.integer("Y", 34, 0, 4000));   // below 1.8.9's FPS and coordinate pills
    public final Setting scale  = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting color  = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting bg     = add(Setting.bool("Background", true));
    public int lastW = 60, lastH = 12;

    private static boolean sprintLatched, sneakLatched, wasSprintDown, wasSneakDown;

    public ToggleSprintModule() { super("ToggleSprint", "HUD"); instance = this; }

    private static boolean sprintToggle() {
        ToggleSprintModule m = instance;
        return m != null && m.enabled && m.sprint.boolValue;
    }

    private static boolean sneakToggle() {
        ToggleSprintModule m = instance;
        return m != null && m.enabled && m.sneak.boolValue;
    }

    /** Client tick (MinecraftMixin): a press edge of the physical key flips its latch. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        GameOptions o = mc.options;
        if (o == null || mc.player == null || mc.world == null) {
            sprintLatched = sneakLatched = wasSprintDown = wasSneakDown = false;
            return;
        }
        boolean sp = ((KeyBindingAccessor) o.sprintKey).s1mp1e$rawPressed();
        boolean sn = ((KeyBindingAccessor) o.sneakKey).s1mp1e$rawPressed();
        if (!sprintToggle()) sprintLatched = false;
        else if (sp && !wasSprintDown) sprintLatched = !sprintLatched;
        if (!sneakToggle()) sneakLatched = false;
        else if (sn && !wasSneakDown) sneakLatched = !sneakLatched;
        wasSprintDown = sp;
        wasSneakDown = sn;
    }

    /** Asked by {@code KeyBindingMixin} on every {@code isPressed()}: a latched sprint / sneak key reads as held. */
    public static boolean held(KeyBinding kb) {
        if (!sprintLatched && !sneakLatched) return false;
        GameOptions o = Minecraft.getInstance().options;
        return o != null && ((sprintLatched && kb == o.sprintKey) || (sneakLatched && kb == o.sneakKey));
    }

    /** The pill text for the current state, or null when there is nothing to report. */
    private static String label() {
        LocalClientPlayerEntity p = Minecraft.getInstance().player;
        if (p == null) return null;
        if (sneakLatched) return "潛行（切換）";
        if (p.isSneaking()) return "潛行";
        if (sprintLatched) return "疾跑（切換）";
        if (p.isSprinting()) return "疾跑";
        return null;
    }

    @Override
    public void renderHud() {
        // visibility (incl. the fade-out after switching off) is decided by HudRenderDispatcher via HudFade
        if (!status.boolValue) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (mc.options.hideGui && mc.screen == null) return;
        String s = label();
        if (s == null) {
            // nothing to report — except in the HUD layout editor, which needs something to drag
            if (!(mc.screen instanceof S1mp1eHudEditScreen)) return;
            s = "疾跑（切換）";
        }
        int tw = Math.round(GlassFont.width(s));
        int th = Math.round(GlassFont.height());
        float sc = (float) scale.doubleValue;
        lastW = Math.round((tw + PAD * 2) * sc);
        lastH = Math.round((th + PAD * 2) * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + lastW, y0 + lastH, 0.85f);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translatef((float) x0, (float) y0, 0f);
            GlStateManager.scalef(sc, sc, 1f);
            dev.s1mp1e.o.client.hud.HudText.draw(s, PAD, PAD, color.colorValue, true);
        } finally {
            GlStateManager.popMatrix();
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
