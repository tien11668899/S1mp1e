package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.PanelGhost;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 2 (container glass) — the close-ghost TRIGGER. 1.14.4 tier port of the
 * 1.16.5 mixin.
 *
 * <p>Forge counterpart: {@code GlassContainerHandler#onGuiOpen(GuiOpenEvent)} —
 * when the OUTGOING screen was a container, calls {@link PanelGhost#trigger()} to
 * start fading the remembered panel rect(s) out. The incoming container re-remembers
 * on its next frame and {@code PanelGhost.cancel()}s if it reopens before the ghost
 * finishes (that cancel lives in {@link HandledScreenGlassMixin}).
 *
 * <p>Fabric target: {@code MinecraftClient.openScreen(Screen)} ({@code openScreen(Ldcl;)V},
 * class_310) at {@code @At("HEAD")}, where {@code currentScreen} is still the OUTGOING
 * screen. <b>Tier delta:</b> 1.14.4's container base class is
 * {@code net.minecraft.client.gui.screen.ingame.ContainerScreen} (class_465) — the
 * yarn-1.16.5 {@code HandledScreen} rename had not happened yet — so the outgoing-screen
 * check is {@code instanceof ContainerScreen}.
 *
 * <p>A separate mixin from {@link MinecraftClientFadeMixin} so the two openScreen
 * hooks stay independently toggleable; Mixin merges both injections into the one method.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientContainerGhostMixin {

    @Shadow public Screen currentScreen;

    @Inject(method = "openScreen", at = @At("HEAD"))
    private void s1mp1e$containerCloseGhost(Screen screen, CallbackInfo ci) {
        // Leaving any container screen -> fade its panel(s) out (matches Forge:
        // `if (!(old instanceof GuiContainer)) return; PanelGhost.trigger();`).
        if (this.currentScreen instanceof ContainerScreen) {
            PanelGhost.trigger();
        }
    }
}
