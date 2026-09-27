package dev.s1mp1e.glass.mixin;

import net.minecraft.client.texture.Sprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes a 1.13.2 {@link Sprite}'s CPU-side pixel reader so {@link dev.s1mp1e.client.module.Silhouette}
 * can read an item icon's alpha mask (its silhouette). The 1.13.2 counterpart of mc1144's
 * {@code SpriteAccessor} and mc1211's {@code SpriteContentsAccessor}.
 *
 * <p><b>Verified with javap against legacy yarn 1.13.2+build.604-v2:</b> the reader is UNMAPPED,
 * {@code private int method_19518(int frame, int mip, int x, int y)}. Its body is
 * {@code field_21028[mip].method_19459(x + field_21029[frame] * width >> mip,
 * y + field_21030[frame] * height >> mip)} — {@code field_21028} is the per-mip
 * {@code NativeImage[]} ({@code class_4277}) and {@code method_19459} is {@code getPixelRgba}, packed
 * ABGR with alpha in the top byte. The images are kept for the whole session and only cleared by a
 * re-stitch ({@code SpriteAtlasTexture.method_19516}) and on shutdown, so a read after the GPU upload
 * is valid. {@link dev.s1mp1e.client.module.Silhouette} calls it with {@code frame=0, mip=0} and reads
 * only the top-byte alpha.
 *
 * <p>An accessor INTERFACE may be referenced from normal code (unlike a mixin class), which is how
 * Silhouette casts the sprite to it.
 */
@Mixin(Sprite.class)
public interface SpriteAccessor {
    @Invoker("method_19518") int s1mp1e$framePixel(int frame, int mip, int x, int y);
}
