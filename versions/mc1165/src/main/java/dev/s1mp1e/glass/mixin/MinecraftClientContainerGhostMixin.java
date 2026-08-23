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
 * draw — the 1.16.5 analogue of the Forge handler's {@code curScreen != screen}
 * branch).
 *
 * <p>Fabric target: {@code MinecraftClient.openScreen(Screen)} ({@code method_1507},
 * {@code (Lnet/minecraft/client/gui/screen/Screen;)V}) at {@code @At("HEAD")},
 * where {@code currentScreen} is still the OUTGOING screen. A separate mixin from
 * {@link MinecraftClientFadeMixin} so the human can enable the two openScreen
 * hooks independently; Mixin merges both injections into the one method.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientContainerGhostMixin {

    @Shadow public Screen currentScreen;

    @Inject(method = "openScreen", at = @At("HEAD"))
    private void s1mp1e$containerCloseGhost(Screen screen, CallbackInfo ci) {
        // Leaving any HandledScreen -> fade its panel(s) out (matches Forge:
        // `if (!(old instanceof GuiContainer)) return; PanelGhost.trigger();`).
        if (this.currentScreen instanceof HandledScreen) {
            PanelGhost.trigger();
        }
    }
}
