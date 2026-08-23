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
 * <p>Fabric target: {@code MinecraftClient.openScreen(Screen)} — intermediary
 * {@code method_1507}, descriptor
 * {@code (Lnet/minecraft/client/gui/screen/Screen;)V}. At {@code @At("HEAD")} the
 * {@code currentScreen} field ({@code field_1755}) still holds the OUTGOING
 * screen, so the byte-for-byte equivalent of the Forge guard is
 * {@code this.currentScreen == screen}.
 *
 * <p>1.15.2 delta vs 1.16.5: none — {@code openScreen} carries no
 * {@code MatrixStack} and keeps the same yarn name/descriptor across 1.15.2 and
 * 1.16.5, so this mixin is copied verbatim.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientFadeMixin {

    @Shadow public Screen currentScreen;

    @Inject(method = "openScreen", at = @At("HEAD"))
    private void s1mp1e$fadeOnScreenChange(Screen screen, CallbackInfo ci) {
        // resize re-sets the identical instance -> no dissolve (matches Forge)
        if (this.currentScreen == screen) return;
        ScreenFade.trigger();
    }
}
