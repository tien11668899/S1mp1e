package dev.s1mp1e.glass.mixin;

import net.minecraft.class_4277;
import net.minecraft.client.texture.Sprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a sprite's CPU-side {@link class_4277} mip chain so {@link dev.s1mp1e.client.module.Silhouette}
 * can read an item / effect icon's alpha mask (its silhouette). On 1.13.2 there is no {@code SpriteContents};
 * the {@code protected final class_4277[] images} lives on {@code Sprite} itself (mip level 0 = index 0).
 */
@Mixin(Sprite.class)
public interface SpriteImagesAccessor {
    @Accessor("field_21028") class_4277[] s1mp1e$images();
}
