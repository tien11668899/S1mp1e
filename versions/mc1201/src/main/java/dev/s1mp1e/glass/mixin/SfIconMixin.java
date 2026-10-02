package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.SfIcons;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the game's interactive glyphs (ticks, crosses, page arrows, the recipe filter, …) for Apple SF Symbols — see
 * {@link SfIcons}.
 *
 * <p>1.20.1 has no {@code drawGuiTexture}: these glyphs are regions of atlas textures. Every public
 * {@code DrawContext.drawTexture} overload (verified in the decompiled 1.20.1 class) ends in the package-private
 * {@code drawTexture(Identifier, x1, x2, y1, y2, z, regionWidth, regionHeight, u, v, textureWidth, textureHeight)},
 * so one HEAD hook there sees every one of them. Only an UNSCALED draw (rectangle size == region size) of an exactly
 * listed region ({@link SfIcons#atlasSprite}) is replaced; everything else falls through untouched.
 */
@Mixin(DrawContext.class)
public abstract class SfIconMixin {

    @Inject(method = "drawTexture(Lnet/minecraft/util/Identifier;IIIIIIIFFII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$sfIcon(Identifier texture, int x1, int x2, int y1, int y2, int z, int regionWidth, int regionHeight,
                               float u, float v, int textureWidth, int textureHeight, CallbackInfo ci) {
        if (x2 - x1 != regionWidth || y2 - y1 != regionHeight) return;
        if (!"minecraft".equals(texture.getNamespace())) return;
        String sprite = SfIcons.atlasSprite(texture.getPath(), u, v, regionWidth, regionHeight, textureWidth);
        if (sprite == null) return;
        if (SfIcons.draw((DrawContext) (Object) this, sprite, x1, y1, regionWidth, regionHeight)) ci.cancel();
    }
}
