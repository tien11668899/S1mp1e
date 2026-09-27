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
 * {@code manager.drawTexture(matrices, 0, 0, u, v, width, height)}; that call is redirected to nothing while the glass
 * is up, and a frosted glass card (hotbar corner, R2) plus a faint grey scrim is drawn at HEAD instead. Because both
 * sit under the {@code ToastManager}'s slide-in pose translation on the passed {@link MatrixStack} - the card is
 * projected through it ({@link HudGlass#glassBoxLocalHotbar}) - the card slides in with the toast. The toast's own icon
 * and text draw on top unchanged. Falls back to the vanilla frame when the glass pipeline is unusable.
 *
 * <p>{@code SystemToast} tiles its background in multiple parts and is handled by {@code SystemToastGlassMixin}.
 * (javap-verified: each toast's {@code draw(MatrixStack, ToastManager, long)} calls
 * {@code ToastManager.drawTexture(MatrixStack,IIIIII)} once for the frame — identical to the 1.19.2 sibling.)
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
        HudGlass.glassBoxLocalHotbar(matrices, 0, 0, w, h, 0.92F);
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
