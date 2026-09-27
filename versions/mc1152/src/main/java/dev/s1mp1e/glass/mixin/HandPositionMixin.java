package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HandPositionModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HandPosition (1.15.2): translates the first-person hand/item by the per-hand offset inside
 * {@code HeldItemRenderer.renderFirstPersonItem(AbstractClientPlayerEntity, float, float, Hand, float,
 * ItemStack, float, MatrixStack, VertexConsumerProvider, int)} — private, a single overload on 1.15.2,
 * the same per-hand method {@link HeldItemSwingMixin} hooks. The full descriptor is always spelled out.
 *
 * <p>The method opens its own {@code matrices.push()} (the first call, ordinal 0, @38) and pops it right
 * before returning (@1479) — javap-verified, yarn 1.15.2+build.17. We translate right AFTER that push, so
 * the offset lives inside vanilla's own push/pop and composes under the arm transform.
 *
 * <p>NOT the 1.16.5 HEAD translate: on 1.15.2 {@code HeldItemRenderer.renderItem(float, MatrixStack,
 * Immediate, ClientPlayerEntity, int)} renders BOTH hands on the same un-pushed stack, so a translate at
 * HEAD would leak the main-hand offset into the off hand. Cosmetic only; never touches reach or hit
 * registration. Both hands (main and off) are adjustable.
 */
@Mixin(HeldItemRenderer.class)
public class HandPositionMixin {

    @Inject(
        method = "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/util/math/MatrixStack;push()V",
                 ordinal = 0,
                 shift = At.Shift.AFTER))
    private void s1mp1e$handPosition(AbstractClientPlayerEntity player, float tickDelta, float pitch,
                                     Hand hand, float swingProgress, ItemStack item, float equipProgress,
                                     MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                                     CallbackInfo ci) {
        try {
            if (!HandPositionModule.active() || matrices == null) return;
            float[] o = (hand == Hand.MAIN_HAND) ? HandPositionModule.mainOffset() : HandPositionModule.offOffset();
            if (o[0] != 0f || o[1] != 0f || o[2] != 0f) {
                matrices.translate(o[0], o[1], o[2]);   // scoped by vanilla's own push/pop
            }
        } catch (Throwable ignored) {
            // vanilla hand position on any failure
        }
    }
}
