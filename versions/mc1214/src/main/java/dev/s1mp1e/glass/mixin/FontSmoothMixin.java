package dev.s1mp1e.glass.mixin;

import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Anti-aliases the TTF font. MC's text render phase calls
 * {@code AbstractTexture.setFilter(bilinear=false, mipmap=false)} on every draw, so the glyph
 * atlas is sampled with {@code GL_NEAREST}. That is fine for the vanilla 1:1 bitmap font, but our
 * PingFang glyphs are baked by FreeType at {@code size × oversample} (e.g. 9×8 = 72px) and then
 * displayed at ~9px — a nearest-neighbour minification that throws away all of FreeType's grayscale
 * anti-aliasing, which is exactly the "鋸齒/jagged" edges.
 *
 * <p>Fix: force {@code bilinear = true} (GL_LINEAR) but ONLY for {@link GlyphAtlasTexture}, so the
 * 72px→9px downsample is a smooth average = crisp anti-aliased text. Verified: {@code setFilter(ZZ)V}
 * on {@code AbstractTexture} (class_1044) in yarn 1.21.1+build.3; scoped by an instanceof check so no
 * other texture (blocks, GUI, our glass) is touched.
 */
@Mixin(AbstractTexture.class)
public class FontSmoothMixin {

    @ModifyVariable(method = "setFilter(ZZ)V", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private boolean s1mp1e$linearFontAtlas(boolean bilinear) {
        if ((Object) this instanceof GlyphAtlasTexture) {
            return true;   // LINEAR filtering for the font atlas -> smooth AA instead of jaggies
        }
        return bilinear;
    }
}
