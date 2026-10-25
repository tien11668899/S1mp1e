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
 * HandPosition: translates the first-person hand/item render matrix by the per-hand offset at
 * the HEAD of {@code HeldItemRenderer.renderFirstPersonItem} (method_3228, verified yarn
 * 1.21.1+build.3 — the same method {@link HeldItemSwingMixin} hooks), so the offset composes
 * under vanilla's own arm transform. Cosmetic only; never touches reach/hit registration.
 */
@Mixin(HeldItemRenderer.class)
public class HandPositionMixin {

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"))
    private void s1mp1e$handPosition(AbstractClientPlayerEntity player, float tickDelta, float pitch,
                                     Hand hand, float swingProgress, ItemStack item, float equipProgress,
                                     MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                                     CallbackInfo ci) {
        if (!HandPositionModule.active()) return;
        float[] o = (hand == Hand.MAIN_HAND) ? HandPositionModule.mainOffset() : HandPositionModule.offOffset();
        if (o[0] != 0f || o[1] != 0f || o[2] != 0f) {
            matrices.translate(o[0], o[1], o[2]);
        }
    }
}
