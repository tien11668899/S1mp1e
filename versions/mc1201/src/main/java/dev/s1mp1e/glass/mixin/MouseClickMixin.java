package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.CpsModule;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the CPS module every hardware mouse press. {@code Mouse.onMouseButton}
 * is the GLFW callback drained once per hardware event, so every press is seen
 * even when several land inside one client tick (polling would drop them). Pure
 * OBSERVATION — never cancels or synthesises input.
 */
@Mixin(Mouse.class)
public class MouseClickMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void s1mp1e$cps(long window, int button, int action, int mods, CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS) CpsModule.recordClick(button);
    }
}
