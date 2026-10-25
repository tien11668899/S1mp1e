package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zoom look-scaling (the Zoomify "silky while zoomed" half): scale this frame's raw mouse movement by
 * the current zoom factor so the on-screen aim speed stays constant as the FOV narrows. Applied at the
 * HEAD of {@code Mouse.updateMouse} (method_1606, verified yarn 1.21.1+build.3) by multiplying the
 * accumulated {@code cursorDeltaX/Y} — purely a proportional scale of the player's OWN mouse input,
 * active only while the zoom is engaged; it never auto-aims and reads no target.
 */
@Mixin(Mouse.class)
public class MouseZoomSensitivityMixin {

    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void s1mp1e$zoomLookScale(double timeDelta, CallbackInfo ci) {
        double f = ZoomModule.lookScale();
        if (f < 0.999) {
            cursorDeltaX *= f;
            cursorDeltaY *= f;
        }
    }
}
