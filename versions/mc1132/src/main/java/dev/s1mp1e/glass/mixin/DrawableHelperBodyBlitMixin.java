package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ContainerBodyBlit;
import dev.s1mp1e.glass.render.ContainerExtras;
import net.minecraft.client.gui.DrawableHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every GUI texture blit of 1.13.2. {@code DrawableHelper} has no single funnel yet (the private {@code innerBlit}
 * is 1.14): the instance {@code drawTexture(IIIIII)} containers use, its float-position twin and the two public
 * statics (the {@code texW x texH} forms) each build their own quad, so each one gets the same HEAD hook
 * (javap-verified, legacy yarn 1.13.2+build.604). While {@link ContainerBodyBlit} has a generic container's
 * {@code drawBackground} window open, a full-width body strip is replaced by the glass panel and dropped; everything
 * else — and every blit outside that one-call window — is a strict pass-through (one static boolean test). Inside the
 * window the remaining blits of a vanilla container texture are drawn from a keyed copy without the panel grey
 * ({@link ContainerExtras}).
 */
@Mixin(DrawableHelper.class)
public abstract class DrawableHelperBodyBlitMixin {

    private static boolean s1mp1e$body(int x0, int x1, int y0, int y1) {
        if (ContainerBodyBlit.intercept(x0, x1, y0, y1)) return true;
        // Every other blit of a vanilla container texture inside the window (flame, progress arrow, bubbles, error
        // cross, mount slot art) uses a copy whose opaque panel-grey pixels are transparent, so the piece does not sit
        // in a grey box on the glass (ContainerExtras; 1.13.2 blits whatever texture is BOUND, so the bound texture is
        // swapped). A no-op outside the window and for any other texture.
        if (ContainerBodyBlit.open()) ContainerExtras.rebindKeyed();
        return false;
    }

    @Inject(method = "drawTexture(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$bodyBlit(int x, int y, int u, int v, int width, int height, CallbackInfo ci) {
        if (s1mp1e$body(x, x + width, y, y + height)) ci.cancel();
    }

    @Inject(method = "drawTexture(FFIIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$bodyBlitF(float x, float y, int u, int v, int width, int height, CallbackInfo ci) {
        int x0 = Math.round(x), y0 = Math.round(y);
        if (s1mp1e$body(x0, x0 + width, y0, y0 + height)) ci.cancel();
    }

    @Inject(method = "drawTexture(IIFFIIFF)V", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$bodyBlitS(int x, int y, float u, float v, int width, int height, float texW, float texH,
                                         CallbackInfo ci) {
        if (s1mp1e$body(x, x + width, y, y + height)) ci.cancel();
    }

    @Inject(method = "drawTexture(IIFFIIIIFF)V", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$bodyBlitR(int x, int y, float u, float v, int regionWidth, int regionHeight, int width,
                                         int height, float texW, float texH, CallbackInfo ci) {
        if (s1mp1e$body(x, x + width, y, y + height)) ci.cancel();
    }
}
