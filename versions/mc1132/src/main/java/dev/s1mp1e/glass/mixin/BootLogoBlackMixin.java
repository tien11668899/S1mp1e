package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 1.13.2 boot frame. {@code class_4158} is the Mojang-logo screen: {@code initializeGame} draws exactly one frame
 * of it (white, red logo) into the window and then blocks until the game is loaded. Brand screens are pure black, and
 * the brand intro that follows the load starts on black: the one frame is a plain black clear instead.
 */
@Mixin(net.minecraft.class_4158.class)
public abstract class BootLogoBlackMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$black(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        GlStateManager.clearColor(0.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.clear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
        ci.cancel();
    }
}
