package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ContainerExtras;
import dev.s1mp1e.glass.render.ContainerGlass;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops ONLY the container body-texture blit while {@code HandledScreenGlassMixin} runs a generic container's own
 * {@code drawBackground} over its glass panel (see {@link ContainerGlass#beginBodySuppress}) — the 1.19.2 counterpart
 * of the newer lines' {@code DrawContextBodyBlitMixin} (26.2's {@code ContainerScreensGlassMixin} single-blit
 * redirect, generalised over every container screen).
 *
 * <p>Seam (javap-verified on the 1.19.2 {@code DrawableHelper}): every {@code drawTexture} overload — the instance
 * one the screens use for their body and the three public static ones (the merchant's 512-wide body) — funnels into
 * the private static {@code drawTexture(MatrixStack, x0, x1, y0, y1, z, regionW, regionH, u, v, texW, texH)}. A blit
 * there is cancelled only while a suppression window is open AND it is a full-width strip of that panel; everything
 * else (and every other screen, HUD, menu) is a strict pass-through — the window is open only for the duration of one
 * generic drawBackground call.
 *
 * <p>Inside the same window every other blit of a vanilla container texture (flame, progress arrow, bubbles, error
 * cross, …) uses a copy whose opaque panel-grey pixels are transparent, so the piece does not sit in a grey box on
 * the glass. 1.19.2 blits whatever texture is BOUND, so instead of swapping an identifier argument the bound texture
 * is swapped — see {@link ContainerExtras#rebindKeyed()}.
 */
@Mixin(DrawableHelper.class)
public abstract class DrawableBodyBlitMixin {

    @Inject(method = "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIIIIFFII)V", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$dropContainerBody(MatrixStack matrices, int x0, int x1, int y0, int y1, int z,
                                                 int regionWidth, int regionHeight, float u, float v,
                                                 int textureWidth, int textureHeight, CallbackInfo ci) {
        if (!ContainerGlass.suppressing()) return;
        if (ContainerGlass.isSuppressedBody(x0, x1, y0, y1)) {
            ci.cancel();
            return;
        }
        ContainerExtras.rebindKeyed();
    }
}
