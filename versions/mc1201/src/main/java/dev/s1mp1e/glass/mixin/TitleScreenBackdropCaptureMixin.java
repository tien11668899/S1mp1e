package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All-glass #26: one panorama rotation for the title screen and every world-less menu. Vanilla 1.20.1 gives each
 * TitleScreen its own {@link RotatingCubeMapRenderer}; {@link MenuBackdrop} renders a shared one behind the other menus.
 * Around the title's own {@code RotatingCubeMapRenderer.render} call the angles are handed over both ways — shared →
 * title just before (so coming back from Options continues where the menus left off), title → shared just after (so
 * opening a menu continues from the title) — and the view never jumps.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenBackdropCaptureMixin {

    @Shadow @Final private RotatingCubeMapRenderer backgroundRenderer;

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/RotatingCubeMapRenderer;render(FF)V"))
    private void s1mp1e$panoramaIn(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        RotatingCubeMapAccessor shared = (RotatingCubeMapAccessor) MenuBackdrop.panorama();
        RotatingCubeMapAccessor title = (RotatingCubeMapAccessor) this.backgroundRenderer;
        title.s1mp1e$setPitch(shared.s1mp1e$pitch());
        title.s1mp1e$setYaw(shared.s1mp1e$yaw());
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/RotatingCubeMapRenderer;render(FF)V", shift = At.Shift.AFTER))
    private void s1mp1e$panoramaOut(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        RotatingCubeMapAccessor shared = (RotatingCubeMapAccessor) MenuBackdrop.panorama();
        RotatingCubeMapAccessor title = (RotatingCubeMapAccessor) this.backgroundRenderer;
        shared.s1mp1e$setPitch(title.s1mp1e$pitch());
        shared.s1mp1e$setYaw(title.s1mp1e$yaw());
    }
}
