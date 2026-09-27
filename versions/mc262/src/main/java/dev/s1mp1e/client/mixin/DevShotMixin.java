package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.DevShot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Driver for the DEV screenshot harness. Injects at the end of every rendered frame and forwards to
 * {@link DevShot}, which is completely inert unless {@code S1MP1E_SHOT} / {@code S1MP1E_AUDIT} is set — with both
 * unset this hook does nothing but a cheap early return.
 *
 * <p>26.2's per-frame render method is {@code Minecraft.renderFrame(boolean)} (mojmap; the mc1201 yarn line
 * hooked {@code render(Z)V}). {@code @At("TAIL")} runs after the frame's main render target is fully drawn — the
 * same point vanilla's F2 screenshot reads from — so {@link DevShot} captures the real, complete frame.
 */
@Mixin(Minecraft.class)
public abstract class DevShotMixin {
    @Inject(method = "renderFrame(Z)V", at = @At("TAIL"))
    private void s1mp1e$devShot(boolean tick, CallbackInfo ci) {
        try {
            DevShot.onRenderEnd((Minecraft) (Object) this);
        } catch (Throwable ignored) {
            // A screenshot-harness failure must never disturb the frame loop.
        }
    }
}
