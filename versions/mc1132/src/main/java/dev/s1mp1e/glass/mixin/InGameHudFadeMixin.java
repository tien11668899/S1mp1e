package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 1 (screen-change dissolve) — the DRAW half, in-world branch. 1.13.2 (Legacy
 * Fabric) port of the 1.16.5 mixin of the same name.
 *
 * <p>Forge counterpart: {@code GlassScreenFadeHandler#onOverlayPost(
 * RenderGameOverlayEvent.Post)} guarded by {@code type == ALL &&
 * currentScreen == null} — back in the world (no screen), the dissolve is drawn
 * above the HUD and the finished frame snapshotted. Same order and same guard here.
 *
 * <p>In 1.13.2 the hotbar mixin ({@code InGameHudMixin}) carries no in-world fade tail, so
 * this standalone mixin is the sole in-world branch — no double-draw (unlike 1.16.5, which
 * registered both). It is also the ONLY HUD-module dispatcher: its handler calls
 * {@link dev.s1mp1e.client.HudDispatch#renderAll()} first (1.13.2 has no Fabric API, so no
 * {@code HudRenderCallback}).
 *
 * <h3>1.13.2 tier delta vs the 1.16.5 source</h3>
 * Fabric target {@code InGameHud.render}. In 1.13.2 (pre-MatrixStack) its descriptor
 * is {@code (F)V} — just {@code tickDelta} — intermediary {@code method_9420}, named
 * {@code render}, at {@code @At("TAIL")}. {@code ScreenFade} keeps its own captured
 * texture (it does not depend on {@code SceneCapture}). The hotbar mixin grabs the scene
 * at {@code render} HEAD again, which also feeds the close-ghost draw in
 * {@code InGameHudPanelGhostMixin} (a separate render TAIL).
 */
@Mixin(InGameHud.class)
public abstract class InGameHudFadeMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(float tickDelta, CallbackInfo ci) {
        // HUD modules first. There is no Fabric API (no HudRenderCallback) on 1.13.2 here, so this
        // existing render TAIL is the single HUD dispatcher (do not add a second one). It runs BEFORE
        // the currentScreen early-return so the modules also draw while a screen is open and sit under
        // the ScreenFade dissolve, as in the references. Own try/catch: a HUD failure must never skip
        // the fade draw/capture below.
        try {
            dev.s1mp1e.client.HudDispatch.renderAll();
        } catch (Throwable t) {
            // HUD dispatch failure disables the HUD modules for this frame only
        }
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }
}
