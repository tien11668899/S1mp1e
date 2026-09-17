package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.OldAnimationsModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OldAnimations "swing while using". In 1.21.1 the main-hand swing is frozen while an item
 * is in use purely because {@code HeldItemRenderer.renderFirstPersonItem} takes the
 * use-transform branch and DELIBERATELY skips {@code applySwingOffset} (the swingProgress
 * value itself keeps ticking). We re-invoke the vanilla {@code applySwingOffset} (shadowed,
 * so we reproduce the exact 1.7/1.8 swing math) right before the item draw, but ONLY in the
 * frames vanilla dropped it — composing the swing WITH the use pose. Purely visual.
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemSwingMixin {

    @Shadow
    private void applySwingOffset(MatrixStack matrices, Arm arm, float swingProgress) {
        throw new AssertionError();   // stub; @Shadow binds to the real private method
    }

    @Inject(
        method = "renderFirstPersonItem",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V"))
    private void s1mp1e$swingWhileUsing(AbstractClientPlayerEntity player, float tickDelta, float pitch,
                                        Hand hand, float swingProgress, ItemStack item, float equipProgress,
                                        MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                                        CallbackInfo ci) {
        if (!OldAnimationsModule.active()) return;          // module / setting off -> vanilla untouched
        if (hand != Hand.MAIN_HAND) return;                 // main-hand animation only (1.8.9 scope)
        if (swingProgress <= 0.0F) return;                  // no live swing -> nothing to restore
        // Only add the swing in the frames vanilla dropped it (the use branch).
        if (!player.isUsingItem()) return;
        if (player.getItemUseTimeLeft() <= 0) return;
        if (player.getActiveHand() != Hand.MAIN_HAND) return;
        applySwingOffset(matrices, player.getMainArm(), swingProgress);
    }
}
