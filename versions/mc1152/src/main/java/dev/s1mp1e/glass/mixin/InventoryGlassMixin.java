package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 *
 * <h3>Pre-dim backdrop grab</h3>
 * The 1.16.5 source grabs the panel's refraction backdrop while it is still
 * <em>world-only</em> — its {@code render} does not dim first; the dim lives inside
 * {@code drawBackground}, which the panel redirect swallows, so the grab sees the
 * bright world and the frosted panel reads bright (like the 1.20.1 reference). In
 * 1.15.2 the shape is different: {@code InventoryScreen.render} runs
 * {@code renderBackground()} (the dark dim) at offset 1, <em>before</em> the panel
 * redirect is ever reached via {@code super.render}. Left alone,
 * {@link HandledScreenGlassMixin}'s {@code SceneCapture.grab()} therefore copies the
 * already-dimmed (dark) world, so the survival-inventory panel came out dark instead
 * of the reference's bright frosted glass. Grabbing a fresh backdrop at the HEAD of
 * {@code render}, before the dim, restores the pre-dim world; the panel's own
 * {@code grab()} then folds onto this copy through {@link SceneCapture}'s 3 ms
 * de-dup guard (both fire in the same sub-millisecond frame), so the panel refracts
 * the bright world exactly as in 1.16.5 / 1.20.1. World-only, so nothing else changes.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryGlassMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$grabPreDimBackdrop(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // Only when the glass pipeline is live; otherwise the vanilla PNG path runs and
        // there is no panel to feed. grabNow() (un-deduped) so this copy is authoritative.
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            SceneCapture.grabNow();
        }
    }

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
