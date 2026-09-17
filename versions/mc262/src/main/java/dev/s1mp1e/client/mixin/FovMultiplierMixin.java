package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.SteadyFovModule;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SteadyFOV (26.2): forces {@code AbstractClientPlayer.getFieldOfViewModifier(boolean firstPerson,
 * float effectScale)} — the 26.2 successor of yarn's {@code getFovMultiplier}, polled by
 * {@code Camera.tickFov} — to return 1.0 while the module is on, so sprinting / speed / slowness /
 * flying no longer zoom the field of view. Purely a render-side FOV change.
 */
@Mixin(AbstractClientPlayer.class)
public class FovMultiplierMixin {

    @Inject(method = "getFieldOfViewModifier(ZF)F", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$steadyFov(CallbackInfoReturnable<Float> cir) {
        if (SteadyFovModule.active()) cir.setReturnValue(1.0F);
    }
}
