package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * System toasts (G4) become liquid-glass cards, like the other toasts, but their background is TILED from several
 * {@code drawTexture} parts (a variable-width/height frame drawn in {@code draw} + the {@code drawPart} helper). So the
 * frame drops via a redirect that covers BOTH methods, and one glass card sized to {@code getWidth()x getHeight()} is
 * drawn at HEAD, projected through the slide pose. Text draws on top. Falls back to the vanilla frame when the glass
 * pipeline is unusable.
 */
@Mixin(SystemToast.class)
public abstract class SystemToastGlassMixin {

    private static final int TOAST_SCRIM = 0x30101018;

    @Inject(method = "draw", at = @At("HEAD"))
    private void s1mp1e$toastCard(MatrixStack matrices, ToastManager manager, long time,
                                  CallbackInfoReturnable<Toast.Visibility> cir) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        Toast self = (Toast) (Object) this;
        int w = self.getWidth(), h = self.getHeight();
        HudGlass.glassBoxLocalHotbar(matrices, 0, 0, w, h, 0.92F);
        DrawableHelper.fill(matrices, 0, 0, w, h, TOAST_SCRIM);
    }

    @Redirect(method = {"draw", "drawPart"},
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/toast/ToastManager;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$dropFrame(ToastManager manager, MatrixStack matrices, int x, int y, int u, int v, int w, int h) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            manager.drawTexture(matrices, x, y, u, v, w, h);
        }
    }
}
