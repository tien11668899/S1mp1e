package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.gui.widget.ToggleButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The recipe-book open/close toggle button (the little book icon beside the
 * crafting grid) becomes a translucent liquid-glass capsule — sibling of
 * {@link RecipeTabGlassMixin}. 1.16.5 Fabric port of 26.2's recipe-book button glass.
 *
 * <h3>Target class — {@code ToggleButtonWidget}</h3>
 * {@code net.minecraft.client.gui.widget.ToggleButtonWidget} (intermediary
 * {@code class_361}, obf {@code dma}), {@code extends ClickableWidget extends
 * DrawableHelper}. This is the base toggle whose {@code renderButton} paints the
 * texture at {@code (u,v)} shifted by {@code pressedUOffset}/{@code hoverVOffset}
 * for the toggled/hovered states. The recipe category tabs extend this class but
 * fully OVERRIDE {@code renderButton} (they never call {@code super}), so this
 * mixin only affects plain toggles — i.e. the recipe-book open/close button — while
 * the tabs are handled by {@link RecipeTabGlassMixin}.
 *
 * <h3>Bytecode recon (yarn 1.16.5+build.10, merged jar)</h3>
 * {@code ToggleButtonWidget.renderButton} is yarn {@code method_25359} = obf
 * {@code dma.b(dfm,int,int,float)} ({@code dfm} = {@code MatrixStack}). Its single
 * texture blit is yarn {@code method_25302} =
 * {@code drawTexture(MatrixStack,int,int,int,int,int,int)} = obf {@code b(Ldfm;IIIIII)V}
 * (public, declared on {@code DrawableHelper}). Constant-pool entry {@code #85}
 * resolves to {@code dma.b:(Ldfm;IIIIII)V}: the {@code invokevirtual} OWNER is the
 * receiver's own class {@code ToggleButtonWidget}, NOT {@code DrawableHelper} — so the
 * {@code @Redirect} target owner is {@code ToggleButtonWidget} (same receiver-class
 * rule verified for {@code HandledScreen.fillGradient}). Args (from the invoke):
 * {@code drawTexture(matrices, x, y, u, v, w, h)} with {@code x=this.x}, {@code y=this.y},
 * {@code w}/{@code h} the widget size.
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

    @Redirect(method = "renderButton",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/widget/ToggleButtonWidget;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$glassToggle(ToggleButtonWidget self, MatrixStack matrices,
                                    int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) {
            self.drawTexture(matrices, x, y, u, v, w, h);
            return;
        }
        float lift = self.isToggled() ? LIFT_SELECTED : 0f;
        GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, 1.0f, true);
    }
}
