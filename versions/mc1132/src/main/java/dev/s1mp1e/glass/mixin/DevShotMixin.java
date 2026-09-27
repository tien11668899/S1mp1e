package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.DevShot;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Driver for the DEV screenshot harness. Injects at the end of every rendered frame and forwards
 * to {@link DevShot}, which is completely inert unless the {@code S1MP1E_SHOT} (or
 * {@code S1MP1E_AUDIT}) environment variable is set — with both unset this hook does nothing but a
 * cheap early return.
 *
 * <p>1.13.2's {@code MinecraftClient} has no {@code render(Z)V}: the per-frame render method is the
 * unmapped {@code method_18228(Z)V} (it binds the fbo, renders the game, unbinds and blits the fbo
 * to the screen). Hooking it — rather than individual screen {@code render(IIF)V} methods as the v1
 * harness did — is what DevShot v2 needs: it fires on <b>every</b> frame including the in-world
 * frame that has no open screen ({@code world.png}), and other {@code MinecraftClient} mixins in
 * this version already inject by their {@code method_NNNNN} name. At {@code @At("TAIL")} the frame's
 * fbo is fully drawn (the same buffer vanilla's F2 screenshot reads from), so {@link DevShot}
 * captures the real, complete frame.
 */
@Mixin(MinecraftClient.class)
public abstract class DevShotMixin {
    @Inject(method = "method_18228(Z)V", at = @At("TAIL"))
    private void s1mp1e$devShot(boolean tick, CallbackInfo ci) {
        try {
            DevShot.onRenderEnd((MinecraftClient) (Object) this);
        } catch (Throwable ignored) {
            // A screenshot-harness failure must never disturb the frame loop.
        }
    }
}
