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
 * HandPosition: translates the first-person hand/item render matrix by the per-hand offset inside
 * {@code HeldItemRenderer.renderFirstPersonItem} (the same method {@link HeldItemSwingMixin} hooks), so the
 * offset composes under vanilla's own arm transform. Cosmetic only; never touches reach/hit registration.
 *
 * <p>1.19.2 port: injected right AFTER the method's first (and only) {@code MatrixStack.push()} instead of
 * at HEAD. javap of yarn 1.19.2+build.28: the spyglass early return is at bc 7, {@code push()} at bc 46 and
 * the matching {@code pop()} at bc 1476. A HEAD translate would land on the CALLER's entry, outside that
 * push/pop, so the main-hand offset would leak into the off-hand draw that follows (and into the spyglass
 * path). After the push, the translate lives in vanilla's own entry and is popped with it.
 */
@Mixin(HeldItemRenderer.class)
public class HandPositionMixin {

    @Inject(method = "renderFirstPersonItem",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/util/math/MatrixStack;push()V",
                     ordinal = 0,
                     shift = At.Shift.AFTER))
    private void s1mp1e$handPosition(AbstractClientPlayerEntity player, float tickDelta, float pitch,
                                     Hand hand, float swingProgress, ItemStack item, float equipProgress,
                                     MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                                     CallbackInfo ci) {
        try {
            if (!HandPositionModule.active()) return;
            float[] o = (hand == Hand.MAIN_HAND) ? HandPositionModule.mainOffset() : HandPositionModule.offOffset();
            if (o[0] != 0f || o[1] != 0f || o[2] != 0f) {
                matrices.translate(o[0], o[1], o[2]);
            }
        } catch (Throwable ignored) {
            // no offset on failure
        }
    }
}
