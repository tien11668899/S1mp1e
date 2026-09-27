package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.SteadyFovModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SteadyFOV: forces the player's FOV multiplier to 1.0 while the module is on, so sprinting /
 * speed / slowness / bow-pull no longer zoom the field of view. Purely a render-side FOV change.
 *
 * <p>1.16.5: {@code AbstractClientPlayerEntity.getSpeed()F} IS the FOV multiplier (same intermediary
 * {@code method_3118} as 1.20.1's {@code getFovMultiplier}); its body (flying 1.1×, movement-speed vs
 * walk-speed ratio, bow-pull attenuation) is javap-verified on yarn 1.16.5+build.10. We inject at
 * RETURN and override with 1.0 rather than retargeting {@code GameRenderer.updateMovementFovMultiplier}
 * (that would lose vanilla's lerp and need a private-field write).
 */
@Mixin(AbstractClientPlayerEntity.class)
public class FovMultiplierMixin {

    @Inject(method = "getSpeed", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$steadyFov(CallbackInfoReturnable<Float> cir) {
        try {
            if (SteadyFovModule.active()) cir.setReturnValue(1.0F);
        } catch (Throwable t) {
            // Any failure -> leave the vanilla multiplier untouched.
        }
    }
}
