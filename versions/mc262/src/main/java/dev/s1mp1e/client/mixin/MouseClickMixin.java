package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.CpsModule;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the CPS module every hardware mouse press. In 26.2 the GLFW button callback wraps the raw
 * button/mods into a {@link MouseButtonInfo} record and queues {@code MouseHandler.onButton(long handle,
 * MouseButtonInfo, int action)} once per hardware event, so every press is seen even when several land
 * inside one client tick. Pure OBSERVATION — never cancels or synthesises input.
 */
@Mixin(MouseHandler.class)
public class MouseClickMixin {
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"))
    private void s1mp1e$cps(long handle, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        // 1 == GLFW_PRESS (vanilla's own check in onButton is `action == 1`).
        if (action == 1) CpsModule.recordClick(buttonInfo.button());
    }
}
