package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.screen.recipebook.AnimatedResultButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Recipe result grid cells — the 1.14.4 tier port of 26.2's
 * {@code RecipeButtonGlassMixin}. Each {@link AnimatedResultButton} slot background
 * becomes a clear refractive-glass cell; craftable cells get the neutral white lift
 * so state still reads, uncraftable cells are plain glass.
 *
 * <h3>Bytecode recon (yarn 1.14.4+build.18, merged jar)</h3>
 * {@code AnimatedResultButton} (obf {@code dfb} / {@code class_514},
 * {@code extends AbstractButtonWidget extends DrawableHelper}).
 * {@code renderButton} = {@code (IIF)V} (NO MatrixStack); its slot-background blit is
 * the sole {@code blit(IIIIII)V} INVOKE (offset 217), OWNER
 * {@code AnimatedResultButton} itself (javac's receiver-type rule), so the
 * {@code @Redirect} target owner is {@code AnimatedResultButton}.
 *
 * <p>Craftable state is read straight from the blit's {@code u} argument — vanilla
 * computes {@code int u = 29; if (!results.hasCraftableRecipes()) u += 25;} (verified
 * at offsets 38-54: {@code bipush 29}; if {@code results.b()} is false {@code iinc
 * 5,25}), i.e. {@code u == 29} → craftable, {@code u == 54} → not. No {@code @Shadow}
 * needed. The blit runs inside {@code renderButton}'s bounce
 * {@code pushMatrix/scalef}, so the glass cell inherits the click bounce for free.
 *
 * <h3>Knobs — 26.2 RecipeButtonGlassMixin, byte-for-byte</h3>
 * frost none, full corner, opacity 1.0, lift {@code 1-0xD8/255 = 0.153} when
 * craftable else 0. Falls back to the vanilla slot PNG when glass is off or no
 * backdrop was grabbed so a cell never vanishes.
 */
@Mixin(AnimatedResultButton.class)
public abstract class RecipeButtonGlassMixin {

    /** 26.2's craftable/selected lift (G=0xD8 -> 1-0xD8/255). */
    private static final float LIFT_CRAFTABLE = 0.153f;

    // ---- (E) cascade scale-in (RecipeCascade) ---------------------------------------------------------------------
    // 1.14.4: a recipe button's item icon is a GUI item model, which reads the GlStateManager model-view (vanilla's own
    // "bounce" scales the button through that stack too), and the raw-GL glass cell is drawn under the same
    // model-view — so ONE model-view scale about the button centre grows the glass cell and the icon together.
    // s < 0 (not born yet) cancels the whole renderButton so nothing draws until the button's turn.
    @org.spongepowered.asm.mixin.Unique private boolean s1mp1e$cascadePosed;

    @org.spongepowered.asm.mixin.injection.Inject(method = "renderButton", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$cascadeBegin(int mouseX, int mouseY, float delta,
                                     org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        s1mp1e$cascadePosed = false;
        float s = dev.s1mp1e.glass.render.RecipeCascade.scale(this);
        if (s < 0f) { ci.cancel(); return; }        // not born yet: draw nothing
        if (s >= 1f) return;                         // settled
        AnimatedResultButton self = (AnimatedResultButton) (Object) this;
        float cx = self.x + self.getWidth() / 2f, cy = self.y + ((ClickableWidgetAccessor) (Object) self).s1mp1e$getHeight() / 2f;
        com.mojang.blaze3d.platform.GlStateManager.pushMatrix();          // 1.14.4: the fixed-function model-view
        com.mojang.blaze3d.platform.GlStateManager.translatef(cx, cy, 0f);
        com.mojang.blaze3d.platform.GlStateManager.scalef(s, s, 1f);
        com.mojang.blaze3d.platform.GlStateManager.translatef(-cx, -cy, 0f);
        s1mp1e$cascadePosed = true;
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "renderButton", at = @At("RETURN"))
    private void s1mp1e$cascadeEnd(int mouseX, int mouseY, float delta,
                                   org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!s1mp1e$cascadePosed) return;
        s1mp1e$cascadePosed = false;
        com.mojang.blaze3d.platform.GlStateManager.popMatrix();
    }

    @Redirect(method = "renderButton",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/recipebook/AnimatedResultButton;"
                            + "blit(IIIIII)V"))
    private void s1mp1e$glassCell(AnimatedResultButton self,
                                  int x, int y, int u, int v, int w, int h) {
        // Reuse the backdrop the book panel grabbed this frame. If glass is off, or
        // no backdrop is available, keep the vanilla slot PNG so the cell never
        // vanishes.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable()) || !SceneCapture.hasBackdrop()) {
            self.blit(x, y, u, v, w, h);
            return;
        }
        boolean craftable = (u == 29);   // vanilla: u=29 craftable, u=54 uncraftable
        float lift = craftable ? LIFT_CRAFTABLE : 0f;
        GlassRenderer.glass(x, y, x + w, y + h, 6f, 1.0f, lift, 1.0f, GlassRenderer.FROST_NONE);
    }
}
