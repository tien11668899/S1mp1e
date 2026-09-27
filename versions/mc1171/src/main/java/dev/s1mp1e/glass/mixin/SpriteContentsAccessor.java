package dev.s1mp1e.glass.mixin;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a sprite's CPU-side {@link NativeImage}s so the ArmorHUD / PotionHUD can read an item or
 * effect icon's alpha mask (its silhouette).
 *
 * <p><b>1.19.2 note.</b> There is no {@code SpriteContents} on 1.19.2 (that class arrived in 1.19.3):
 * the frames live directly on {@link Sprite} as {@code protected final NativeImage[] images}, verified
 * in yarn 1.19.2+build.28. {@code images[0]} holds the icon (all mip levels / frames), like 1.20's
 * {@code SpriteContents.image}. The class name is kept so callers read the same
 * {@code SpriteContentsAccessor} type across versions.
 */
@Mixin(Sprite.class)
public interface SpriteContentsAccessor {
    @Accessor("images") NativeImage[] s1mp1e$images();
}
