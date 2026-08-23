package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Survival-inventory PNG suppressor. {@code SurvivalInventoryScreen.drawBackground}
 * paints TWO things — the container GUI texture (a {@code drawTexture} blit) and the
 * rotating player model (a STATIC {@code drawEntity} call).
 * {@link HandledScreenGlassMixin} draws the glass panel and then re-runs this
 * {@code drawBackground} so vanilla paints the player in its own correct GL state
 * (reproducing {@code drawEntity} by hand in the glass batch's leftover GlStateManager
 * state made the model vanish whenever the cursor wasn't over a slot). This mixin
 * swallows ONLY the PNG blit, leaving the player draw intact.
 *
 * <h3>Seam — verified from 1.13.2 bytecode (obf merged jar)</h3>
 * The concrete player-inventory screen is {@code SurvivalInventoryScreen} (obf
 * {@code clp} / {@code class_422}) — NOT the abstract {@code InventoryScreen}
 * ({@code clj}), which declares no {@code drawBackground}. {@code SurvivalInventoryScreen}
 * overrides {@code drawBackground} (obf {@code a(FII)V}, yarn {@code drawBackground}
 * inherited from {@code HandledScreen}), whose body issues exactly ONE
 * {@code drawTexture(IIIIII)V} blit ({@code clp.b:(IIIIII)V} = {@code DrawableHelper}'s
 * {@code drawTexture} / {@code method_992}) at offset 47 — the 176x166 container PNG —
 * with INVOKE owner {@code SurvivalInventoryScreen} itself (javac receiver-type rule).
 * The player is drawn by a separate STATIC {@code drawEntity} call
 * ({@code a(IIIFFLafa;)V}, unmapped) at offset 94, which we do NOT touch, so the model
 * survives. Pre-MatrixStack: the blit descriptor is {@code (IIIIII)V} and
 * {@code drawBackground} is {@code (FII)V}.
 *
 * <p>{@code drawTexture} is PUBLIC (inherited from {@code DrawableHelper}), NEVER
 * {@code @Shadow}'d; the shader-off fallback calls it through the redirect's {@code self}
 * receiver (whose static type {@code SurvivalInventoryScreen} publicly inherits it) —
 * mirroring {@code CreativeGlassMixin} / {@code RecipeBookGlassMixin} in this project,
 * which is where the {@code drawTexture} (not {@code blit}) name for 1.13.2 was
 * confirmed.
 */
@Mixin(SurvivalInventoryScreen.class)
public abstract class InventoryGlassMixin {

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/SurvivalInventoryScreen;"
                            + "drawTexture(IIIIII)V"))
    private void s1mp1e$suppressInventoryPng(SurvivalInventoryScreen self,
                                             int x, int y, int u, int v, int w, int h) {
        // Glass on: swallow the PNG (the base already painted the glass panel); the
        // separate static drawEntity INVOKE still runs, so the player model survives.
        // Glass off: draw the vanilla PNG so the inventory never goes blank.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            self.drawTexture(x, y, u, v, w, h);
        }
    }
}
