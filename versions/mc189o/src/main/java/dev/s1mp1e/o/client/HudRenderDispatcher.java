package dev.s1mp1e.o.client;

import java.util.List;

import dev.s1mp1e.o.client.hud.HudFade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;
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

        // Appear / disappear (ported from mc189/mc1122): VISIBILITY (not the raw enabled flag) decides drawing, so a
        // module switched on fades in and one switched off keeps drawing while it fades out. Each HudBounds module is
        // scaled about its own centre (0.85 -> 1 ease-out) and HudFade.alpha is published for the draw helpers (HudText
        // / HudGlass / Silhouette) to multiply. The first sighting (world join) snaps, matching the vanilla HUD.
        //
        // 1.8.9 (as on 1.12.2): renderHud() takes no MatrixStack, so the centre scale is folded into the GlStateManager
        // GL model-view. Everything a module draws under it scales together — text, fills AND the raw-GL liquid-glass
        // backgrounds (glass.vsh is gl_ModelViewProjectionMatrix * gl_Vertex and glass.fsh samples its backdrop by
        // gl_FragCoord, so the quad grows out and its refraction stays aligned). Each module runs in its own push/pop
        // inside try/catch so one that throws can't shift the next, and HudFade.alpha is always restored to 1.
        List<Module> modules = ModuleManager.all();
        for (int i = 0; i < modules.size(); i++) {
            Module m = modules.get(i);
            if (!(m instanceof HudRenderer)) continue;
            float vis = HudFade.visibility(m, m.enabled);
            if (vis <= 0.004f) continue;
            GlStateManager.pushMatrix();
            try {
                if (vis < 1f && m instanceof HudBounds) {
                    HudBounds hb = (HudBounds) m;
                    float s = HudFade.SCALE_FROM + (1f - HudFade.SCALE_FROM) * HudFade.easeOut(vis);
                    float cx = hb.hudX() + hb.hudW() * 0.5f, cy = hb.hudY() + hb.hudH() * 0.5f;
                    GlStateManager.translatef(cx, cy, 0f);
                    GlStateManager.scalef(s, s, 1f);
                    GlStateManager.translatef(-cx, -cy, 0f);
                }
                HudFade.alpha = vis;
                ((HudRenderer) m).renderHud();
            } catch (Throwable t) {
                // one bad module never breaks the HUD pass
            } finally {
                HudFade.alpha = 1f;
                GlStateManager.popMatrix();
            }
        }
    }
}
