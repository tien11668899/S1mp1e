package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.SteadyFovModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SteadyFOV: forces {@code AbstractClientPlayerEntity.getFovMultiplier} (method_3118, verified
 * yarn 1.21.1+build.3) to return 1.0 while the module is on, so sprinting / speed / slowness no
 * longer zoom the field of view. Purely a render-side FOV change.
 */
@Mixin(AbstractClientPlayerEntity.class)
public class FovMultiplierMixin {

    @Inject(method = "getFovMultiplier", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$steadyFov(CallbackInfoReturnable<Float> cir) {
        if (SteadyFovModule.active()) cir.setReturnValue(1.0F);
    }
}
