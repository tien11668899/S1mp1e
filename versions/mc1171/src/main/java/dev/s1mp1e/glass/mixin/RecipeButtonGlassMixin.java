package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Recipe result grid cells — the 1.17.1 Fabric port of 26.2's
 * {@code RecipeButtonGlassMixin}. Each {@link AnimatedResultButton} slot background
 * becomes a clear refractive-glass cell; craftable cells get the neutral white lift
 * so state still reads, uncraftable cells are plain glass (26.2's design: craftable
 * = lifted, uncraftable = flat).
 *
 * <h3>Bytecode recon (yarn 1.17.1+build.65, merged jar)</h3>
 * {@code AnimatedResultButton} ({@code extends ClickableWidget extends DrawableHelper}).
 * {@code renderButton} ({@code (Lnet/minecraft/client/util/math/MatrixStack;IIF)V});
 * its slot-background blit is the sole {@code drawTexture(MatrixStack,IIIIII)} INVOKE
 * (offset 234, constant-pool {@code #231 = AnimatedResultButton.drawTexture:(...)V}) —
 * the invoke OWNER is {@code AnimatedResultButton} itself (javac's receiver-type
 * rule), so the {@code @Redirect} target owner is {@code AnimatedResultButton}.
 *
 * <p>Craftable state is read straight from the blit's {@code u} argument — vanilla
 * computes (verified: offset 37 {@code bipush 29; istore 6}, offset 48-51
 * {@code ifne / iinc 6,25} guarded by {@code hasCraftableRecipes}), i.e.
 * {@code u == 29} → craftable, {@code u == 54} → not. No {@code @Shadow} needed.
 * The blit runs inside {@code renderButton}'s bounce {@code push/scale/translate}
 * (offsets 137-198), so the glass cell inherits the click bounce for free — matching
 * 26.2, whose cell draws through {@code g.pose()} inside the same transform.
 *
 * <h3>Knobs — 26.2 RecipeButtonGlassMixin, byte-for-byte</h3>
 * 26.2 packs {@code cornerRadius 6, col = 0xFFFF0000 | (lifted?0xD8:0xFF)<<8 | 0xFF}:
 * frost none (A=0xFF → {@link GlassRenderer#FROST_NONE}), full corner (R=0xFF →
 * corner scale 1.0), opacity 1.0 (B=0xFF), lift {@code 1-0xD8/255 = 0.153} when
 * craftable else 0. The refractive GLASS program samples the backdrop the recipe
 * book panel already grabbed this frame; if none was grabbed, fall back to the
 * vanilla slot PNG so a cell never vanishes.
 */
@Mixin(AnimatedResultButton.class)
public abstract class RecipeButtonGlassMixin {

    /** 26.2's craftable/selected lift (G=0xD8 -> 1-0xD8/255). */
    private static final float LIFT_CRAFTABLE = 0.153f;

    @Redirect(method = "renderButton",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/recipebook/AnimatedResultButton;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassCell(AnimatedResultButton self, MatrixStack matrices,
                                  int x, int y, int u, int v, int w, int h) {
        // Reuse the backdrop the book panel grabbed this frame. If glass is off, or
        // no backdrop is available, keep the vanilla slot PNG so the cell never
        // vanishes.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable()) || !SceneCapture.hasBackdrop()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
            return;
        }
        boolean craftable = (u == 29);   // vanilla: u=29 craftable, u=54 uncraftable
        float lift = craftable ? LIFT_CRAFTABLE : 0f;
        GlassRenderer.glass(x, y, x + w, y + h, 6f, 1.0f, lift, 1.0f, GlassRenderer.FROST_NONE);
    }
}
