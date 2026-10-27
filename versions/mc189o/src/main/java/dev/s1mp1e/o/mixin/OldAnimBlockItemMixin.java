package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.module.OldAnimationsModule;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.layer.ItemInHandLayer;
import net.minecraft.client.render.model.Model;
import net.minecraft.client.render.model.entity.HumanoidModel;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.living.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7 third-person block, the sword half: 1.7 turned the blocking sword in the hand (in RenderPlayer:
 * {@code translate(.05, 0, -.1), rotate(-50 Y), rotate(-10 X), rotate(-60 Z)}, just before the item's own transform);
 * 1.8 dropped it for the arm yaw that {@link OldAnimBlockPoseMixin} takes back out. Same place here: right before the
 * held item is drawn, inside the layer's push/pop.
 */
@Mixin(ItemInHandLayer.class)
public abstract class OldAnimBlockItemMixin {

    @Shadow @Final private LivingEntityRenderer<?> parent;

    @Inject(method = "render(Lnet/minecraft/entity/living/LivingEntity;FFFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/ItemInHandRenderer;render(Lnet/minecraft/entity/living/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/block/ModelTransformations$Type;)V"))
    private void s1mp1e$oldBlockItem(LivingEntity entity, float walk, float walkSpeed, float tickDelta, float bob,
                                     float yaw, float pitch, float scale, CallbackInfo ci) {
        if (!OldAnimationsModule.oldBlock3p()) return;
        Model model = parent.getModel();
        if (!(model instanceof HumanoidModel) || ((HumanoidModel) model).itemInRightHand != 3) return;
        GlStateManager.translatef(0.05F, 0.0F, -0.1F);
        GlStateManager.rotatef(-50.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotatef(-10.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotatef(-60.0F, 0.0F, 0.0F, 1.0F);
    }
}
