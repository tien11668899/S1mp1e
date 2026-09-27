package dev.s1mp1e.glass.mixin;

import net.minecraft.client.font.GlyphAtlasTexture;
import net.minecraft.client.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Anti-aliases the TTF font (1.19.2, draw side; the reference {@code FontSmoothMixin} hook of the 1.20.1 /
 * 1.21.1 lines, same logic). Every text render layer's texture phase calls
 * {@code AbstractTexture.setFilter(bilinear=false, mipmap=false)} on the glyph atlas when it is flushed, so
 * the atlas is sampled with {@code GL_NEAREST}. We force {@code bilinear = true} (GL_LINEAR) but ONLY for
 * {@link GlyphAtlasTexture}, so no other texture (blocks, GUI, our glass) is touched.
 *
 * <p>javap-verified in yarn 1.19.2+build.28: {@code RenderLayer.getText/getTextSeeThrough/getTextPolygonOffset}
 * (and the intensity variants) build a {@code RenderPhase.Texture(atlasId, false, false)}, whose begin action
 * is {@code TextureManager.getTexture(id).setFilter(false, false)}; {@code FontStorage} registers each atlas
 * with the TextureManager, so this fires for glyph atlases on every text flush. Without it, the LINEAR that
 * {@link FontSmoothMixin} restores after a glyph upload would be reset to NEAREST before the text is drawn.
 */
@Mixin(AbstractTexture.class)
public class FontSmoothFilterMixin {

    @ModifyVariable(method = "setFilter(ZZ)V", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private boolean s1mp1e$linearFontAtlas(boolean bilinear) {
        if ((Object) this instanceof GlyphAtlasTexture) {
            return true;   // LINEAR filtering for the font atlas -> smooth AA instead of jaggies
        }
        return bilinear;
    }
}
