package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * All-glass #26: one panorama rotation for the title screen and every world-less menu. Vanilla 1.18.2 gives each
 * TitleScreen its own {@link RotatingCubeMapRenderer}; {@link MenuBackdrop} renders a shared one behind the other menus.
 * Around the title's own {@code RotatingCubeMapRenderer.render(FF)} call the rotation phase (the private {@code time})
 * is handed over both ways — shared → title just before (so coming back from Options continues where the menus left
 * off), title → shared just after (so opening a menu continues from the title) — and the view never jumps.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenBackdropCaptureMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/RotatingCubeMapRenderer;render(FF)V"))
    private void s1mp1e$shareRotation(RotatingCubeMapRenderer self, float delta, float alpha) {
        RotatingCubeMapRenderer shared = MenuBackdrop.panorama();
        if (shared != self) {
            ((RotatingCubeMapAccessor) self).s1mp1e$setTime(((RotatingCubeMapAccessor) shared).s1mp1e$time());
        }
        self.render(delta, alpha);
        if (shared != self) {
            ((RotatingCubeMapAccessor) shared).s1mp1e$setTime(((RotatingCubeMapAccessor) self).s1mp1e$time());
        }
        MenuBackdrop.capture();
    }
}
