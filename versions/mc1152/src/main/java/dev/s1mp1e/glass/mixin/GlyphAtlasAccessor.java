package dev.s1mp1e.glass.mixin;

import net.minecraft.client.font.GlyphAtlasTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link GlyphAtlasTexture}'s {@code private final boolean hasColor} (javap-verified on
 * yarn 1.15.2+build.17) so {@link FontSmoothMixin} can tell a TTF glyph atlas (grayscale,
 * {@code hasColor == false}) from an RGBA bitmap-font atlas ({@code hasColor == true}). Only the
 * TTF atlases are switched to LINEAR filtering; vanilla pixel fonts stay crisp.
 */
@Mixin(GlyphAtlasTexture.class)
public interface GlyphAtlasAccessor {
    @Accessor("hasColor") boolean s1mp1e$hasColor();
}
