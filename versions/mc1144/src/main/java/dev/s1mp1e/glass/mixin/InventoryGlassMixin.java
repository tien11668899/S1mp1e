package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Survival-inventory PNG suppressor. {@code InventoryScreen.drawBackground} paints
 * TWO things — the container GUI texture (a {@code blit}) and the rotating player
 * model (a {@code drawEntity} call). {@link HandledScreenGlassMixin} draws the glass
 * panel and then re-runs this {@code drawBackground} so vanilla paints the player in
 * its own correct GL state (reproducing {@code drawEntity} by hand in the glass batch's
 * leftover GlStateManager state made the model vanish whenever the cursor wasn't over a
 * slot). This mixin swallows ONLY the PNG blit, leaving the player draw intact.
 *
 * <h3>Seam (1.14.4 bytecode)</h3>
 * {@code InventoryScreen} (obf {@code deb} / {@code class_490}); {@code drawBackground}
 * is {@code (FII)V} with a single {@code blit(IIIIII)V} invoke (the PNG) whose owner is
 * {@code InventoryScreen} itself (javac receiver-type rule) — so the {@code @Redirect}
 * target owner is {@code InventoryScreen}, not {@code DrawableHelper}. {@code blit} is
 * public (inherited), NEVER {@code @Shadow}'d; the shader-off fallback calls it through
 * the redirect's {@code self} receiver.
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
