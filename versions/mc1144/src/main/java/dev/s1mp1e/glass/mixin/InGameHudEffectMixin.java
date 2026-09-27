package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.PotionHudModule;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When {@link PotionHudModule} is drawing effects as clean glass icons+silhouettes, hide vanilla's own
 * top-right status-effect overlay (the grey rounded boxes) by cancelling
 * {@code InGameHud.renderStatusEffectOverlay} at HEAD. On 1.14.4 the method is
 * {@code renderStatusEffectOverlay()V} (protected, no args). Cosmetic — removes a duplicate display,
 * changes no state.
 */
@Mixin(InGameHud.class)
public class InGameHudEffectMixin {

    @Inject(method = "renderStatusEffectOverlay", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$hideVanillaEffects(CallbackInfo ci) {
        if (PotionHudModule.replacesVanilla()) ci.cancel();
    }
}
