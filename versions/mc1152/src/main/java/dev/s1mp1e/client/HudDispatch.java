package dev.s1mp1e.client;

import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.s1mp1e.client.hud.HudFade;
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

    /**
     * Draw every {@link HudRenderer} module once, guarding each with try/catch.
     *
     * <p><b>Appear / disappear (2026-10-08, ported from mc1165).</b> VISIBILITY (not the raw {@code enabled} flag)
     * decides drawing, so a module switched on fades in and one switched off keeps drawing while it fades out. Each
     * module is scaled about its own {@link HudBounds} centre (0.85 -> 1 ease-out) and {@link HudFade#alpha} is
     * published for the draw helpers ({@code HudText} / {@code HudGlass} / {@code Silhouette}) to multiply. The first
     * sighting (world join) snaps, matching the vanilla HUD.
     *
     * <p><b>1.15.2 delta vs 1.16.5.</b> {@code renderHud()} takes no {@code MatrixStack} (there is none before 1.16),
     * so the centre scale is folded into the {@code RenderSystem} GL model-view instead of a matrix. Everything the
     * module draws under that model-view scales together — text, {@code DrawableHelper} fills AND the raw-GL
     * liquid-glass backgrounds — because the glass shader samples its backdrop by {@code gl_FragCoord} (screen
     * position), so the quad grows out and its refraction stays aligned (better than 1.16.5, where the MatrixStack
     * left glass alpha-only). Each module runs in its own push/pop inside a try/catch so one that throws or leaves the
     * stack unbalanced can't shift the next, and {@link HudFade#alpha} is always restored to 1.
     *
     * <p>{@code HungerSaturationHudModule} / {@code XpFlowHudModule} keep their own {@code if(!enabled) return} and so
     * never fade: they draw through raw fills / sprites that don't honour {@link HudFade#alpha} (same as mc1165).
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
            RenderSystem.pushMatrix();
            try {
                if (vis < 1f && m instanceof HudBounds) {
                    HudBounds hb = (HudBounds) m;
                    float s = HudFade.SCALE_FROM + (1f - HudFade.SCALE_FROM) * HudFade.easeOut(vis);
                    float cx = hb.hudX() + hb.hudW() * 0.5f, cy = hb.hudY() + hb.hudH() * 0.5f;
                    RenderSystem.translatef(cx, cy, 0f);
                    RenderSystem.scalef(s, s, 1f);
                    RenderSystem.translatef(-cx, -cy, 0f);
                }
                HudFade.alpha = vis;
                ((HudRenderer) m).renderHud();
            } catch (Throwable t) {
                // one bad module never breaks the HUD pass
            } finally {
                HudFade.alpha = 1f;
                RenderSystem.popMatrix();
            }
        }
    }
}
