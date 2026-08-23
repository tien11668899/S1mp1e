package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Recipe result grid cells — the 1.13.2 (Legacy Fabric) port of 26.2's
 * {@code RecipeButtonGlassMixin}. Each result-button slot background becomes a clear
 * refractive-glass cell; craftable cells get the neutral white lift so state still
 * reads, uncraftable cells are plain glass (26.2's design).
 *
 * <h3>1.13.2 recon (obf merged jar + legacyfabric yarn build.604)</h3>
 * Target is the result button, obf {@code cmj} / intermediary {@code class_3285}
 * (UNMAPPED), {@code extends ButtonWidget} ({@code cgu} / {@code class_356}).
 * <ul>
 *   <li>renderButton = obf {@code a(IIF)V} = intermediary {@code method_891} (render name
 *       mapped on {@code ButtonWidget}; the override carries it). Its SOLE
 *       {@code drawTexture(IIIIII)V} INVOKE (offset 276) is the slot background; the
 *       invoke OWNER is {@code class_3285} itself (receiver-type rule), so the
 *       {@code @Redirect} owner is {@code class_3285}. Pre-MatrixStack: {@code (IIF)V}.</li>
 *   <li>Craftable state is read straight from the blit's {@code u} argument — vanilla
 *       computes {@code int u = 29; if (!hasCraftableRecipes) u += 25;}, i.e. {@code u==29}
 *       → craftable, {@code u==54} → not. No {@code @Shadow} needed.</li>
 * </ul>
 * The refractive GLASS program samples the backdrop the recipe book panel already
 * grabbed this frame; if none was grabbed, fall back to the vanilla slot PNG so a cell
 * never vanishes. {@code drawTexture} is PUBLIC inherited from {@code DrawableHelper} —
 * NOT {@code @Shadow}'d; the fallback calls it through the redirect's {@code self} param.
 */
@Mixin(net.minecraft.class_3285.class)
public abstract class RecipeButtonGlassMixin {

    /** 26.2's craftable/selected lift (G=0xD8 -> 1-0xD8/255). */
    private static final float LIFT_CRAFTABLE = 0.153f;

    @Redirect(method = "method_891",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/class_3285;drawTexture(IIIIII)V"))
    private void s1mp1e$glassCell(net.minecraft.class_3285 self,
                                  int x, int y, int u, int v, int w, int h) {
        // Reuse the backdrop the book panel grabbed this frame. If glass is off, or no
        // backdrop is available, keep the vanilla slot PNG so the cell never vanishes.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable()) || !SceneCapture.hasBackdrop()) {
            self.drawTexture(x, y, u, v, w, h);
            return;
        }
        boolean craftable = (u == 29);   // vanilla: u=29 craftable, u=54 uncraftable
        float lift = craftable ? LIFT_CRAFTABLE : 0f;
        GlassRenderer.glass(x, y, x + w, y + h, 6f, 1.0f, lift, 1.0f, GlassRenderer.FROST_NONE);
    }
}
