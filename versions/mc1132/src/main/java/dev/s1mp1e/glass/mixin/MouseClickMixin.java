package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.CpsModule;
import net.minecraft.class_4112;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the CPS module every hardware mouse press. On 1.13.2 the {@code Mouse} class is UNMAPPED
 * ({@code net.minecraft.class_4112}, the public field {@code MinecraftClient.field_19945}) and its
 * GLFW mouse-button callback {@code onMouseButton} is the private {@code method_18242(JIII)V}
 * ({@code long window, int button, int action, int mods}; javap-verified against legacy yarn
 * 1.13.2+build.604-v2). It is drained once per hardware event, so every press is seen even when several
 * land inside one client tick (polling would drop them).
 *
 * <p><b>Fair play:</b> pure OBSERVATION of the player's own clicks. It never cancels, injects, delays
 * or schedules a click; the handler only counts a GLFW press. Guarded with try/catch so a failure only
 * loses a CPS sample, never the click.
 */
@Mixin(class_4112.class)
public class MouseClickMixin {
    @Inject(method = "method_18242", at = @At("HEAD"))
    private void s1mp1e$cps(long window, int button, int action, int mods, CallbackInfo ci) {
        try {
            if (action == GLFW.GLFW_PRESS) CpsModule.recordClick(button);
        } catch (Throwable t) {
            // observe-only: a failure never affects the click itself
        }
    }
}
