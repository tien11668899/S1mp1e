package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.HandPositionModule;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HandPosition: translates the first-person hand/item pose by the per-hand offset at the HEAD of
 * {@code ItemInHandRenderer.submitArmWithItem} (26.2 name of 1.21.1's
 * {@code HeldItemRenderer.renderFirstPersonItem}; javap-verified descriptor
 * {@code (AbstractClientPlayer;FFInteractionHand;FItemStack;FPoseStack;SubmitNodeCollector;I)V}),
 * the same method {@link HeldItemSwingMixin} hooks, so the offset composes under vanilla's own arm
 * transform. Cosmetic only; never touches reach/hit registration.
 */
@Mixin(ItemInHandRenderer.class)
public class HandPositionMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void s1mp1e$handPosition(AbstractClientPlayer player, float frameInterp, float xRot,
                                     InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight,
                                     PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
                                     CallbackInfo ci) {
        if (!HandPositionModule.active()) return;
        float[] o = (hand == InteractionHand.MAIN_HAND) ? HandPositionModule.mainOffset() : HandPositionModule.offOffset();
        if (o[0] != 0f || o[1] != 0f || o[2] != 0f) {
            poseStack.translate(o[0], o[1], o[2]);
        }
    }
}
