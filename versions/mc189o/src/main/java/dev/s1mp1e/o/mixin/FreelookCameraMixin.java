package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.s1mp1e.o.client.module.FreelookModule;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freelook (ported from 26.3): {@code GameRenderer.render(FJ)} turns the player with
 * {@code player.updateLocalPlayerCamera(dx, dy)} (sensitivity, smooth camera and invert already applied); while freelook
 * holds the camera that delta turns the camera instead and the player's rotation is left exactly as it was.
 * {@code transformCamera(F)} reads the camera entity's yaw / pitch for the third-person orbit (with vanilla's wall
 * raytrace) and for the final view rotation — every read gets the freelook angles, so the camera orbits the player.
 * Render-only.
 */
@Mixin(value = GameRenderer.class, priority = 1100)
public abstract class FreelookCameraMixin {

    @Inject(method = "render(FJ)V", at = @At("HEAD"))
    private void s1mp1e$freelookFrame(float tickDelta, long nanoTime, CallbackInfo ci) {
        FreelookModule.frame();
    }

    @WrapWithCondition(method = "render(FJ)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/entity/living/player/LocalClientPlayerEntity;updateLocalPlayerCamera(FF)V"))
    private boolean s1mp1e$freelookTurn(LocalClientPlayerEntity player, float dx, float dy) {
        if (!FreelookModule.capturing()) return true;
        FreelookModule.turn(dx, dy);
        return false;
    }

    @ModifyExpressionValue(method = "transformCamera", at = {
            @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;yaw:F", opcode = Opcodes.GETFIELD),
            @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;lastYaw:F", opcode = Opcodes.GETFIELD)})
    private float s1mp1e$freelookYaw(float v) {
        return FreelookModule.viewYaw(v);
    }

    @ModifyExpressionValue(method = "transformCamera", at = {
            @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;pitch:F", opcode = Opcodes.GETFIELD),
            @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;lastPitch:F", opcode = Opcodes.GETFIELD)})
    private float s1mp1e$freelookPitch(float v) {
        return FreelookModule.viewPitch(v);
    }
}
