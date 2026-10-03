package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.client.module.PotionHudModule;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The top-right vanilla status-effect overlay (shown while the inventory is CLOSED).
 *
 * <p>Two independent behaviours, both cosmetic (no state change):
 * <ul>
 *   <li><b>Hide (PotionHUD active).</b> When {@link PotionHudModule} draws the effects itself (its
 *       "Hide vanilla effects" setting), the whole vanilla overlay is cancelled at HEAD so the display is not
 *       duplicated.</li>
 *   <li><b>Glass plate on the box (S9P2).</b> Otherwise the vanilla overlay draws, but every effect's grey
 *       background box is replaced by a liquid-glass plate sitting EXACTLY on vanilla's box — the rect comes
 *       straight from vanilla's own {@code drawTexture} blit arguments ({@code x, y, w=24, h=24}), never a
 *       re-derived offset, so the plate is always concentric with the 18x18 icon vanilla draws at
 *       {@code (x+3, y+3)} for the beneficial row, the harmful row, the ambient variant, at every GUI scale.
 *       The icon is drawn (by vanilla, on top) unchanged.</li>
 * </ul>
 *
 * <p>On 1.13.2 {@code renderStatusEffectOverlay} takes just {@code (MatrixStack)} and blits each 24x24 box with
 * {@code this.drawTexture(MatrixStack, x, y, u, v, 24, 24)} (u,v = 141/166 normal, 165/166 ambient); the icon
 * sprites are drawn afterwards via a deferred pass, so the plates stay behind them. Everything is wrapped so a
 * failure never breaks the vanilla HUD.
 */
@Mixin(InGameHud.class)
public class InGameHudEffectMixin {

    @Inject(method = "method_18363", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$hideVanillaEffects(CallbackInfo ci) {
        try {
            if (PotionHudModule.replacesVanilla()) ci.cancel();
        } catch (Throwable ignored) { }
    }

    /**
     * Replace vanilla's grey 24x24 effect box with a glass plate on the exact same rect (both the normal and
     * the ambient blit route through this one {@code drawTexture(MatrixStack,IIIIII)} call). Reached only when
     * the overlay was NOT cancelled above (PotionHUD not hiding). The rect is taken verbatim from the blit
     * args, so the plate is concentric with the icon at any GUI scale.
     */
    @Redirect(method = "method_18363",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(IIIIII)V"))
    private void s1mp1e$box(InGameHud self, int x, int y, int u, int v, int w, int h) {
        try {
            if (GlassProgram.ensureReady() && GlassProgram.usable()) {
                HudGlass.glassBoxHotbar(x, y, x + w, y + h, 1.0f);
                return;
            }
        } catch (Throwable ignored) { }
        self.drawTexture(x, y, u, v, w, h);   // glass unusable: keep the vanilla box
    }
}
