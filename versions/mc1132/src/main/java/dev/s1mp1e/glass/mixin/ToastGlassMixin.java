package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.class_3258;
import net.minecraft.class_3259;
import net.minecraft.class_3262;
import net.minecraft.class_3264;
import net.minecraft.class_3266;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Advancement / recipe / tutorial toasts become liquid-glass cards (G4). Each draws its background frame with one
 * {@code manager.drawTexture(0, 0, u, v, width, height)} (decompile-verified); that call is redirected to
 * nothing while the glass is up, and a frosted glass card (hotbar corner, R2) plus a faint grey scrim is drawn at HEAD
 * instead. On 1.13.2 the {@code class_3264.Entry} slide-in is applied to the GL model-view
 * ({@code GlStateManager.translatef(x - w*progress, y*h, 800+y)}), and {@link HudGlass#glassBoxHotbar} draws its raw-GL
 * quad under that same model-view, so the card slides in with the toast (LOCAL {@code 0..w,0..h} coords). The toast's
 * own icon and text draw on top unchanged. Falls back to the vanilla frame when the glass pipeline is unusable.
 *
 * <p>{@code SystemToast} tiles its background in multiple parts and is handled by {@code SystemToastGlassMixin}.
 */
@Mixin({class_3258.class, class_3259.class, class_3266.class})
public abstract class ToastGlassMixin {

    /** Faint grey scrim under the toast text/icon, over the glass card. */
    private static final int TOAST_SCRIM = 0x30101018;

    @Inject(method = "method_14486", at = @At("HEAD"))
    private void s1mp1e$toastCard(class_3264 manager, long time,
                                  CallbackInfoReturnable<class_3262.class_3263> cir) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        class_3262 self = (class_3262) (Object) this;
        int w = 160, h = 32;   // 1.13.2: every toast is 160 x 32 (class_3262.getWidth / getHeight are 1.16+)
        HudGlass.glassBoxHotbar(0, 0, w, h, 0.92F);
        DrawableHelper.fill(0, 0, w, h, TOAST_SCRIM);
    }

    @Redirect(method = "method_14486",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/class_3264;drawTexture(IIIIII)V"))
    private void s1mp1e$dropFrame(class_3264 manager, int x, int y, int u, int v, int w, int h) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            manager.drawTexture(x, y, u, v, w, h);
        }
    }
}
