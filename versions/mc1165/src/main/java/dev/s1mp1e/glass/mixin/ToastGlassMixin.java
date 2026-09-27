package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.toast.AdvancementToast;
import net.minecraft.client.toast.RecipeToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.toast.TutorialToast;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Advancement / recipe / tutorial toasts become liquid-glass cards (G4). Each draws its background frame with one
 * {@code manager.drawTexture(matrices, 0, 0, u, v, width, height)} (decompile-verified); that call is redirected to
 * nothing while the glass is up, and a frosted glass card (hotbar corner, R2) plus a faint grey scrim is drawn at HEAD
 * instead. On 1.16.5 the {@code ToastManager.Entry} slide-in is applied to the GL model-view
 * ({@code RenderSystem.translatef(x - w*progress, y*h, 800+y)}), and {@link HudGlass#glassBoxHotbar} draws its raw-GL
 * quad under that same model-view, so the card slides in with the toast (LOCAL {@code 0..w,0..h} coords). The toast's
 * own icon and text draw on top unchanged. Falls back to the vanilla frame when the glass pipeline is unusable.
 *
 * <p>{@code SystemToast} tiles its background in multiple parts and is handled by {@code SystemToastGlassMixin}.
 */
@Mixin({AdvancementToast.class, RecipeToast.class, TutorialToast.class})
public abstract class ToastGlassMixin {

    /** Faint grey scrim under the toast text/icon, over the glass card. */
    private static final int TOAST_SCRIM = 0x30101018;

    @Inject(method = "draw", at = @At("HEAD"))
    private void s1mp1e$toastCard(MatrixStack matrices, ToastManager manager, long time,
                                  CallbackInfoReturnable<Toast.Visibility> cir) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        Toast self = (Toast) (Object) this;
        int w = self.getWidth(), h = self.getHeight();
        HudGlass.glassBoxHotbar(0, 0, w, h, 0.92F);
        DrawableHelper.fill(matrices, 0, 0, w, h, TOAST_SCRIM);
    }

    @Redirect(method = "draw",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/toast/ToastManager;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$dropFrame(ToastManager manager, MatrixStack matrices, int x, int y, int u, int v, int w, int h) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            manager.drawTexture(matrices, x, y, u, v, w, h);
        }
    }
}
