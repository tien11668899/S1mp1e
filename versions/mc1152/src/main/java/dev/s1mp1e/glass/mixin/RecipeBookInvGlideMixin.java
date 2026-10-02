package dev.s1mp1e.glass.mixin;

import java.util.ArrayList;
import java.util.List;

import dev.s1mp1e.glass.render.RecipeBookSlide;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeBookProvider;
import net.minecraft.client.gui.widget.AbstractButtonWidget;
import net.minecraft.client.gui.widget.TexturedButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Glides the WHOLE inventory when the recipe book opens/closes — the 1.15.2 port of 26.2's {@code RecipeBookInvGlideMixin}
 * (the "whole inventory moves as one" root-cause fix). Vanilla, in the recipe-book button's click handler, calls
 * {@code recipeBook.toggleOpen()} and then sets {@code this.x = recipeBook.findLeftEdge(...)} — so the container jumps
 * ~77 px in one frame. Here the field is instead overridden to {@link RecipeBookSlide#animatedX()} at the very HEAD of
 * {@code render} (before the panel is drawn from it, and before the slot loop's model-view {@code translate(x, y)}),
 * and restored at RETURN so hit-testing keeps the real position.
 *
 * <p>Because the panel, slot lattice, items, labels, the survival player preview and the crafting grid ALL read the same
 * {@code x}, overriding it once slides every one of them together — matching 26.2's "change the field, don't pose each
 * part" fix. The recipe-book button itself is a {@link TexturedButtonWidget} whose position is a widget field (not
 * re-read from {@code x} each frame), so it is shifted by the same delta for the frame and restored afterwards. Only
 * recipe screens ({@link RecipeBookProvider}: inventory / crafting / furnaces) are affected; every other container runs
 * the untouched vanilla path. The toggle is detected here ({@code x} moved since last frame) and the glide advanced
 * before anything draws.
 */
@Mixin(ContainerScreen.class)
public abstract class RecipeBookInvGlideMixin {

    @Shadow protected int x;

    @Unique private boolean s1mp1e$overridden;
    @Unique private int s1mp1e$saved;
    @Unique private int s1mp1e$shift;
    @Unique private final List<TexturedButtonWidget> s1mp1e$shifted = new ArrayList<>();

    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$glideBegin(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        s1mp1e$overridden = false;
        if (!((Object) this instanceof RecipeBookProvider)) return;

        int cur = this.x;
        // the book (drawn before this render) may already have reported the move this frame — see observe()
        RecipeBookSlide.observe(this, cur);
        if (!RecipeBookSlide.active()) return;

        int animated = RecipeBookSlide.animatedX();
        s1mp1e$saved = cur;
        s1mp1e$shift = animated - cur;
        this.x = animated;
        s1mp1e$overridden = true;
        s1mp1e$shifted.clear();
        if (s1mp1e$shift != 0) {
            for (Element c : ((Screen) (Object) this).children()) {
                if (c instanceof TexturedButtonWidget) {
                    TexturedButtonWidget b = (TexturedButtonWidget) c;
                    b.x += s1mp1e$shift;
                    s1mp1e$shifted.add(b);
                }
            }
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void s1mp1e$glideEnd(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$overridden) return;
        s1mp1e$overridden = false;
        this.x = s1mp1e$saved;
        for (AbstractButtonWidget b : s1mp1e$shifted) b.x -= s1mp1e$shift;
        s1mp1e$shifted.clear();
    }
}
