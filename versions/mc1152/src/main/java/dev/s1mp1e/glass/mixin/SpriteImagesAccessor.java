package dev.s1mp1e.glass.mixin;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a sprite's CPU-side {@link NativeImage} mip chain so {@link dev.s1mp1e.client.module.Silhouette}
 * can read an item / effect icon's alpha mask (its silhouette). On 1.15.2 there is no {@code SpriteContents} and no {@code getFramePixel};
 * the {@code protected final NativeImage[] images} lives on {@code Sprite} itself (mip level 0 = index 0).
 */
@Mixin(Sprite.class)
public interface SpriteImagesAccessor {
    @Accessor("images") NativeImage[] s1mp1e$images();
}
