package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 5 (menu-backdrop blur) — the panorama CAPTURE.
 *
 * <p>Forge counterpart: LiquidGlass26 drove {@code MenuBackdrop.capture()} from an
 * ASM hook placed immediately after {@code GuiMainMenu.renderSkybox} returns — the
 * one point where the title BACKGROUND is on screen by itself, before the logo,
 * splash and buttons. Capturing at end-of-frame instead would bake in and blur
 * those overlays.
 *
 * <p>Fabric target: {@code TitleScreen.render(int, int, float)} at the
 * {@code INVOKE} of the panorama renderer,
 * {@code RotatingCubeMapRenderer.render(float, float)} ({@code method_3317},
 * descriptor {@code (FF)V}), {@code shift = AFTER}. Verified against the mapped
 * 1.15.2 jar: {@code TitleScreen.render} invokes {@code RotatingCubeMapRenderer.
 * render(FF)V} at bytecode offset 75; at that instruction the framebuffer holds
 * only the panorama. {@link MenuBackdrop#capture()} is self-throttling (~30 Hz) and
 * freezes once you leave the menu.
 *
 * <p><b>1.15.2 delta vs 1.16.5:</b> {@code TitleScreen.render} is {@code (IIF)V} —
 * no {@code MatrixStack} first arg — so the injected signature drops it. The
 * {@code RotatingCubeMapRenderer.render(FF)V} target is unchanged across 1.15.2 and
 * 1.16.5.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenBackdropCaptureMixin {

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/RotatingCubeMapRenderer;render(FF)V",
                     shift = At.Shift.AFTER))
    private void s1mp1e$capturePanorama(int mouseX, int mouseY,
                                        float delta, CallbackInfo ci) {
        MenuBackdrop.capture();
    }
}
