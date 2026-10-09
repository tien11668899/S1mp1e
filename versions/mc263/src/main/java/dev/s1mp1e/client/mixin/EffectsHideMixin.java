package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.PotionHudModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 port of mc1211's {@code InGameHudEffectMixin}. When {@link PotionHudModule} is drawing effects as clean
 * glass icons+silhouettes, hide vanilla's own top-right status-effect overlay (the grey rounded boxes) by
 * cancelling {@code Hud.extractEffects(GuiGraphicsExtractor, DeltaTracker)} (was
 * {@code InGameHud.renderStatusEffectOverlay}) at HEAD. Verified by javap against the 26.2 client jar:
 * {@code private void extractEffects(GuiGraphicsExtractor, DeltaTracker)}.
 * Cosmetic — removes a duplicate display, changes no game state.
 */
@Mixin(Hud.class)
public class EffectsHideMixin {

    @Inject(method = "extractEffects(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$hideVanillaEffects(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            if (PotionHudModule.replacesVanilla()) ci.cancel();
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("EffectsHide", t);   // on failure vanilla effects stay visible
        }
    }
}
