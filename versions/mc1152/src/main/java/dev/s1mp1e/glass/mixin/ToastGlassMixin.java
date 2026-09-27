package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.toast.AdvancementToast;
import net.minecraft.client.toast.RecipeToast;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.toast.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Advancement / recipe / tutorial / system toasts become liquid-glass cards (G4). In 1.15.2 every toast draws its
 * fixed 160x32 background frame with one {@code manager.blit(x, y, u, v, 160, 32)} (javap-verified: {@code Toast} has
 * no per-toast width/height — the frame is a single blit); that first {@code blit} (ordinal 0) is redirected to nothing
 * while the glass is up, and a frosted glass card (hotbar corner, R2) plus a faint grey scrim is drawn at HEAD instead.
 * The {@code ToastManager} applies each toast's slide-in to the GL model-view ({@code RenderSystem.pushMatrix() +
 * translatef}) before calling {@code draw}, and {@link HudGlass#glassBoxHotbar} draws its raw-GL quad under that same
 * model-view, so the card slides in with the toast (LOCAL {@code 0..160,0..32} coords). The toast's own icon and text
 * (drawn as ordinal-1+ blits / item renders / text) stay on top unchanged. Falls back to the vanilla frame when the
 * glass pipeline is unusable.
 */
@Mixin({AdvancementToast.class, RecipeToast.class, TutorialToast.class, SystemToast.class})
public abstract class ToastGlassMixin {

    /** Faint grey scrim under the toast text/icon, over the glass card. */
    private static final int TOAST_SCRIM = 0x30101018;
    /** The fixed 1.15.2 toast frame size (no per-toast getWidth/getHeight before 1.16). */
    private static final int TOAST_W = 160, TOAST_H = 32;

    @Inject(method = "draw", at = @At("HEAD"))
    private void s1mp1e$toastCard(ToastManager manager, long time, CallbackInfoReturnable<Toast.Visibility> cir) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        HudGlass.glassBoxHotbar(0, 0, TOAST_W, TOAST_H, 0.92F);
        DrawableHelper.fill(0, 0, TOAST_W, TOAST_H, TOAST_SCRIM);
    }

    @Redirect(method = "draw",
              at = @At(value = "INVOKE", ordinal = 0,
                       target = "Lnet/minecraft/client/toast/ToastManager;blit(IIIIII)V"))
    private void s1mp1e$dropFrame(ToastManager manager, int x, int y, int u, int v, int w, int h) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            manager.blit(x, y, u, v, w, h);
        }
    }
}
