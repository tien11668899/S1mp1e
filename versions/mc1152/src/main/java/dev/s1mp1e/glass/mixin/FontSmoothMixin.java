package dev.s1mp1e.glass.mixin;

import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Anti-aliases the whole-game PingFang TTF on 1.15.2. The glyphs are baked by STB at
 * {@code size x oversample} (12 x 8) and displayed at ~9 px, so NEAREST minification throws
 * away the grayscale anti-aliasing and the text stair-steps.
 *
 * <p>Why not mc1144's {@code GlyphAtlasSmoothMixin} (a one-time {@code setFilter(true,false)} at
 * {@code GlyphAtlasTexture.<init>} RETURN)? In 1.15.2 the text {@code RenderLayer}'s
 * {@code RenderPhase$Texture} start action calls {@code TextureManager.bindTexture(id)} and then
 * {@code getTexture(id).setFilter(false, false)} on EVERY text draw (javap-verified), which would
 * undo the one-time switch on the very first draw. So, like mc1165/mc1201, we rewrite the
 * {@code bilinear} argument of {@code AbstractTexture.setFilter(ZZ)V} at HEAD — but scoped to TTF
 * glyph atlases only ({@code hasColor == false} via {@link GlyphAtlasAccessor}), so RGBA bitmap
 * font atlases keep their sharp NEAREST filter (mc1144 semantics). {@code GlyphAtlasTexture} does
 * not override {@code setFilter}. Guarded: any failure returns the original argument.
 */
@Mixin(AbstractTexture.class)
public class FontSmoothMixin {

    @ModifyVariable(method = "setFilter(ZZ)V", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private boolean s1mp1e$linearTtfAtlas(boolean bilinear) {
        try {
            if ((Object) this instanceof GlyphAtlasTexture
                    && !((GlyphAtlasAccessor) (Object) this).s1mp1e$hasColor()) {
                return true;   // LINEAR for the TTF glyph atlas -> smooth AA instead of jaggies
            }
        } catch (Throwable ignored) {
            // leave the filter exactly as the caller asked
        }
        return bilinear;
    }
}
