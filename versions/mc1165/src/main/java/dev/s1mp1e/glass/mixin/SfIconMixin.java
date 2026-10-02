package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.SfIcons;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the game's interactive glyphs (ticks, crosses, page arrows, the recipe filter, …) for Apple SF Symbols — see
 * {@link SfIcons}.
 *
 * <p>1.16.5 has neither {@code drawGuiTexture} nor a {@code DrawContext}: these glyphs are regions of atlas textures
 * the caller binds with {@code RenderSystem.setShaderTexture(0, id)} and then draws through {@link DrawableHelper}.
 * Every {@code DrawableHelper.drawTexture} overload (javap-verified on the 1.16.5 class: the instance 6-int one and
 * the three public static ones) ends in the private static
 * {@code drawTexture(MatrixStack, x0, x1, y0, y1, z, regionWidth, regionHeight, u, v, textureWidth, textureHeight)},
 * so one HEAD hook there sees every one of them. Only an UNSCALED draw (rectangle size == region size) of an exactly
 * listed region of the BOUND atlas ({@link SfIcons#atlasSpriteBound}) is replaced; everything else falls through
 * untouched (the geometry test comes first, so an ordinary blit costs one failed map lookup).
 */
@Mixin(DrawableHelper.class)
public abstract class SfIconMixin {

    @Inject(method = "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIIIIFFII)V", at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$sfIcon(MatrixStack matrices, int x0, int x1, int y0, int y1, int z, int regionWidth,
                                      int regionHeight, float u, float v, int textureWidth, int textureHeight,
                                      CallbackInfo ci) {
        if (x1 - x0 != regionWidth || y1 - y0 != regionHeight) return;
        String sprite = SfIcons.atlasSpriteBound(u, v, regionWidth, regionHeight, textureWidth);
        if (sprite == null) return;
        if (SfIcons.draw(matrices, sprite, x0, y0, regionWidth, regionHeight)) ci.cancel();
    }
}
