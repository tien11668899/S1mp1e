package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.PotionHudModule;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When {@link PotionHudModule} is drawing effects as clean icons+silhouettes, hide vanilla's own
 * top-right status-effect overlay (the grey rounded boxes) by cancelling
 * {@code InGameHud.renderStatusEffectOverlay} at HEAD. On 1.13.2 that method is UNMAPPED:
 * {@code protected void method_18363()} (no args), invoked exactly once from {@code render(F)V}
 * (javap-verified against legacy yarn 1.13.2+build.604-v2; it draws the inventory.png effect icons).
 * Cosmetic — removes a duplicate display, changes no state. Guarded: if the gate throws, vanilla draws.
 */
@Mixin(InGameHud.class)
public class InGameHudEffectMixin {

    @Inject(method = "method_18363", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$hideVanillaEffects(CallbackInfo ci) {
        try {
            if (PotionHudModule.replacesVanilla()) ci.cancel();
        } catch (Throwable t) {
            // failure leaves the vanilla overlay visible
        }
    }
}
