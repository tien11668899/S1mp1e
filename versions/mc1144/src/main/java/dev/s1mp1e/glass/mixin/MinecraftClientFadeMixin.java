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
 * System 1 (screen-change dissolve) — the STARTER half. 1.14.4 tier port of the
 * 1.16.5 mixin; behaviour byte-for-byte identical (calls the same {@link ScreenFade}
 * core in the same order).
 *
 * <p>Forge counterpart: {@code GlassScreenFadeHandler#onGuiOpen(GuiOpenEvent,
 * priority = LOWEST)} — compared {@code mc.currentScreen} (outgoing) against the
 * incoming screen and only fired {@link ScreenFade#trigger()} on a real change (MC
 * re-sets the SAME screen on a window resize, which must not flash).
 *
 * <p>Fabric target: {@code MinecraftClient.openScreen(Screen)} — verified in the
 * 1.14.4 yarn tiny as {@code openScreen(Ldcl;)V} (class_310, unchanged from 1.16.5).
 * At {@code @At("HEAD")} the {@code currentScreen} field ({@code Ldcl;}) still holds
 * the OUTGOING screen, so the guard is {@code this.currentScreen == screen}. No
 * capture happens here on purpose. No MatrixStack / RenderSystem involved at this
 * tier, so nothing else changes.
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
