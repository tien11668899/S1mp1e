package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 1 (screen-change dissolve) — the DRAW half, in-world branch.
 *
 * <p>Forge counterpart: {@code GlassScreenFadeHandler#onOverlayPost(
 * RenderGameOverlayEvent.Post)} guarded by {@code type == ALL &&
 * currentScreen == null} — when the player is back in the world (no screen), the
 * dissolve is drawn above the HUD and the finished frame snapshotted. Same order
 * ({@link ScreenFade#draw()} then {@link ScreenFade#captureFrame()}) and same
 * guard here.
 *
 * <p>Fabric target: {@code InGameHud.render(float)} at {@code @At("TAIL")};
 * intermediary {@code method_1753}, descriptor {@code (F)V}. The
 * {@code currentScreen == null} check keeps this in-world branch and
 * {@link ScreenFadeMixin}'s screen branch mutually exclusive — exactly the two
 * Forge Post handlers.
 *
 * <p><b>1.15.2 delta vs 1.16.5:</b> {@code InGameHud.render} is {@code (F)V} here
 * (the 1.16.5 source targeted {@code (MatrixStack, float)}), so the injected
 * signature drops the {@code MatrixStack}. Nothing else changes.
 *
 * <p>This mirrors the identical render-TAIL fade also carried inside
 * {@link InGameHudMixin}; both are registered exactly as in the 1.16.5 source.
 * {@code ScreenFade.draw()} is a no-op while idle and {@code captureFrame()} is
 * throttled to ~20 Hz, so the second invocation in the same frame captures
 * nothing extra.
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
