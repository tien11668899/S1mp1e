package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 1 (screen-change dissolve) — the DRAW half, in-world branch. 1.14.4 tier
 * port of the 1.16.5 mixin.
 *
 * <p>Forge counterpart: {@code GlassScreenFadeHandler#onOverlayPost(
 * RenderGameOverlayEvent.Post)} guarded by {@code type == ALL &&
 * currentScreen == null} — when back in the world (no screen), the dissolve is drawn
 * above the HUD and the finished frame snapshotted. Same order, same guard.
 *
 * <p>Fabric target: {@code InGameHud.render(float)} — verified in the 1.14.4 yarn
 * tiny as {@code render(F)V} (class_329). 1.14.4 predates MatrixStack, so the target
 * takes only {@code tickDelta}; the MatrixStack first arg of the 1.16.5 target is
 * dropped from the handler.
 *
 * <p><b>Note:</b> {@code InGameHudMixin} carries a byte-identical render-TAIL fade
 * handler as well (the "EDITED InGameHudMixin" delta). Both are registered exactly
 * as in the 1.16.5 source; {@link ScreenFade#draw()} is a no-op when idle and
 * {@link ScreenFade#captureFrame()} is throttled, so the duplication is faithfully
 * mirrored from 1.16.5.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudFadeMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(float tickDelta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }
}
