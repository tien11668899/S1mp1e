package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.SmokeShot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives {@link SmokeShot} once per frame (inert unless {@code S1MP1E_SHOT} is set). */
@Mixin(Minecraft.class)
public abstract class SmokeShotMixin {
    @Inject(method = "runTick", at = @At("TAIL"))
    private void s1mp1e$smokeShot(boolean renderLevel, CallbackInfo ci) {
        SmokeShot.frame((Minecraft) (Object) this);
    }
}
