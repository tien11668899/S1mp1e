package dev.s1mp1e.client.mixin;

import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No drop shadow under any text. Vanilla draws every "shadowed" string twice — a dark copy offset by one GUI pixel,
 * then the text — which is a pixel-font habit; under the smooth PingFang face on glass it reads as a smeared double
 * image. Every glyph (and underline / strike-through effect) gets its shadow colour from
 * {@code Font.PreparedTextBuilder.getShadowColor}; 0 means "no shadow", so answering 0 there removes it for the HUD,
 * menus, tooltips, chat and other mods' screens alike. The glowing-sign outline is a separate path and stays.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public abstract class TextShadowMixin {

    @Inject(method = "getShadowColor", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noShadow(Style style, int textColor, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(0);
    }
}
