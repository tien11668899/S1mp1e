package dev.s1mp1e.glass.mixin;

import java.util.ArrayList;
import java.util.List;

import dev.s1mp1e.glass.render.RecipeBookSlide;
import dev.s1mp1e.glass.render.RecipeCascade;
import net.minecraft.class_3285;
import net.minecraft.class_3283;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recipe items cascade in ({@link RecipeCascade}) whenever the page starts showing a different set of recipes — the
 * 1.13.2 port of 26.2's {@code RecipeCascadeMixin$Page}. In 26.2 the single content-assignment point was
 * {@code RecipeBookPage.updateButtonsForPage}; here it is {@code class_3283.refreshResultButtons()} (the private
 * method that reassigns every visible result button its {@code RecipeResultCollection} — called on open, page turn,
 * category switch and search refill, but ALSO on craftability-only refreshes each recipe-book tick). So the page's set
 * of assigned collections is compared with the last cascade (identity), and an unchanged page never re-animates. On an
 * opening the cascade waits until most of the inventory glide is done ({@link RecipeBookSlide#cascadeBase}).
 *
 * <p>The per-button scale-in itself lives in {@link RecipeButtonGlassMixin} (it already redirects
 * {@code class_3285.renderButton}, so folding the {@link RecipeCascade#scale} pose there keeps the glass cell
 * and the item icon growing as one and avoids a second {@code scale()} call per frame).
 */
@Mixin(class_3283.class)
public abstract class RecipeCascadeMixin {

    @Shadow @Final private List<class_3285> field_16058;
    @Shadow private int field_16067;

    @Unique private Object[] s1mp1e$sig;

    @Inject(method = "method_14612", at = @At("RETURN"))
    private void s1mp1e$cascade(CallbackInfo ci) {
        List<class_3285> shown = new ArrayList<>();
        for (class_3285 b : this.field_16058) if (b.visible) shown.add(b);

        // Signature = current page + the identity of each visible button's assigned collection. A craftability-only
        // refresh reassigns the SAME collection objects, so the signature is unchanged and the cascade does not re-fire
        // (which would otherwise reset every button to "born now" each tick — a permanent scale-in flicker).
        Object[] sig = new Object[shown.size() + 1];
        sig[0] = this.field_16067;
        for (int i = 0; i < shown.size(); i++) sig[i + 1] = shown.get(i).method_14620();
        if (s1mp1e$same(sig, s1mp1e$sig)) return;
        s1mp1e$sig = sig;

        RecipeCascade.schedule(shown, RecipeBookSlide.cascadeBase());
    }

    @Unique
    private static boolean s1mp1e$same(Object[] a, Object[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        if (!a[0].equals(b[0])) return false;
        for (int i = 1; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
}
