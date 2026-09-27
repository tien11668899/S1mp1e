package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.module.HandPositionModule;
import net.minecraft.class_4225;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HandPosition (1.13.2 port): translates the first-person hand/item by the per-hand offset.
 *
 * <p>Legacy-yarn trap: on 1.13.2 {@code net.minecraft.client.render.item.HeldItemRenderer} is the GUI
 * ITEM renderer; the first-person renderer is the UNMAPPED {@code net.minecraft.class_4225}
 * ({@code MinecraftClient.method_18201()}, {@code GameRenderer.field_20676}). Its per-hand
 * {@code renderFirstPersonItem(AbstractClientPlayerEntity, float, float, Hand, float, ItemStack, float)}
 * is {@code method_19142} — the same method {@link HeldItemSwingMixin} hooks. The full descriptor is
 * spelled out so an overload can never be picked by mistake.
 *
 * <p>1.13.2 has no MatrixStack: the method opens its own {@code GlStateManager.pushMatrix()} (the only
 * one, ordinal 0, offset 36) and pops it right before its single return (exactly 1 push + 1 pop,
 * javap-verified, legacy yarn 1.13.2+build.604-v2). We translate right AFTER that push, so the offset
 * lives inside vanilla's own push/pop and composes under the arm transform — a HEAD translate would leak
 * into the other hand and the rest of the frame. Cosmetic only; never touches reach or hit registration.
 * Both hands (main and off) are adjustable.
 */
@Mixin(class_4225.class)
public class HandPositionMixin {

    @Inject(
        method = "method_19142(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;F)V",
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
                GlStateManager.translate(o[0], o[1], o[2]);
            }
        } catch (Throwable ignored) {
            // vanilla hand position on any failure
        }
    }
}
