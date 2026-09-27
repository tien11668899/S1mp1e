package dev.s1mp1e.client;

import java.util.List;

import net.minecraft.client.MinecraftClient;

/**
 * The single per-frame HUD dispatch for 1.15.2 — the counterpart of mc1211's
 * {@code HudRenderCallback} loop in {@code S1mp1eClient} and of mc189's
 * {@code HudRenderDispatcher}. It iterates {@link ModuleManager#all()} once and, for
 * every enabled module that implements {@link HudRenderer}, calls
 * {@link HudRenderer#renderHud()} inside a {@code try/catch(Throwable)} so one bad
 * module never breaks the HUD pass.
 *
 * <p><b>Why a plain helper, not a Fabric event.</b> fabric-api 0.28.5+1.15 does ship
 * {@code HudRenderCallback}, but fabric-api is only an optional runtime provider here (not a
 * declared dependency), and the mc1144 line dispatches from its own mixin. Fabric's
 * {@code HudRenderCallback} fires at {@code InGameHud.render} TAIL too, so this method
 * is called there directly — as the FIRST statement of {@code InGameHudMixin}'s existing
 * render-TAIL handler, BEFORE its {@code currentScreen} early-return, so HUD modules draw
 * under the {@code ScreenFade} dissolve and also while a screen is open (as in the
 * reference). Each module re-guards world / hideGUI / currentScreen in its own
 * {@code renderHud()}.
 */
public final class HudDispatch {

    private HudDispatch() {}

    /** Draw every enabled {@link HudRenderer} module once, guarding each with try/catch. */
    public static void renderAll() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
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
