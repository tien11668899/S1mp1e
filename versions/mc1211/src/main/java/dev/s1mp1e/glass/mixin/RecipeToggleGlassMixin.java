package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ToggleButtonWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The recipe-book open/close toggle button (the little book icon beside the
 * crafting grid) becomes a translucent liquid-glass capsule — sibling of
 * {@link RecipeTabGlassMixin}. 1.20.1 Fabric port of 26.2's recipe-book button glass.
 *
 * <h3>Target class — {@code ToggleButtonWidget}</h3>
 * {@code net.minecraft.client.gui.widget.ToggleButtonWidget}, {@code extends
 * ClickableWidget extends DrawableHelper}. This is the base toggle whose
 * {@code renderButton} paints the texture at {@code (u,v)} shifted by
 * {@code pressedUOffset}/{@code hoverVOffset} for the toggled/hovered states. The
 * recipe category tabs extend this class but fully OVERRIDE {@code renderButton}
 * (they never call {@code super}), so this mixin only affects plain toggles — i.e.
 * the recipe-book open/close button — while the tabs are handled by
 * {@link RecipeTabGlassMixin}.
 *
 * <h3>Bytecode recon (yarn 1.17.1+build.65, merged jar)</h3>
 * {@code ToggleButtonWidget.renderButton}
 * ({@code (Lnet/minecraft/client/util/math/MatrixStack;IIF)V}). Its single texture
 * blit is {@code drawTexture(MatrixStack,IIIIII)} at offset 85 (constant-pool
 * {@code #125 = ToggleButtonWidget.drawTexture:(...)V}): the {@code invokevirtual}
 * OWNER is the receiver's own class {@code ToggleButtonWidget}, NOT
 * {@code DrawableHelper} — so the {@code @Redirect} target owner is
 * {@code ToggleButtonWidget}. {@code isToggled()} reports the open state.
 *
 * <h3>Knobs</h3>
 * Same as {@link RecipeTabGlassMixin}: the {@code BTN} capsule program (no backdrop,
 * so no {@link dev.s1mp1e.glass.render.SceneCapture#grab()}), corner {@code 1.0},
 * opacity {@code 1.0}, no dim; neutral-white lift {@code 0.153} when the book is
 * open (toggled), else {@code 0} — 26.2's {@code tab_selected} lift.
 */
@Mixin(ToggleButtonWidget.class)
public abstract class RecipeToggleGlassMixin {

    /** 26.2's selected/craftable lift (G=0xD8 -> 1-0xD8/255). */
    private static final float LIFT_SELECTED = 0.153f;

    // 1.21: renderButton -> renderWidget (method_48579), and the toggle sprite is
    // now blitted through DrawContext.drawGuiTexture(Identifier, x, y, w, h)
    // (method_52706) instead of the u/v drawTexture — the recipe book moved to GUI
    // sprites. Retarget both, and drop the (u,v) params from the handler.
    @Redirect(method = "renderWidget",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$glassToggle(DrawContext self, Identifier texture,
                                    int x, int y, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) {
            self.drawGuiTexture(texture, x, y, w, h);
            return;
        }
        // `self` is now the DrawContext receiver; read the toggle state off `this`.
        boolean toggled = ((ToggleButtonWidget) (Object) this).isToggled();
        float lift = toggled ? LIFT_SELECTED : 0f;
        GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, 1.0f, true);
    }
}
