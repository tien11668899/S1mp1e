package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.module.OldAnimationsModule;
import net.minecraft.client.render.model.ModelPart;
import net.minecraft.client.render.model.entity.HumanoidModel;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7 third-person block, the arm half: 1.8 sets the blocking arm's yaw to -30 degrees (pose 3); the later steps of
 * setupAnimation only add to it, so adding the 30 degrees back at the end leaves exactly what 1.7 drew. PlayerModel
 * copies the arm into the sleeve after this returns, and armour models copy the pose, so all of them follow.
 */
@Mixin(HumanoidModel.class)
public abstract class OldAnimBlockPoseMixin {

    @Shadow public ModelPart rightArm;
    @Shadow public int itemInRightHand;

    @Inject(method = "setupAnimation", at = @At("TAIL"))
    private void s1mp1e$oldBlockPose(float walk, float walkSpeed, float bob, float yaw, float pitch, float scale,
                                     Entity entity, CallbackInfo ci) {
        if (itemInRightHand == 3 && OldAnimationsModule.oldBlock3p()) rightArm.rotationY += (float) (Math.PI / 6);
    }
}
