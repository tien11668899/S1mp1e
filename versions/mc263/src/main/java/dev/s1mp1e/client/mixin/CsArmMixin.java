package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.knife.CsArm;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "CS arms everywhere": replaces the vanilla first-person player arm with a CS2 gloved arm. renderRightHand /
 * renderLeftHand is the single geometry entry every first-person arm goes through (empty hand, held items, eating,
 * blocking, two-handed map). The knife viewmodel already cancels submitArmWithItem upstream, so it never reaches here.
 */
@Mixin(AvatarRenderer.class)
public class CsArmMixin {

    // Inside renderPlayerArm (empty hand) the pose here already carries vanilla's equip / swing motion; draw the CS
    // gloved arm from it and skip the player-skin arm. Other callers (maps) keep the vanilla arm.
    @Inject(method = "renderRightHand", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$csRightHand(PoseStack poseStack, SubmitNodeCollector snc, int light, Identifier skin, boolean sleeve, CallbackInfo ci) {
        if (CsArm.renderPlayerArm(HumanoidArm.RIGHT, poseStack, light)) ci.cancel();
    }

    @Inject(method = "renderLeftHand", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$csLeftHand(PoseStack poseStack, SubmitNodeCollector snc, int light, Identifier skin, boolean sleeve, CallbackInfo ci) {
        if (CsArm.renderPlayerArm(HumanoidArm.LEFT, poseStack, light)) ci.cancel();
    }
}
