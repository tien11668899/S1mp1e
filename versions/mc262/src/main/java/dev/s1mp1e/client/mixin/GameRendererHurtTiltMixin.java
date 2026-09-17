package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.NoHurtCamModule;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoHurtCam (camera shake): cancels {@code GameRenderer.bobHurt(CameraRenderState, PoseStack)} (26.2
 * name of 1.21.1 yarn {@code tiltViewWhenHurt}; javap-verified, called from both
 * {@code renderItemInHand} and {@code renderLevel}) at HEAD when the setting is on, skipping the
 * whole hurt/death tilt transform. Cosmetic only.
 */
@Mixin(GameRenderer.class)
public class GameRendererHurtTiltMixin {
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noHurtTilt(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (NoHurtCamModule.shakeSuppressed()) ci.cancel();
    }
}
