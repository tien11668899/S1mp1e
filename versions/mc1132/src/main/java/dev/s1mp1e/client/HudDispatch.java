package dev.s1mp1e.client;

import java.util.List;

import net.minecraft.client.MinecraftClient;

/**
 * The single per-frame HUD dispatch for 1.13.2 — the counterpart of mc1211's
 * {@code HudRenderCallback} loop in {@code S1mp1eClient}, of mc189's
 * {@code HudRenderDispatcher} and of mc1144's {@code HudDispatch}. It iterates
 * {@link ModuleManager#all()} once and, for every enabled module that implements
 * {@link HudRenderer}, calls {@link HudRenderer#renderHud()} inside a
 * {@code try/catch(Throwable)} so one bad module never breaks the HUD pass.
 *
 * <p><b>Why a plain helper, not a Fabric event.</b> There is NO Fabric API at runtime for
 * 1.13.2 in this setup, so {@code HudRenderCallback} does not exist. Fabric's own 1.20.1
 * {@code HudRenderCallback} fires at {@code InGameHud.render} TAIL, so this method is called
 * there directly — as the FIRST statement of the existing {@code InGameHudFadeMixin}
 * render-TAIL handler, BEFORE its {@code currentScreen} early-return, so HUD modules draw
 * under the {@code ScreenFade} dissolve and also while a screen is open (as in the
 * reference). {@code InGameHud.render(F)V} has exactly one return, so TAIL fires once per
 * frame. Vanilla skips {@code InGameHud.render} under F1 only while no screen is open; with a
 * screen open under F1 it IS called, so each module re-guards the F1 flag
 * ({@code Mc1132.hudHidden()}) in its own {@code renderHud()}, as the mc1211 modules do.
 */
public final class HudDispatch {

    private HudDispatch() {}

    /** Draw every enabled {@link HudRenderer} module once, guarding each with try/catch. */
    public static void renderAll() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        List<Module> modules = ModuleManager.all();
        for (int i = 0; i < modules.size(); i++) {
            Module m = modules.get(i);
            if (!m.enabled || !(m instanceof HudRenderer)) continue;
            try {
                ((HudRenderer) m).renderHud();
            } catch (Throwable t) {
                // one bad module never breaks the HUD pass
            }
        }
    }
}
