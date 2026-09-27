package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.class_4112;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zoom look-scaling (the Zoomify "silky while zoomed" half), 1.13.2 port: scale this frame's raw mouse
 * movement by the current zoom factor so the on-screen look speed stays constant as the FOV narrows.
 *
 * <p>On 1.13.2 the {@code Mouse} class is UNMAPPED ({@code net.minecraft.class_4112}, the public field
 * {@code MinecraftClient.field_19945}). {@code updateMouse()} is the public {@code method_18239()V}, and
 * the accumulated {@code cursorDeltaX/Y} are the private doubles {@code field_19968/field_19969}: added
 * to in the cursor-position callback {@code method_18246(JDD)V} and consumed (then zeroed) by
 * {@code method_18239} (all javap-verified, legacy yarn 1.13.2+build.604-v2). We multiply them at the
 * HEAD of {@code method_18239} — purely a proportional scale of the player's OWN mouse input, active
 * only while the zoom is engaged; it reads no target and adds no movement of its own.
 */
@Mixin(class_4112.class)
public class MouseZoomSensitivityMixin {

    @Shadow private double field_19968;   // cursorDeltaX
    @Shadow private double field_19969;   // cursorDeltaY

    @Inject(method = "method_18239()V", at = @At("HEAD"))
    private void s1mp1e$zoomLookScale(CallbackInfo ci) {
        try {
            double f = ZoomModule.lookScale();
            if (f < 0.999) {
                field_19968 *= f;
                field_19969 *= f;
            }
        } catch (Throwable ignored) {
            // leave the player's mouse input untouched on any failure
        }
    }
}
