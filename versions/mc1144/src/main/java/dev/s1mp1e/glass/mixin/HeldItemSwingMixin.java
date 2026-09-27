package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.OldAnimationsModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OldAnimations "swing while using" (1.14.4 port). The main-hand swing is frozen while an item is in
 * use purely because {@code HeldItemRenderer.renderFirstPersonItem} takes the use-transform branch and
 * DELIBERATELY skips the swing offset (the swingProgress value itself keeps ticking). We re-invoke the
 * vanilla swing transform (shadowed, so the exact 1.7/1.8 swing math is reproduced) right before the
 * item draw, but ONLY in the frames vanilla dropped it — composing the swing WITH the use pose. Purely
 * visual.
 *
 * <p>1.14.4 specifics (javap-verified, yarn 1.14.4+build.18):
 * <ul>
 *   <li>The swing offset is the unnamed {@code private void method_3217(Arm, float)} (GL rotations by
 *       swingProgress); {@code applyHandOffset(Arm, float)} is the equip offset, not the swing.</li>
 *   <li>The 7-arg {@code renderFirstPersonItem} draws the item through
 *       {@code renderItemFromSide(LivingEntity, ItemStack, ModelTransformation$Type, boolean)} at two
 *       sites (crossbow branch and general branch; only one runs per call). The 3-arg
 *       {@code renderItem} is NOT called there. Both sites are hooked; the guards below keep it to the
 *       frames where vanilla skipped the swing, so it is never applied twice.</li>
 * </ul>
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemSwingMixin {

    @Shadow
    private void method_3217(Arm arm, float swingProgress) {
        throw new AssertionError();   // stub; @Shadow binds to the real private method
    }

    @Inject(
        method = "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;F)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItemFromSide(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformation$Type;Z)V"))
    private void s1mp1e$swingWhileUsing(AbstractClientPlayerEntity player, float tickDelta, float pitch,
                                        Hand hand, float swingProgress, ItemStack item, float equipProgress,
                                        CallbackInfo ci) {
        try {
            if (!OldAnimationsModule.active()) return;          // module / setting off -> vanilla untouched
            if (hand != Hand.MAIN_HAND) return;                 // main-hand animation only (1.8.9 scope)
            if (swingProgress <= 0.0F) return;                  // no live swing -> nothing to restore
            // Only add the swing in the frames vanilla dropped it (the use branch).
            if (!player.isUsingItem()) return;
            if (player.getItemUseTimeLeft() <= 0) return;
            if (player.getActiveHand() != Hand.MAIN_HAND) return;
            method_3217(player.getMainArm(), swingProgress);
        } catch (Throwable ignored) {
            // vanilla pose on any failure (method_3217 only issues GL rotations inside vanilla's push/pop)
        }
    }
}
