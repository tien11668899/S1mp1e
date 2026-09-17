package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Companion to {@link FontSmoothMixin} for WORLD-SPACE text (name tags, signs, text displays).
 *
 * <p>GUI/HUD text picks its sampler per glyph in {@code GlyphRenderState.textureSetup()} (handled by
 * {@link FontSmoothMixin}), but world text samples the atlas's own {@code FontTexture.sampler}, which the
 * constructor creates with {@code SamplerCache.getRepeat(FilterMode.NEAREST)}. mc1211 smoothed both paths
 * through {@code setFilter}; this restores parity by switching that one argument to LINEAR.
 *
 * <p>Scope: {@code FontTexture} is only ever a font glyph atlas, so no other texture is affected. Verified with
 * javap -c against the 26.2 client jar: the single constructor
 * {@code (Supplier, GlyphRenderTypes, boolean)} calls {@code SamplerCache.getRepeat(FilterMode)} exactly once
 * (offset 73, after the {@code AbstractTexture} super call), its result stored into {@code sampler}.
 */
@Mixin(FontTexture.class)
public class FontTextureSmoothMixin {

    @ModifyArg(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/SamplerCache;getRepeat(Lcom/mojang/blaze3d/textures/FilterMode;)Lcom/mojang/blaze3d/textures/GpuSampler;"),
            index = 0)
    private FilterMode s1mp1e$linearWorldText(FilterMode filter) {
        return FilterMode.LINEAR;
    }
}
