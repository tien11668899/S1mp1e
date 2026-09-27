package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.OldAnimationsModule;
import net.minecraft.class_4225;
import net.minecraft.client.gui.screen.options.HandOption;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OldAnimations "swing while using" (1.13.2 port). The main-hand swing is frozen while an item is in
 * use purely because the first-person {@code renderFirstPersonItem} takes the use-transform branch and
 * DELIBERATELY skips the swing offset (the swingProgress value itself keeps ticking). We re-invoke the
 * vanilla swing transform (shadowed, so the exact 1.7/1.8 swing math is reproduced) right before the
 * item draw, but ONLY in the frames vanilla dropped it — composing the swing WITH the use pose. Purely
 * visual.
 *
 * <p>1.13.2 specifics (javap-verified, legacy yarn 1.13.2+build.604-v2):
 * <ul>
 *   <li>The first-person renderer is the UNMAPPED {@code net.minecraft.class_4225} (legacy yarn's
 *       {@code HeldItemRenderer} is the GUI item renderer); {@code renderFirstPersonItem} (7 args) is
 *       {@code method_19142}.</li>
 *   <li>The swing offset is the private {@code method_19138(HandOption, float)} (GL rotations by
 *       swingProgress; called once, in the non-use branch). {@code method_19146(HandOption, float)} is
 *       the equip offset, not the swing. {@code HandOption} is 1.13.2's {@code Arm}.</li>
 *   <li>{@code method_19142} draws the item through {@code renderItemFromSide(LivingEntity, ItemStack,
 *       ModelTransformation$Mode, boolean)} = {@code method_19140} at exactly ONE site (there is no
 *       crossbow branch on 1.13.2). The guards below keep it to the frames where vanilla skipped the
 *       swing, so it is never applied twice.</li>
 *   <li>Player API names: {@code isUsingItem} = {@code method_13061()}, {@code getItemUseTimeLeft} =
 *       {@code method_13065()}, {@code getActiveHand} = {@code method_13062()}, and {@code getMainArm}
 *       is the misnamed {@code getDurability()} (returns the {@code HandOption}).</li>
 * </ul>
 */
@Mixin(class_4225.class)
public class HeldItemSwingMixin {

    @Shadow
    private void method_19138(HandOption arm, float swingProgress) {
        throw new AssertionError();   // stub; @Shadow binds to the real private method
    }

    @Inject(
        method = "method_19142(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;F)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/class_4225;method_19140(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformation$Mode;Z)V"))
    private void s1mp1e$swingWhileUsing(AbstractClientPlayerEntity player, float tickDelta, float pitch,
                                        Hand hand, float swingProgress, ItemStack item, float equipProgress,
                                        CallbackInfo ci) {
        try {
            if (!OldAnimationsModule.active()) return;          // module / setting off -> vanilla untouched
            if (hand != Hand.MAIN_HAND) return;                 // main-hand animation only (1.8.9 scope)
            if (swingProgress <= 0.0F) return;                  // no live swing -> nothing to restore
            // Only add the swing in the frames vanilla dropped it (the use branch).
            if (!player.method_13061()) return;                 // isUsingItem
            if (player.method_13065() <= 0) return;             // getItemUseTimeLeft
            if (player.method_13062() != Hand.MAIN_HAND) return; // getActiveHand
            method_19138(player.getDurability(), swingProgress); // getDurability() == getMainArm on 1.13.2
        } catch (Throwable ignored) {
            // vanilla pose on any failure (method_19138 only issues GL rotations inside vanilla's push/pop)
        }
    }
}
