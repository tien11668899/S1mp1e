package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.recipe.book.RecipeBookGroup;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Recipe-book category tabs — the 1.14.4 tier port of 26.2's
 * {@code RecipeTabGlassMixin}. Two jobs, both matching LiquidGlass26 exactly:
 *
 * <ol>
 *   <li><b>Suppress the flat tab sprite.</b> The ONE vertical glass bar + the
 *       liquid-sliding selected pill live in {@link RecipeBookGlassMixin}; the
 *       individual tab backgrounds draw nothing (icons only) whenever the glass
 *       path is usable.</li>
 *   <li><b>Centre the tab icon, removing vanilla's selected-shift.</b> Vanilla
 *       nudges the icon {@code -2px} when the tab is toggled (bytecode:
 *       {@code int i = this.toggled ? -2 : 0}; single icon at {@code x+9+i},
 *       double at {@code x+3+i / x+14+i}). 26.2 removes the shift and draws the
 *       icon at a FIXED position centred in the 27px glass bar (single at
 *       {@code x+6}, double at {@code x+1 / x+12}).</li>
 * </ol>
 *
 * <h3>Bytecode recon (yarn 1.14.4+build.18, merged jar)</h3>
 * Target {@code RecipeGroupButtonWidget} (obf {@code dfa} / {@code class_512},
 * {@code extends ToggleButtonWidget extends AbstractButtonWidget extends
 * DrawableHelper}). {@code renderButton} = {@code (IIF)V} (NO MatrixStack); its sole
 * {@code blit(IIIIII)V} INVOKE (owner {@code RecipeGroupButtonWidget} per javac's
 * receiver-type rule) is the tab sprite.
 *
 * <p>1.14.4 has NO {@code renderIcons} method — the icons are drawn from a private
 * helper {@code method_2621(ItemRenderer)} ({@code dfa.a(Ldsv;)V}, called from the
 * tail of {@code renderButton}). That helper reads {@code this.category}
 * ({@code RecipeBookGroup}, obf {@code g}, a field declared ON
 * {@code RecipeGroupButtonWidget} → {@code @Shadow}-safe) and draws each stack with
 * {@code ItemRenderer.renderGuiItem(ItemStack,int,int)} ({@code dsv.b(Lbcj;II)V} —
 * the 1.14.4 name of 1.16.5's {@code renderInGui}). We cancel it at HEAD and redraw
 * the icons centred, exactly like 26.2's {@code extractIcon} inject.
 */
@Mixin(RecipeGroupButtonWidget.class)
public abstract class RecipeTabGlassMixin {

    /** The tab's category — own field of RecipeGroupButtonWidget (@Shadow safe);
     *  {@code getIcons()} returns the 1-or-2 icon stacks. */
    @Shadow @org.spongepowered.asm.mixin.Final private RecipeBookGroup category;

    @Redirect(method = "renderButton",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/recipebook/RecipeGroupButtonWidget;"
                            + "blit(IIIIII)V"))
    private void s1mp1e$glassTab(RecipeGroupButtonWidget self,
                                 int x, int y, int u, int v, int w, int h) {
        // 26.2: individual tab sprites draw NOTHING — the glass bar + sliding pill
        // (RecipeBookGlassMixin) replace them. The tab ICON still draws afterwards
        // (method_2621, centred by the inject below). Fall back to the flat vanilla
        // sprite only when the glass program is unavailable.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            self.blit(x, y, u, v, w, h);
        }
    }

    /**
     * 26.2 {@code lg$centeredIcon}: redraw the tab icon at a FIXED position centred
     * in the glass bar, dropping vanilla's {@code toggled ? -2 : 0} shift. Cancels
     * the vanilla icon helper ({@code method_2621}).
     */
    @Inject(method = "method_2621", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$centerIcons(ItemRenderer itemRenderer, CallbackInfo ci) {
        // Glass off -> keep vanilla icon draw (with its shift) so the flat tabs
        // still look right.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        ci.cancel();
        RecipeGroupButtonWidget self = (RecipeGroupButtonWidget) (Object) this;
        List<ItemStack> icons = this.category.getIcons();
        int x = self.x, y = self.y;
        // Centred in the [x+1, x+28] glass bar (26.2's fixed offsets, no shift).
        if (icons.size() == 1) {
            itemRenderer.renderGuiItem(icons.get(0), x + 6, y + 5);
        } else if (icons.size() >= 2) {
            itemRenderer.renderGuiItem(icons.get(0), x + 1,  y + 5);
            itemRenderer.renderGuiItem(icons.get(1), x + 12, y + 5);
        }
    }
}
