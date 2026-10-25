package dev.s1mp1e.glass.mixin;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes a sprite's CPU-side {@link NativeImage} so the ArmorHUD can read an item icon's
 *  alpha mask (its silhouette). {@code image} = field_40539, verified in yarn 1.21.1+build.3. */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("image") NativeImage s1mp1e$image();
}
