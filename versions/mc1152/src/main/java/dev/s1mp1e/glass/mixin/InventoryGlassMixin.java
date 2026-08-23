package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Survival-inventory PNG suppressor (1.15.2 tier — identical seam to 1.14.4).
 * {@code InventoryScreen.drawBackground} paints the container GUI texture (a
 * {@code blit}) AND the rotating player model (a {@code drawEntity} call).
 * {@link HandledScreenGlassMixin} draws the glass panel and then re-runs this
 * {@code drawBackground} so vanilla paints the player in its own correct GL state
 * (reproducing {@code drawEntity} by hand in the glass batch's leftover state made
 * the model vanish). This mixin swallows ONLY the PNG blit, leaving the player draw.
 *
 * <h3>Seam</h3>
 * {@code InventoryScreen.drawBackground} is {@code (FII)V} with a single
 * {@code blit(IIIIII)V} invoke (the PNG) whose owner is {@code InventoryScreen} itself
 * (javac receiver-type rule) — {@code @Redirect} owner is {@code InventoryScreen}.
 * {@code blit} is public (inherited), NEVER {@code @Shadow}'d; the shader-off fallback
 * calls it through the redirect's {@code self} receiver.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryGlassMixin {

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/InventoryScreen;blit(IIIIII)V"))
    private void s1mp1e$suppressInventoryPng(InventoryScreen self,
                                             int x, int y, int u, int v, int w, int h) {
        // Glass on: swallow the PNG (the base already painted the glass panel); the
        // separate drawEntity INVOKE still runs, so the player model survives. Glass
        // off: draw the vanilla PNG so the inventory never goes blank.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            self.blit(x, y, u, v, w, h);
        }
    }
}
