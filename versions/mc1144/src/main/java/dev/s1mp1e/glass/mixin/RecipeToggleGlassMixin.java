package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.gui.widget.ToggleButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The recipe-book open/close toggle button becomes a translucent liquid-glass
 * capsule — the 1.14.4 tier port of 26.2's recipe-book button glass.
 *
 * <h3>Target class — {@code ToggleButtonWidget}</h3>
 * {@code net.minecraft.client.gui.widget.ToggleButtonWidget} (class_361, obf
 * {@code czy}), {@code extends AbstractButtonWidget extends DrawableHelper}. The
 * recipe category tabs extend this class but fully OVERRIDE {@code renderButton}
 * (they never call {@code super}), so this mixin only affects plain toggles — the
 * recipe-book open/close button — while the tabs are handled by
 * {@link RecipeTabGlassMixin}.
 *
 * <h3>Bytecode recon (yarn 1.14.4+build.18, merged jar)</h3>
 * {@code ToggleButtonWidget.renderButton} = {@code (IIF)V} (NO MatrixStack). Its
 * single texture blit is {@code blit(IIIIII)V} (offset 85, the 1.14.4 name of
 * {@code DrawableHelper.drawTexture}); the {@code invokevirtual} OWNER is the
 * receiver's own class {@code ToggleButtonWidget}, NOT {@code DrawableHelper} — so
 * the {@code @Redirect} target owner is {@code ToggleButtonWidget}. Args (from the
 * invoke): {@code blit(x, y, u, v, w, h)}.
 *
 * <h3>Knobs</h3>
 * The {@code BTN} capsule program (no backdrop), corner {@code 1.0}, opacity
 * {@code 1.0}; neutral-white lift {@code 0.153} when the book is open (toggled), else
 * {@code 0} — 26.2's {@code tab_selected} lift. {@code isToggled()} is the yarn
 * accessor for the inherited {@code toggled} field.
 */
@Mixin(ToggleButtonWidget.class)
public abstract class RecipeToggleGlassMixin {

    /** 26.2's selected/craftable lift (G=0xD8 -> 1-0xD8/255). */
    private static final float LIFT_SELECTED = 0.153f;

    @Redirect(method = "renderButton",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/ToggleButtonWidget;"
                            + "blit(IIIIII)V"))
    private void s1mp1e$glassToggle(ToggleButtonWidget self,
                                    int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) {
            self.blit(x, y, u, v, w, h);
            return;
        }
        float lift = self.isToggled() ? LIFT_SELECTED : 0f;
        GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, 1.0f, true);
    }
}
