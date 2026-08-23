package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 5 (menu-backdrop blur) — the panorama CAPTURE. 1.14.4 tier port of the
 * 1.16.5 mixin.
 *
 * <p>Forge counterpart: LiquidGlass26 drove {@code MenuBackdrop.capture()} from an
 * ASM hook immediately after {@code GuiMainMenu.renderSkybox} returns — the one point
 * where the title BACKGROUND is on screen by itself.
 *
 * <p>Fabric target: {@code TitleScreen.render(int, int, float)} — verified in the
 * 1.14.4 yarn tiny as {@code render(IIF)V} (class_442) — at the {@code INVOKE} of the
 * panorama renderer {@code RotatingCubeMapRenderer.render(float, float)}
 * ({@code render(FF)V}, class_766, UNCHANGED from 1.16.5), {@code shift = AFTER}. The
 * only tier delta is the enclosing method: 1.14.4 predates MatrixStack, so
 * {@code render} takes {@code (int, int, float)} and the handler drops the MatrixStack
 * first arg. {@link MenuBackdrop#capture()} is self-throttling (~30 Hz) and freezes
 * once you leave the menu.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenBackdropCaptureMixin {

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/RotatingCubeMapRenderer;render(FF)V",
                     shift = At.Shift.AFTER))
    private void s1mp1e$capturePanorama(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MenuBackdrop.capture();
    }
}
