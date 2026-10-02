package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenDissolve;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 1 (screen-change dissolve) — the STARTER half.
 *
 * <p>Forge counterpart: {@code GlassScreenFadeHandler#onGuiOpen(GuiOpenEvent,
 * priority = LOWEST)}. That handler compared {@code mc.currentScreen} (the
 * outgoing screen) against {@code e.getGui()} (the incoming one) and only fired
 * the dissolve on a real change — MC re-sets the SAME screen on
 * a window resize and that must not flash.
 *
 * <p>Fabric target: {@code MinecraftClient.setScreen(Screen)} — intermediary
 * {@code method_1507}, descriptor {@code (Lnet/minecraft/client/gui/screen/Screen;)V}.
 * <b>Signature delta vs 1.16.5:</b> yarn RENAMED this method
 * {@code openScreen -> setScreen} at 1.17 (verified against yarn 1.17.1+build.65;
 * {@code method_1507} is unchanged), so the {@code @Inject method} string is
 * {@code "setScreen"} here where 1.16.5 used {@code "openScreen"}. At
 * {@code @At("HEAD")} the {@code currentScreen} field ({@code field_1755}, still
 * public) holds the OUTGOING screen, so the byte-for-byte equivalent of the Forge
 * guard is {@code this.currentScreen == screen}.
 *
 * <p><b>1.18.2: live.</b> The trigger starts {@link ScreenDissolve} (snapshot of the outgoing frame, 220 ms
 * smoothstep dissolve drawn as the last GUI layer by {@code MinecraftClientTopLayerMixin}, after the toasts).
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientFadeMixin {

    @Shadow public Screen currentScreen;

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void s1mp1e$fadeOnScreenChange(Screen screen, CallbackInfo ci) {
        // resize re-sets the identical instance -> no dissolve (matches Forge)
        if (this.currentScreen == screen) return;
        // The dissolve is live (ScreenDissolve, the core-profile port of 26.2's ScreenTransition). At HEAD the main
        // framebuffer still holds the last finished frame of the outgoing screen — that is what gets snapshot.
        ScreenDissolve.onSetScreen(this.currentScreen, screen);
    }
}
