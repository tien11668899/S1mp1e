package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ContainerBodyBlit;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single funnel of every GUI texture blit in 1.16.5: all public {@code DrawableHelper.drawTexture} overloads (the
 * 7-arg instance one containers use, the 9/10/11-arg statics — the merchant's {@code z, 256x512} body blit included)
 * end in the private static {@code drawTexture(MatrixStack, x0, x1, y0, y1, z, regionW, regionH, u, v, texW, texH)}
 * (javap-verified, yarn 1.16.5+build.10). While {@link ContainerBodyBlit} has a generic container's
 * {@code drawBackground} window open, a full-width body strip is replaced by the glass panel and dropped; everything
 * else — and every blit outside that one-call window — is a strict pass-through (one static boolean test).
 */
@Mixin(DrawableHelper.class)
public abstract class DrawableHelperBodyBlitMixin {

    @Inject(method = "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIIIIFFII)V",
            at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$bodyBlit(MatrixStack matrices, int x0, int x1, int y0, int y1, int z,
                                        int regionWidth, int regionHeight, float u, float v,
                                        int textureWidth, int textureHeight, CallbackInfo ci) {
        if (ContainerBodyBlit.intercept(x0, x1, y0, y1)) ci.cancel();
    }
}
