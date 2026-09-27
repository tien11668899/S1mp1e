package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.SteadyFovModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SteadyFOV (1.15.2): forces the player's FOV multiplier to 1.0 while the module is on, so
 * sprinting / speed / slowness / flying / bow pull no longer zoom the field of view.
 *
 * <p>On 1.15.2 the FOV multiplier is {@code AbstractClientPlayerEntity.getSpeed()F}
 * (public; javap-verified, yarn 1.15.2+build.17: flying ×1.1, MOVEMENT_SPEED attribute vs walk
 * speed, bow pull ×(1 - 0.15·pull)); its ONLY caller is
 * {@code GameRenderer.updateMovementFovMultiplier()V} (INVOKE @27). It is therefore the exact
 * counterpart of the 1.21.1 reference's {@code getFovMultiplier} hook. Purely a render-side FOV
 * change — movement is untouched.
 */
@Mixin(AbstractClientPlayerEntity.class)
public class FovMultiplierMixin {

    @Inject(method = "getSpeed()F", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$steadyFov(CallbackInfoReturnable<Float> cir) {
        try {
            if (SteadyFovModule.active()) cir.setReturnValue(1.0F);
        } catch (Throwable ignored) {
            // never let a cosmetic FOV tweak break the frame; vanilla value stands
        }
    }
}
