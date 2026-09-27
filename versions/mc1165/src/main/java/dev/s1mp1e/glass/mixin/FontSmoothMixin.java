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
 * PingFang glyphs are baked by STB at {@code size × oversample} (9×8 = 72px) and then displayed at
 * ~9px — a nearest-neighbour minification that throws away the grayscale anti-aliasing, which is
 * exactly the jagged ("鋸齒") edges.
 *
 * <p>Fix: force {@code bilinear = true} (GL_LINEAR) but ONLY for {@link GlyphAtlasTexture}, so the
 * 72px→9px downsample is a smooth average = crisp anti-aliased text. {@code setFilter(ZZ)V} on
 * {@code AbstractTexture} (class_1044) and {@code GlyphAtlasTexture} (class_1057) are javap-verified
 * on yarn 1.16.5+build.10; the instanceof scope leaves every other texture (blocks, GUI, our glass)
 * untouched. 1.16.5 port of the 1.20.1 mixin — byte-identical apart from this doc note.
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
