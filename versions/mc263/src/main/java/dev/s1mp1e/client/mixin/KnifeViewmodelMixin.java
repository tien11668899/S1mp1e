package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.knife.KnifeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Main-hand sword -> CS2 knife viewmodel (vanilla arm+item skipped only when the knife actually drew). 26.3 moved
 * this to the render-state FirstPersonHandsAndItemsRenderer; the local player is taken from Minecraft directly.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class KnifeViewmodelMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$knifeViewmodel(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState handState,
                                       float frameInterp, float xRot,
                                       InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight,
                                       PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
                                       CallbackInfo ci) {
        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (player == null || hand != InteractionHand.MAIN_HAND || !KnifeRenderer.holdingKnife(player)) return;
        if (KnifeRenderer.render(player, itemStack, poseStack, submitNodeCollector, lightCoords)) ci.cancel();
    }

    /** CS arms everywhere: remember this frame's base hand pose (view bob included) for the arm's rest reference. */
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void s1mp1e$csBase(float frameInterp, PoseStack poseStack, SubmitNodeCollector snc,
                               PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState handState, CallbackInfo ci) {
        dev.s1mp1e.client.knife.CsArm.base(poseStack);
    }

    /** Vanilla only draws a first-person arm for an empty hand (and maps); mark that window so CsArmMixin swaps it. */
    @Inject(method = "renderPlayerArm", at = @At("HEAD"))
    private void s1mp1e$csArmIn(PoseStack poseStack, SubmitNodeCollector snc, int light, float inverseArmHeight,
                                float attack, net.minecraft.world.entity.HumanoidArm arm, PlayerRenderState playerState,
                                CallbackInfo ci) {
        dev.s1mp1e.client.knife.CsArm.beginPlayerArm(inverseArmHeight, attack);
    }

    @Inject(method = "renderPlayerArm", at = @At("RETURN"))
    private void s1mp1e$csArmOut(PoseStack poseStack, SubmitNodeCollector snc, int light, float inverseArmHeight,
                                 float attack, net.minecraft.world.entity.HumanoidArm arm, PlayerRenderState playerState,
                                 CallbackInfo ci) {
        dev.s1mp1e.client.knife.CsArm.endPlayerArm();
    }
}
