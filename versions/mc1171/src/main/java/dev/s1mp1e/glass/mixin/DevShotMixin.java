package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.DevShot;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Driver for the DEV screenshot harness. Injects at the end of every rendered frame and forwards
 * to {@link DevShot}, which is completely inert unless the {@code S1MP1E_SHOT} (or {@code S1MP1E_AUDIT})
 * environment variable is set — with both unset this hook does nothing but a cheap early return.
 *
 * <p>{@code render(Z)V} is private on {@code MinecraftClient}; {@code @At("TAIL")} runs after the
 * frame's main framebuffer is fully drawn (the same point vanilla's F2 screenshot reads from), so
 * {@link DevShot} captures the real, complete frame.
 */
@Mixin(MinecraftClient.class)
public abstract class DevShotMixin {
    @Inject(method = "render(Z)V", at = @At("TAIL"))
    private void s1mp1e$devShot(boolean tick, CallbackInfo ci) {
        try {
            DevShot.onRenderEnd((MinecraftClient) (Object) this);
        } catch (Throwable ignored) {
            // A screenshot-harness failure must never disturb the frame loop.
        }
    }
}
