package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenFade;
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
 * {@link ScreenFade#trigger()} on a real change — MC re-sets the SAME screen on
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
 * <p><b>1.17.1 core-profile status: TRIGGER wired, the dissolve does not render
 * yet.</b> {@link ScreenFade} is STUBBED on 1.17.1, so {@link ScreenFade#trigger()}
 * is a no-op and a screen change is a hard cut. Visual-TODO: core-profile rewrite
 * of ScreenFade (CORE_PROFILE_SPEC §7).
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientFadeMixin {

    @Shadow public Screen currentScreen;

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void s1mp1e$fadeOnScreenChange(Screen screen, CallbackInfo ci) {
        // resize re-sets the identical instance -> no dissolve (matches Forge)
        if (this.currentScreen == screen) return;
        ScreenFade.trigger();
    }
}
