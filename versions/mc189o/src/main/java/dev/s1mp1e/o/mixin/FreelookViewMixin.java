package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s1mp1e.o.client.module.FreelookModule;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Freelook: {@code EntityRenderDispatcher.prepare} takes the camera's facing from the camera entity's yaw / pitch, and
 * name tags (and other billboards) turn to face it. While freelook holds the camera they face the freelook view, not
 * the way the player is looking. Render-only.
 */
@Mixin(value = EntityRenderDispatcher.class, priority = 1100)
public abstract class FreelookViewMixin {

    @ModifyExpressionValue(method = "prepare", at = {
            @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;yaw:F", opcode = Opcodes.GETFIELD),
            @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;lastYaw:F", opcode = Opcodes.GETFIELD)})
    private float s1mp1e$freelookYaw(float v) {
        return FreelookModule.viewYaw(v);
    }

    @ModifyExpressionValue(method = "prepare", at = {
            @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;pitch:F", opcode = Opcodes.GETFIELD),
            @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;lastPitch:F", opcode = Opcodes.GETFIELD)})
    private float s1mp1e$freelookPitch(float v) {
        return FreelookModule.viewPitch(v);
    }
}
