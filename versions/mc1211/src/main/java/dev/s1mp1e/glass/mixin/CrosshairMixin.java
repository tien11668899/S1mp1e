package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.module.CrosshairModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla crosshair with {@link CrosshairModule}'s custom shape. Cancels
 * {@code InGameHud.renderCrosshair} at HEAD (method_1736, verified in yarn 1.21.1+build.3)
 * and delegates — inheriting vanilla's screen-open / F1 hiding for free (renderCrosshair
 * isn't reached then), only re-adding the first-person gate that the HEAD-cancel skips.
 */
@Mixin(InGameHud.class)
public class CrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$crosshair(DrawContext ctx, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!(ModuleManager.byName("Crosshair") instanceof CrosshairModule ch) || !ch.enabled) return; // vanilla draws
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (!mc.options.getPerspective().isFirstPerson()) return; // F5: vanilla shows none -> no-op
        ci.cancel();
        int cx = mc.getWindow().getScaledWidth() / 2;
        int cy = mc.getWindow().getScaledHeight() / 2;
        ch.draw(ctx, cx, cy);
    }
}
