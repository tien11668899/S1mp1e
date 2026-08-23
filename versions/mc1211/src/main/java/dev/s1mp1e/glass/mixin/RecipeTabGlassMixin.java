package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.recipebook.RecipeBookGroup;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.recipebook.RecipeGroupButtonWidget;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Recipe-book category tabs — the 1.20.1 Fabric counterpart of 26.2's
 * {@code RecipeTabGlassMixin}. Two jobs, both matching LiquidGlass26 exactly:
 *
 * <ol>
 *   <li><b>Suppress the flat tab sprite.</b> The ONE vertical glass bar + the
 *       liquid-sliding selected pill live in {@link RecipeBookGlassMixin}; the
 *       individual tab backgrounds draw nothing (icons only) whenever the glass
 *       path is usable. Mirrors 26.2 {@code lg$glassTab}.</li>
 *   <li><b>Centre the tab icon, removing vanilla's selected-shift.</b> Vanilla
 *       {@code renderIcons} nudges the icon {@code -2px} when the tab is toggled;
 *       26.2 removes the shift and draws the icon at a FIXED position centred in the
 *       27px glass bar (single at {@code x+6}, double at {@code x+1 / x+12}).
 *       Mirrors 26.2 {@code lg$centeredIcon}.</li>
 * </ol>
 *
 * <h3>Bytecode recon (yarn 1.17.1+build.65, merged jar)</h3>
 * Target {@code RecipeGroupButtonWidget} ({@code extends ToggleButtonWidget extends
 * ClickableWidget extends DrawableHelper}). {@code renderButton}
 * ({@code (Lnet/minecraft/client/util/math/MatrixStack;IIF)V}) has its sole
 * {@code drawTexture(MatrixStack,IIIIII)} INVOKE at offset 198 (constant-pool
 * {@code #219 = RecipeGroupButtonWidget.drawTexture:(...)V}) — owner
 * {@code RecipeGroupButtonWidget} per javac's receiver-type rule — the tab sprite.
 * {@code renderIcons(ItemRenderer)} reads {@code this.category} ({@code private final
 * RecipeBookGroup category}, declared ON {@code RecipeGroupButtonWidget} →
 * {@code @Shadow}-safe) and draws each stack with
 * {@code ItemRenderer.renderInGui(ItemStack,int,int)}. {@code RecipeBookGroup.getIcons()}
 * returns {@code List<ItemStack>}.
 */
@Mixin(RecipeGroupButtonWidget.class)
public abstract class RecipeTabGlassMixin {

    /** The tab's category — own field of RecipeGroupButtonWidget (@Shadow safe);
     *  {@code getIcons()} returns the 1-or-2 icon stacks. */
    @Shadow @org.spongepowered.asm.mixin.Final private RecipeBookGroup category;

    // 1.21: renderButton -> renderWidget (method_48579); the tab sprite blit is now
    // DrawContext.drawGuiTexture(Identifier, x, y, w, h) (method_52706) instead of
    // the u/v drawTexture. Retarget both; the handler drops (u,v).
    @Redirect(method = "renderWidget",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$glassTab(DrawContext self, Identifier texture,
                                 int x, int y, int w, int h) {
        // 26.2: individual tab sprites draw NOTHING — the glass bar + sliding pill
        // (RecipeBookGlassMixin) replace them. The tab ICON still draws afterwards
        // (renderIcons, centred by the inject below). Fall back to the flat vanilla
        // sprite only when the glass program is unavailable (then the bar is skipped
        // too, so the tabs need their own sprite back).
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            self.drawGuiTexture(texture, x, y, w, h);
        }
    }

    /**
     * 26.2 {@code lg$centeredIcon}: redraw the tab icon at a FIXED position centred
     * in the glass bar, dropping vanilla's {@code toggled ? -2 : 0} shift so the icon
     * no longer jumps when the tab is selected. Cancels the vanilla {@code renderIcons}.
     */
    @Inject(method = "renderIcons", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$centerIcons(DrawContext context, ItemRenderer itemRenderer, CallbackInfo ci) {
        // Glass off -> keep vanilla renderIcons (with its shift) so the flat tabs
        // still look right.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        ci.cancel();
        RecipeGroupButtonWidget self = (RecipeGroupButtonWidget) (Object) this;
        List<ItemStack> icons = this.category.getIcons();
        // 1.20: ClickableWidget x/y are private; read via the Widget getters.
        int x = self.getX(), y = self.getY();
        // Centred in the [x+1, x+28] glass bar (26.2's fixed offsets, no shift).
        // 1.20 moved item drawing off ItemRenderer onto DrawContext.drawItem.
        if (icons.size() == 1) {
            context.drawItem(icons.get(0), x + 6, y + 5);
        } else if (icons.size() >= 2) {
            context.drawItem(icons.get(0), x + 1,  y + 5);
            context.drawItem(icons.get(1), x + 12, y + 5);
        }
    }
}
