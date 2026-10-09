package dev.s1mp1e.client;

import java.util.List;

import com.mojang.blaze3d.platform.GlStateManager;

import dev.s1mp1e.client.hud.HudFade;
import net.minecraft.client.MinecraftClient;

/**
 * The single per-frame HUD dispatch for 1.14.4 — the counterpart of mc1211's
 * {@code HudRenderCallback} loop in {@code S1mp1eClient} and of mc189's
 * {@code HudRenderDispatcher}. It iterates {@link ModuleManager#all()} once and, for
 * every enabled module that implements {@link HudRenderer}, calls
 * {@link HudRenderer#renderHud()} inside a {@code try/catch(Throwable)} so one bad
 * module never breaks the HUD pass.
 *
 * <p><b>Why a plain helper, not a Fabric event.</b> fabric-api 0.28.5+1.14 has no
 * {@code fabric-rendering-v1}, so {@code HudRenderCallback} does not exist. Fabric's own
 * 1.20.1 {@code HudRenderCallback} fires at {@code InGameHud.render} TAIL, so this method
 * is called there directly — as the FIRST statement of {@code InGameHudMixin}'s existing
 * render-TAIL handler, BEFORE its {@code currentScreen} early-return, so HUD modules draw
 * under the {@code ScreenFade} dissolve and also while a screen is open (as in the
 * reference). Each module re-guards world / hideGUI / currentScreen in its own
 * {@code renderHud()}.
 */
public final class HudDispatch {

    private HudDispatch() {}

    /**
     * Draw every {@link HudRenderer} module once, guarding each with try/catch.
     *
     * <p><b>Appear / disappear (2026-10-08, ported from mc1152).</b> VISIBILITY (not the raw {@code enabled} flag)
     * decides drawing, so a module switched on fades in and one switched off keeps drawing while it fades out. Each
     * module is scaled about its own {@link HudBounds} centre (0.85 -> 1 ease-out) and {@link HudFade#alpha} is
     * published for the draw helpers ({@code HudText} / {@code HudGlass} / {@code Silhouette}) to multiply. The first
     * sighting (world join) snaps, matching the vanilla HUD.
     *
     * <p><b>1.14.4 delta.</b> {@code renderHud()} takes no {@code MatrixStack} (there is none before 1.16), so the
     * centre scale is folded into the {@code GlStateManager} GL model-view instead of a matrix. Everything the module
     * draws under that model-view scales together — text, {@code DrawableHelper} fills AND the raw-GL liquid-glass
     * backgrounds — because the glass shader samples its backdrop by {@code gl_FragCoord} (screen position), so the
     * quad grows out and its refraction stays aligned. Each module runs in its own push/pop inside a try/catch so one
     * that throws or leaves the stack unbalanced can't shift the next, and {@link HudFade#alpha} is always restored to 1.
     *
     * <p>{@code HungerSaturationHudModule} / {@code XpFlowHudModule} keep their own {@code if(!enabled) return} and so
     * never fade: they draw through raw fills / sprites that don't honour {@link HudFade#alpha} (same as mc1152).
     */
    public static void renderAll() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
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
