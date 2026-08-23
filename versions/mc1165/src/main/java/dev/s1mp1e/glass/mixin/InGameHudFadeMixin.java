package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
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
 * and same guard here.
 *
 * <p>Fabric target: {@code InGameHud.render(MatrixStack, float)} at
 * {@code @At("TAIL")}; intermediary {@code method_1753}, descriptor
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;F)V}. {@code InGameHud.render}
 * fires once per frame the world is shown (and never while a screen suppresses the
 * HUD path enough to matter), so the {@code currentScreen == null} check keeps this
 * branch and {@link ScreenFadeMixin}'s screen branch mutually exclusive — exactly
 * the two Forge Post handlers.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudFadeMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$fadeDrawHud(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (MinecraftClient.getInstance().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }
}
