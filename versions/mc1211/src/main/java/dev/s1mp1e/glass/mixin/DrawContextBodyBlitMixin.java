package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ContainerGlass;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops ONLY the container body-texture blit while {@code HandledScreenGlassMixin} runs a generic container's own
 * {@code drawBackground} over its glass panel (see {@link ContainerGlass#beginBodySuppress}) — the 1.21.1 equivalent of
 * 26.2's {@code ContainerScreensGlassMixin} single-blit redirect, generalised over every container screen.
 *
 * <p>Seam (verified from the decompiled 1.21.1 {@code DrawContext}): every public {@code drawTexture(Identifier, ...)}
 * overload funnels into the package-private {@code drawTexture(Identifier, int x1, int x2, int y1, int y2, int z,
 * int regionW, int regionH, float u, float v, int texW, int texH)}. A blit there is cancelled only while a suppression
 * window is open AND it is a full-width strip of that panel; everything else (and every other screen, HUD, menu) is a
 * strict pass-through — the window is open only for the duration of one generic drawBackground call.
 */
@Mixin(DrawContext.class)
public abstract class DrawContextBodyBlitMixin {

    @Inject(method = "drawTexture(Lnet/minecraft/util/Identifier;IIIIIIIFFII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$dropContainerBody(Identifier texture, int x1, int x2, int y1, int y2, int z,
                                          int regionWidth, int regionHeight, float u, float v,
                                          int textureWidth, int textureHeight, CallbackInfo ci) {
        if (ContainerGlass.isSuppressedBody(x1, x2, y1, y2)) ci.cancel();
    }
}
