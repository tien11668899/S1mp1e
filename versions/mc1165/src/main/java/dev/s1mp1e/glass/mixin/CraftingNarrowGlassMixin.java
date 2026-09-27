package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassBackgroundHost;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Narrow recipe-book layout of {@code CraftingScreen}: when the recipe book is open on a narrow window, 1.16.5's
 * {@code render} (javap-verified) calls {@code this.drawBackground(...)} DIRECTLY (no {@code HandledScreen.render}), so
 * the background skipped the container glass and showed the vanilla PNG behind the book. That call is routed through the
 * same glassed background every container uses ({@link GlassBackgroundHost}, implemented by
 * {@code HandledScreenGlassMixin}: body-PNG blit -> glass, everything else vanilla; vanilla when glass is down).
 */
@Mixin(CraftingScreen.class)
public abstract class CraftingNarrowGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/CraftingScreen;"
                            + "drawBackground(Lnet/minecraft/client/util/math/MatrixStack;FII)V"))
    private void s1mp1e$glassNarrowBackground(CraftingScreen self, MatrixStack matrices, float delta,
                                              int mouseX, int mouseY) {
        ((GlassBackgroundHost) (Object) self).s1mp1e$glassBackground(matrices, delta, mouseX, mouseY);
    }
}
