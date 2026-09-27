package dev.s1mp1e.client;

import java.util.List;

import dev.s1mp1e.client.module.PotionHudModule;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The single per-frame HUD dispatch, the 1.12.2 counterpart of mc1211's
 * {@code HudRenderCallback} loop in {@code S1mp1eClient}: it iterates
 * {@link ModuleManager#all()} once and, for every enabled module that implements
 * {@link HudRenderer}, calls {@link HudRenderer#renderHud()} inside a {@code try/catch}
 * so one bad module never breaks the HUD pass. This keeps the entrypoint agnostic of
 * which HUD modules exist — a new HUD module just implements {@link HudRenderer} and is
 * picked up here, instead of self-subscribing to the Forge overlay event.
 *
 * <p>Anchored on {@code RenderGameOverlayEvent.Post(TEXT)}: {@code GuiIngameForge.renderHUDText}
 * posts it exactly once per overlay pass (unconditionally, even when the F3 text event is
 * cancelled), after the hotbar, the status bars and the XP bar, so HUD elements land on top
 * of the vanilla HUD (including the glass hotbar) without fighting its matrix. The
 * decoration-lift matrix that {@code GlassHudHandler} pushes is only around HEALTH/ARMOR/
 * FOOD/AIR/HEALTHMOUNT/JUMPBAR (and EXPERIENCE is pushed and popped inside its own Pre), so
 * at Post(TEXT) the stack is balanced.
 *
 * <p>1.12.2 also draws vanilla's status-effect icons as a HUD element (top-right,
 * {@code ElementType.POTION_ICONS}, posted AFTER TEXT). When PotionHUD's
 * "Hide vanilla effects" is on, that element is cancelled here — the mc1211
 * {@code InGameHudEffectMixin} counterpart, which is live on this version (on 1.8.9 the
 * setting is inert because 1.8.9 has no HUD effect list).
 */
public final class HudRenderDispatcher {

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getMinecraft();
        // Title screen / no render view: nothing to draw. hideGUI (F1) hides everything
        // but menus. F3 is deliberately NOT gated here — mc1211 draws the HUD with the
        // debug overlay up too. Individual modules re-guard player/world in renderHud().
        if (mc.player == null || mc.world == null) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;

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

    /**
     * Hide vanilla's top-right effect icons while PotionHUD replaces them. Only the
     * element's Pre is cancelled, so GuiIngameForge skips {@code renderPotionEffects}
     * and its Post together; nothing is pushed, so there is nothing to balance.
     */
    @SubscribeEvent
    public void onPotionIconsPre(RenderGameOverlayEvent.Pre event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.POTION_ICONS) return;
        try {
            if (PotionHudModule.replacesVanilla()) event.setCanceled(true);
        } catch (Throwable t) {
            // a failure here only means vanilla's icons stay visible
        }
    }
}
