package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The recipe-book open/close toggle button (the little book icon beside the crafting
 * grid) becomes a translucent liquid-glass capsule — 1.13.2 (Legacy Fabric) port of
 * 26.2's recipe-book button glass. Sibling of {@link RecipeTabGlassMixin}.
 *
 * <h3>Target class — the base ToggleButtonWidget</h3>
 * obf {@code chn} / intermediary {@code class_3257} (UNMAPPED), {@code extends ButtonWidget}
 * ({@code cgu} / {@code class_356}). This is the base toggle whose {@code renderButton}
 * paints the texture at {@code (u,v)} shifted by the pressed/hovered offsets. The recipe
 * category tabs ({@code class_3284}) extend this class but fully OVERRIDE
 * {@code renderButton} (they never call {@code super}), so this mixin only affects plain
 * toggles — i.e. the recipe-book open/close button — while the tabs are handled by
 * {@link RecipeTabGlassMixin}.
 *
 * <h3>1.13.2 recon (obf merged jar + legacyfabric yarn build.604)</h3>
 * <ul>
 *   <li>renderButton = obf {@code a(IIF)V} = intermediary {@code method_891} (render name
 *       mapped on {@code ButtonWidget}; the {@code class_3257} override carries it). Its
 *       SOLE {@code drawTexture(IIIIII)V} INVOKE (offset 144) is the toggle sprite; the
 *       invoke OWNER is {@code class_3257} itself (receiver-type rule), so the
 *       {@code @Redirect} owner is {@code class_3257}. Pre-MatrixStack: {@code (IIF)V}.</li>
 *   <li>the toggled flag is own field obf {@code p} = intermediary {@code field_15896}
 *       ({@code boolean}) → {@code @Shadow} safe (own field of the target).</li>
 * </ul>
 * {@code drawTexture} is PUBLIC inherited from {@code DrawableHelper} — NOT
 * {@code @Shadow}'d; the fallback calls it through the redirect's {@code self} param.
 *
 * <h3>Knobs</h3>
 * The {@code BTN} capsule program (no backdrop, so no {@code SceneCapture.grab()}), corner
 * {@code 1.0}, opacity {@code 1.0}, no dim; neutral-white lift {@code 0.153} when the book
 * is open (toggled), else {@code 0} — 26.2's {@code tab_selected} lift.
 */
@Mixin(net.minecraft.class_3257.class)
public abstract class RecipeToggleGlassMixin {

    /** 26.2's selected/craftable lift (G=0xD8 -> 1-0xD8/255). */
    private static final float LIFT_SELECTED = 0.153f;

    /** Toggled state — own boolean field of {@code class_3257} (@Shadow safe). */
    @Shadow private boolean field_15896;

    @Redirect(method = "method_891",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/class_3257;drawTexture(IIIIII)V"))
    private void s1mp1e$glassToggle(net.minecraft.class_3257 self,
                                    int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) {
            self.drawTexture(x, y, u, v, w, h);
            return;
        }
        float lift = field_15896 ? LIFT_SELECTED : 0f;
        GlassRenderer.button(x, y, x + w, y + h, 1.0f, lift, 1.0f, true);
    }
}
