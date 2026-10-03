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
 * System 1 (screen-change dissolve) — the STARTER half. 1.13.2 (Legacy Fabric) port
 * of the 1.16.5 mixin of the same name.
 *
 * <p>Forge counterpart: {@code GlassScreenFadeHandler#onGuiOpen(GuiOpenEvent,
 * priority = LOWEST)}. That handler compared {@code mc.currentScreen} (the
 * outgoing screen) against {@code e.getGui()} (the incoming one) and only fired
 * the dissolve on a real change — MC re-sets the SAME screen on
 * a window resize and that must not flash.
 *
 * <h3>1.13.2 tier delta vs the 1.16.5 source</h3>
 * The screen-swap method that later yarn calls {@code setScreen}/{@code openScreen}
 * is, in 1.13.2 yarn, already named {@code setScreen} (intermediary
 * {@code method_2928}, descriptor {@code (Lnet/minecraft/client/gui/screen/Screen;)V}).
 * At {@code @At("HEAD")} the {@code currentScreen} field ({@code field_3816}, still
 * named {@code currentScreen}) holds the OUTGOING screen, so the byte-for-byte
 * equivalent of the Forge guard is {@code this.currentScreen == screen}.
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
