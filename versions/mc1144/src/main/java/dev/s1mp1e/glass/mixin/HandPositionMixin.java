package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.module.HandPositionModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HandPosition (1.14.4): translates the first-person hand/item by the per-hand offset inside
 * {@code HeldItemRenderer.renderFirstPersonItem(AbstractClientPlayerEntity, float, float, Hand, float,
 * ItemStack, float)} — the same per-hand method {@link HeldItemSwingMixin} hooks. The full descriptor is
 * mandatory: 1.14.4 overloads the name with {@code renderFirstPersonItem(F)V}.
 *
 * <p>1.14.4 has no MatrixStack: the method opens its own {@code GlStateManager.pushMatrix()} (the first
 * call, ordinal 0) and pops it at its single return (javap-verified, yarn 1.14.4+build.18). We translate
 * right AFTER that push, so the offset lives inside vanilla's own push/pop and composes under the arm
 * transform — a HEAD translate would leak into the other hand and the rest of the frame. Cosmetic only;
 * never touches reach or hit registration. Both hands (main and off) are adjustable on 1.14.4.
 */
@Mixin(HeldItemRenderer.class)
public class HandPositionMixin {

    @Inject(
        method = "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;F)V",
        at = @At(value = "INVOKE",
                 target = "Lcom/mojang/blaze3d/platform/GlStateManager;pushMatrix()V",
                 ordinal = 0,
                 shift = At.Shift.AFTER))
    private void s1mp1e$handPosition(AbstractClientPlayerEntity player, float tickDelta, float pitch,
                                     Hand hand, float swingProgress, ItemStack item, float equipProgress,
                                     CallbackInfo ci) {
        try {
            if (!HandPositionModule.active()) return;
            float[] o = (hand == Hand.MAIN_HAND) ? HandPositionModule.mainOffset() : HandPositionModule.offOffset();
            if (o[0] != 0f || o[1] != 0f || o[2] != 0f) {
                GlStateManager.translatef(o[0], o[1], o[2]);
            }
        } catch (Throwable ignored) {
            // vanilla hand position on any failure
        }
    }
}
