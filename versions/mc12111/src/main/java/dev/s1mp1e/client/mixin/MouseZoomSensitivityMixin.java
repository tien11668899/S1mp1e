package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zoom look-scaling (the Zoomify "silky while zoomed" half, 26.2): scale this frame's raw mouse movement by
 * the current zoom factor so the on-screen aim speed stays constant as the FOV narrows.
 *
 * <p>mc1211 hooked HEAD of yarn {@code Mouse.updateMouse(double)}. In 26.2 that method became
 * {@code MouseHandler.handleAccumulatedMovement()}, which also forwards the same deltas to an open screen's
 * {@code mouseDragged}; the player-look half was split into {@code private void turnPlayer(double)}, called
 * only when the mouse is grabbed and a player exists, and {@code accumulatedDX/DY} are zeroed right after it.
 * Hooking HEAD of {@code turnPlayer} therefore scales exactly the camera-turn input (as mc1211 did) without
 * touching GUI drag deltas. Purely a proportional scale of the player's OWN mouse input, active only while
 * the zoom is engaged; it never auto-aims and reads no target.
 */
@Mixin(MouseHandler.class)
public class MouseZoomSensitivityMixin {

    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"))
    private void s1mp1e$zoomLookScale(double mousea, CallbackInfo ci) {
        double f = ZoomModule.lookScale();
        if (f < 0.999) {
            accumulatedDX *= f;
            accumulatedDY *= f;
        }
    }
}
