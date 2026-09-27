package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.SteadyFovModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SteadyFOV: forces {@code AbstractClientPlayerEntity.getFovMultiplier} (public float getFovMultiplier(),
 * javap-verified in yarn 1.19.2+build.28) to return 1.0 while the module is on, so sprinting / speed /
 * slowness no longer zoom the field of view. Purely a render-side FOV change.
 *
 * <p>The target class only loads on world join, so a bad target would only surface there; the dev
 * preload list in the entrypoint should force-load it at startup.
 */
@Mixin(AbstractClientPlayerEntity.class)
public class FovMultiplierMixin {

    @Inject(method = "getFovMultiplier", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$steadyFov(CallbackInfoReturnable<Float> cir) {
        try {
            if (SteadyFovModule.active()) cir.setReturnValue(1.0F);
        } catch (Throwable ignored) {
            // never let a cosmetic FOV tweak break the player tick / render
        }
    }
}
