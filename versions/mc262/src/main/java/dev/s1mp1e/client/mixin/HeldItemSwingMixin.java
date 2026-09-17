package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.OldAnimationsModule;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OldAnimations "swing while using". The main-hand swing is frozen while an item is in use purely
 * because {@code ItemInHandRenderer.submitArmWithItem} takes the use-transform branch and
 * DELIBERATELY skips the swing transform (the attack value itself keeps ticking). We re-invoke the
 * vanilla {@code applyItemArmAttackTransform} (26.2 name of 1.21.1 yarn {@code applySwingOffset};
 * shadowed, so the exact vanilla swing math) right before the item submit, but ONLY in the frames
 * vanilla dropped it — composing the swing WITH the use pose. Purely visual: it only multiplies the
 * first-person render pose; the player's actual use/attack state is only read, never changed.
 */
@Mixin(ItemInHandRenderer.class)
public class HeldItemSwingMixin {

    @Shadow
    private void applyItemArmAttackTransform(PoseStack poseStack, HumanoidArm arm, float attackValue) {
        throw new AssertionError();   // stub; @Shadow binds to the real private method
    }

    @Inject(
        method = "submitArmWithItem",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void s1mp1e$swingWhileUsing(AbstractClientPlayer player, float frameInterp, float xRot,
                                        InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight,
                                        PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
                                        CallbackInfo ci) {
        if (!OldAnimationsModule.active()) return;          // module / setting off -> vanilla untouched
        if (hand != InteractionHand.MAIN_HAND) return;      // main-hand animation only (1.8.9 scope)
        if (attack <= 0.0F) return;                         // no live swing -> nothing to restore
        // Only add the swing in the frames vanilla dropped it (the use branch).
        if (!player.isUsingItem()) return;
        if (player.getUseItemRemainingTicks() <= 0) return;
        if (player.getUsedItemHand() != InteractionHand.MAIN_HAND) return;
        applyItemArmAttackTransform(poseStack, player.getMainArm(), attack);
    }
}
