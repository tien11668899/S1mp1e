package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.class_4218;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom (1.13.2 port): scales the final rendered FOV. The game renderer is UNMAPPED on 1.13.2
 * ({@code net.minecraft.class_4218}, the public field {@code MinecraftClient.field_3818}); its private
 * {@code getFov(float tickDelta, boolean changingFov)} is {@code method_19062(FZ)D} (javap-verified,
 * legacy yarn 1.13.2+build.604-v2). It returns the FOV in degrees; while the zoom key is held we
 * multiply it by the eased factor at RETURN. Render-only.
 *
 * <p>The method has TWO {@code dreturn}s: the early return while {@code field_20661} is set (the
 * panorama / fixed-90° path) and the normal one. {@code @At("RETURN")} covers both, which is harmless:
 * {@link ZoomModule#smoothFactor()} is 1.0 unless the zoom is engaged in-world, and the handler only
 * rewrites the value when the factor is below 1.
 */
@Mixin(class_4218.class)
public class ZoomFovMixin {

    @Unique private static boolean s1mp1e$failed;

    @Inject(method = "method_19062(FZ)D", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$zoom(float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (s1mp1e$failed) return;
        try {
            // Advance + read the eased factor every frame; 1.0 when fully released (no-op).
            double f = ZoomModule.smoothFactor();
            if (f < 0.999) {
                cir.setReturnValue(cir.getReturnValueD() * f);
            }
        } catch (Throwable t) {
            s1mp1e$failed = true;
            System.out.println("[S1mp1e] Zoom: FOV hook disabled after error: " + t);
        }
    }
}
