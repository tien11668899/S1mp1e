package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.PanelGhost;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 2 (container glass) — the close-ghost TRIGGER.
 *
 * <p>Forge counterpart: {@code GlassContainerHandler#onGuiOpen(GuiOpenEvent)},
 * which — when the OUTGOING screen was a {@code GuiContainer} — calls
 * {@link PanelGhost#trigger()} to start fading the remembered panel rect(s) out,
 * regardless of what the incoming screen is (close to world, or switch to another
 * container). The incoming container re-remembers on its next frame and
 * {@code PanelGhost.cancel()}s if it reopens before the ghost finishes (that
 * cancel lives in {@link HandledScreenGlassMixin}, on the fresh instance's first
 * draw — the 1.17.1 analogue of the Forge handler's {@code curScreen != screen}
 * branch).
 *
 * <p>Fabric target: {@code MinecraftClient.setScreen(Screen)} ({@code method_1507},
 * {@code (Lnet/minecraft/client/gui/screen/Screen;)V}) at {@code @At("HEAD")},
 * where {@code currentScreen} is still the OUTGOING screen. <b>Signature delta vs
 * 1.16.5:</b> yarn renamed {@code openScreen -> setScreen} at 1.17 (verified
 * against yarn 1.17.1+build.65), so the {@code @Inject method} string is
 * {@code "setScreen"} here. A separate mixin from {@link MinecraftClientFadeMixin}
 * so the human can enable the two setScreen hooks independently; Mixin merges both
 * injections into the one method.
 *
 * <p><b>1.17.1 status: FULLY FUNCTIONAL.</b> {@link PanelGhost} is core-legal
 * (already ported) — the close ghost genuinely draws (see
 * {@link InGameHudPanelGhostMixin}); nothing here is stubbed.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientContainerGhostMixin {

    @Shadow public Screen currentScreen;

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void s1mp1e$containerCloseGhost(Screen screen, CallbackInfo ci) {
        // Leaving any HandledScreen -> fade its panel(s) out (matches Forge:
        // `if (!(old instanceof GuiContainer)) return; PanelGhost.trigger();`).
        if (this.currentScreen instanceof HandledScreen) {
            PanelGhost.trigger();
        }
    }
}
