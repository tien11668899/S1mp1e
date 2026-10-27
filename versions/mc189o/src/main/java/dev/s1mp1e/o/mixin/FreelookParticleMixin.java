package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s1mp1e.o.client.module.FreelookModule;
import net.minecraft.client.render.Camera;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Freelook: {@code Camera.setup} derives the particle billboard axes from the player's yaw / pitch; while freelook
 * holds the camera they come from the freelook view, so particles keep facing the screen. Render-only.
 */
@Mixin(value = Camera.class, priority = 1100)
public abstract class FreelookParticleMixin {

    @ModifyExpressionValue(method = "setup", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/living/player/PlayerEntity;yaw:F", opcode = Opcodes.GETFIELD))
    private static float s1mp1e$freelookYaw(float v) {
        return FreelookModule.viewYaw(v);
    }

    @ModifyExpressionValue(method = "setup", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/living/player/PlayerEntity;pitch:F", opcode = Opcodes.GETFIELD))
    private static float s1mp1e$freelookPitch(float v) {
        return FreelookModule.viewPitch(v);
    }
}
