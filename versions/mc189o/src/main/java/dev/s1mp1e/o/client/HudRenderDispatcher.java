package dev.s1mp1e.o.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import dev.s1mp1e.o.event.RenderGameOverlayEvent;
import dev.s1mp1e.o.event.SubscribeEvent;

/**
 * The single per-frame HUD dispatch, the 1.8.9 counterpart of mc1211's
 * {@code HudRenderCallback} loop in {@code S1mp1eClient}: it iterates
 * {@link ModuleManager#all()} once and, for every enabled module that implements
 * {@link HudRenderer}, calls {@link HudRenderer#renderHud()} inside a {@code try/catch}
 * so one bad module never breaks the HUD pass. This keeps the entrypoint agnostic of
 * which HUD modules exist — a new HUD module just implements {@link HudRenderer} and is
 * picked up here, instead of self-subscribing to the Forge overlay event.
 *
 * <p>Anchored on {@code RenderGameOverlayEvent.Post(TEXT)}: {@code TEXT} is the last
 * non-chat element {@code GuiIngameForge} posts once per frame, so HUD elements land on
 * top of the vanilla HUD (including the glass hotbar) without fighting its matrix. The
 * decoration-lift matrix that {@code GlassHudHandler} pushes is only around HEALTH/ARMOR/
 * FOOD/AIR/EXPERIENCE, so at Post(TEXT) the stack is balanced.
 */
public final class HudRenderDispatcher {

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getInstance();
        // Title screen / no render view: nothing to draw. hideGUI (F1) hides everything
        // but menus. F3 is deliberately NOT gated here — mc1211 draws the HUD with the
        // debug overlay up too. Individual modules re-guard player/world in renderHud().
        if (mc.player == null || mc.world == null) return;
        if (mc.options.hideGui && mc.screen == null) return;

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
