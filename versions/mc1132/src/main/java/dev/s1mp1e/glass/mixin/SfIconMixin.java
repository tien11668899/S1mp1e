package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.glass.render.SfIcons;
import net.minecraft.client.gui.DrawableHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the game's interactive glyphs (ticks, crosses, page arrows, the recipe filter, …) for Apple SF Symbols — see
 * {@link SfIcons}.
 *
 * <p>1.13.2 has neither {@code drawGuiTexture} nor a {@code DrawContext}: these glyphs are regions of atlas textures
 * the caller binds and then draws through {@link DrawableHelper}. There is no single blit funnel yet (1.14's
 * {@code innerBlit}): the instance {@code drawTexture(IIIIII)} (a 256 px atlas), its float-position twin and the two
 * public statics each build their own quad, so each one gets the same HEAD hook. Only an UNSCALED draw (rectangle
 * size == region size) of an exactly listed region of the BOUND atlas ({@link SfIcons#atlasSpriteBound}) is replaced;
 * everything else falls through untouched (an ordinary blit costs one failed map lookup).
 */
@Mixin(DrawableHelper.class)
public abstract class SfIconMixin {

    private static boolean s1mp1e$icon(int x, int y, float u, float v, int w, int h, int texW) {
        String sprite = SfIcons.atlasSpriteBound(u, v, w, h, texW);
        return sprite != null && SfIcons.draw(sprite, x, y, w, h);
    }

    @Inject(method = "drawTexture(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$sfIcon(int x, int y, int u, int v, int width, int height, CallbackInfo ci) {
        if (s1mp1e$icon(x, y, u, v, width, height, 256)) ci.cancel();
    }

    @Inject(method = "drawTexture(IIFFIIFF)V", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$sfIconS(int x, int y, float u, float v, int width, int height, float texW, float texH,
                                       CallbackInfo ci) {
        if (s1mp1e$icon(x, y, u, v, width, height, Math.round(texW))) ci.cancel();
    }

    @Inject(method = "drawTexture(IIFFIIIIFF)V", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$sfIconR(int x, int y, float u, float v, int regionWidth, int regionHeight, int width,
                                       int height, float texW, float texH, CallbackInfo ci) {
        if (width != regionWidth || height != regionHeight) return;
        if (s1mp1e$icon(x, y, u, v, width, height, Math.round(texW))) ci.cancel();
    }
}
