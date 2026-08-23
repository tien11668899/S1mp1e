package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 5 (menu-backdrop blur) — the panorama CAPTURE. 1.13.2 (Legacy Fabric) port
 * of the 1.16.5 mixin of the same name.
 *
 * <p>Forge counterpart: LiquidGlass26 drove {@code MenuBackdrop.capture()} from an
 * ASM hook placed immediately after the title panorama draws — the one point where
 * the title BACKGROUND is on screen by itself, before the logo, splash and buttons.
 * Capturing at end-of-frame instead would bake in and blur those overlays.
 *
 * <h3>1.13.2 tier delta vs the 1.16.5 source</h3>
 * 1.16.5 injected after {@code RotatingCubeMapRenderer.render(float, float)}. 1.13.2
 * has no yarn name for the rotating panorama renderer — it is the unmapped class
 * {@code net.minecraft.class_4227} and its render is {@code method_19176(F)V} (a
 * SINGLE float, not two). {@code TitleScreen.render} ({@code (IIF)V}, named
 * {@code render}, intermediary {@code method_1025}) invokes it as its first
 * instruction; injecting {@code shift = AFTER} that INVOKE is the yarn-1.13.2
 * equivalent of "just after the skybox draws": the framebuffer holds only the
 * panorama at that point. {@link MenuBackdrop#capture()} is self-throttling (~30 Hz)
 * and freezes once you leave the menu. The callback drops the 1.16.5 leading
 * {@code MatrixStack} (1.13.2 render is pre-MatrixStack).
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenBackdropCaptureMixin {

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/class_4227;method_19176(F)V",
                     shift = At.Shift.AFTER))
    private void s1mp1e$capturePanorama(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MenuBackdrop.capture();
    }
}
