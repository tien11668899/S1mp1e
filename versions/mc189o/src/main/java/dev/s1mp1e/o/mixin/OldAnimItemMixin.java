package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.client.module.OldAnimationsModule;
import net.minecraft.client.render.ItemInHandRenderer;
import net.minecraft.client.render.model.block.ModelTransformations;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7 rod and bow (see {@link OldAnimationsModule} for where the numbers come from). Both act inside
 * {@code ItemInHandRenderer}'s own push/pop around the item, so nothing leaks into the rest of the frame.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class OldAnimItemMixin {

    /** Rod: the 1.7 turn-before-transform, as a translation in front of the 1.8 model transform. */
    @Inject(method = "render(Lnet/minecraft/entity/living/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/block/ModelTransformations$Type;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/ItemRenderer;renderItemInHand(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/living/LivingEntity;Lnet/minecraft/client/render/model/block/ModelTransformations$Type;)V"))
    private void s1mp1e$oldRod(LivingEntity entity, ItemStack stack, ModelTransformations.Type type, CallbackInfo ci) {
        if (stack == null || !OldAnimationsModule.oldRod()) return;
        Item item = stack.getItem();
        if (item != Items.FISHING_ROD && item != Items.CARROT_ON_A_STICK) return;
        if (type == ModelTransformations.Type.FIRST_PERSON) GlStateManager.translatef(0.0F, 0.0F, -0.25F);
        else if (type == ModelTransformations.Type.THIRD_PERSON) GlStateManager.translatef(0.0F, 0.046875F, 0.0F);
    }

    /** Bow: the draw stretch along the 2D item's own frame, as 1.7 did it, instead of the hand frame's Z. */
    @WrapOperation(method = "applyBowNocking",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/platform/GlStateManager;scalef(FFF)V"))
    private void s1mp1e$oldBow(float x, float y, float z, Operation<Void> scale) {
        if (!OldAnimationsModule.oldBow()) {
            scale.call(x, y, z);
            return;
        }
        GlStateManager.rotatef(-335.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotatef(-50.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.translatef(0.0F, 0.5F, 0.0F);
        scale.call(x, y, z);
        GlStateManager.translatef(0.0F, -0.5F, 0.0F);
        GlStateManager.rotatef(50.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotatef(335.0F, 0.0F, 0.0F, 1.0F);
    }
}
