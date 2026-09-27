package dev.s1mp1e.glass.mixin;

import net.minecraft.client.texture.Sprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes a 1.14.4 {@link Sprite}'s CPU-side pixel reader so {@link dev.s1mp1e.client.module.Silhouette}
 * can read an icon's alpha mask (its silhouette). Replaces mc1211's {@code SpriteContentsAccessor}: 1.14.4
 * has no {@code SpriteContents} — the sprite keeps its protected {@code NativeImage[] images} for the whole
 * session and reads a pixel through its private {@code getFramePixel}.
 *
 * <p><b>Signature note (verified with javap against yarn 1.14.4+build.18):</b> on 1.14.4 the method is
 * {@code private int getFramePixel(int frameIndex, int mipLevel, int x, int y)} — FOUR ints, not the three
 * of the plan's API note. It indexes {@code frameXs[frameIndex] / frameYs[frameIndex]} for the animation
 * frame, {@code images[mipLevel]} for the mip, and returns {@code NativeImage.getPixelRgba} which is packed
 * ABGR (alpha = top byte). {@link dev.s1mp1e.client.module.Silhouette} calls it with {@code frame=0, mip=0}
 * and reads only the top-byte alpha, so the exact channel order below alpha is irrelevant.
 */
@Mixin(Sprite.class)
public interface SpriteAccessor {
    @Invoker("getFramePixel") int s1mp1e$framePixel(int frameIndex, int mipLevel, int x, int y);
}
