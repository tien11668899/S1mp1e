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
 * System 2 (container glass) — the close-ghost TRIGGER. 1.13.2 (Legacy Fabric) port
 * of the 1.16.5 mixin of the same name.
 *
 * <p>Forge counterpart: {@code GlassContainerHandler#onGuiOpen(GuiOpenEvent)} — when
 * the OUTGOING screen was a {@code GuiContainer} it calls {@link PanelGhost#trigger()}
 * to start fading the remembered panel rect(s) out. The incoming container
 * re-remembers on its next frame and {@code PanelGhost.cancel()}s if it reopens
 * before the ghost finishes (that cancel lives in {@link HandledScreenGlassMixin}).
 *
 * <h3>1.13.2 tier delta vs the 1.16.5 source</h3>
 * The screen-swap target is {@code MinecraftClient.setScreen(Screen)} (yarn already
 * names it {@code setScreen} in 1.13.2; intermediary {@code method_2928}) at
 * {@code @At("HEAD")}, where {@code currentScreen} ({@code field_3816}) still holds
 * the OUTGOING screen. {@code HandledScreen} keeps its class name in 1.13.2 yarn
 * ({@code class_409}). A separate mixin from {@link MinecraftClientFadeMixin} so the
 * two {@code setScreen} hooks can be enabled independently; Mixin merges both
 * injections into the one method.
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
