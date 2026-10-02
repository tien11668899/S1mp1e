package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * System toasts (G4) become liquid-glass cards, like the other toasts, but their background is TILED from several
 * {@code drawTexture} parts (a variable-width/height frame drawn in {@code draw} + the {@code drawPart} helper —
 * decompile-verified). So the frame drops via a redirect that covers BOTH methods, and one glass card sized to
 * {@code getWidth() x getHeight()} is drawn at HEAD, riding the GL model-view slide pose. Text draws on top. Falls back
 * to the vanilla frame when the glass pipeline is unusable.
 */
@Mixin(SystemToast.class)
public abstract class SystemToastGlassMixin {

    private static final int TOAST_SCRIM = 0x30101018;

    @Inject(method = "draw", at = @At("HEAD"))
    private void s1mp1e$toastCard(ToastManager manager, long time,
                                  CallbackInfoReturnable<Toast.Visibility> cir) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        Toast self = (Toast) (Object) this;
        int w = 160, h = 32;   // 1.14.4: every toast is 160 x 32 (Toast.getWidth / getHeight are 1.16+)
        HudGlass.glassBoxHotbar(0, 0, w, h, 0.92F);
        DrawableHelper.fill(0, 0, w, h, TOAST_SCRIM);
    }

    @Redirect(method = "draw",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/toast/ToastManager;blit(IIIIII)V"))
    private void s1mp1e$dropFrame(ToastManager manager, int x, int y, int u, int v, int w, int h) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            manager.blit(x, y, u, v, w, h);
        }
    }
}
