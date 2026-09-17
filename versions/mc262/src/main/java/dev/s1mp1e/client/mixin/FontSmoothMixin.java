package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Anti-aliases the TTF font in GUI/HUD text (26.2 port of the 1.21.1 FontSmoothMixin).
 *
 * <p>26.2 no longer calls {@code AbstractTexture.setFilter}. GUI text is drawn through
 * {@link GlyphRenderState#textureSetup()}, which hard-codes
 * {@code RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)} for every glyph. Our
 * PingFang glyphs are baked by FreeType at {@code size x oversample} (9x8 = 72px) and shown at ~9px,
 * so NEAREST minification throws away FreeType's grayscale anti-aliasing (the jagged edges).
 *
 * <p>Fix: swap that argument to {@link FilterMode#LINEAR}, but ONLY when the glyph comes from a
 * font glyph atlas ({@code FontTexture}). Every glyph stitched into a FontTexture is a
 * {@code BakedSheetGlyph$GlyphInstance} or {@code BakedSheetGlyph$EffectInstance} (both
 * package-private nested records of {@link BakedSheetGlyph}; {@code BakedSheetGlyph} is only ever
 * constructed in {@code FontTexture.add}). Atlas-sprite glyphs ({@code AtlasGlyphProvider$Instance})
 * and player-head glyphs ({@code PlayerGlyphProvider$Instance}) are other classes and keep NEAREST,
 * as does every non-text texture. Same scope as 1.21.1 (all glyph atlases, incl. the vanilla bitmap
 * font, which the 1.21.1 instanceof GlyphAtlasTexture check also covered).
 *
 * <p>Verified against the 26.2 client jar with javap -c:
 * GlyphRenderState.textureSetup()Lnet/minecraft/client/gui/render/TextureSetup; contains
 * {@code invokevirtual SamplerCache.getClampToEdge:(Lcom/mojang/blaze3d/textures/FilterMode;)Lcom/mojang/blaze3d/textures/GpuSampler;}
 * exactly once.
 */
@Mixin(GlyphRenderState.class)
public class FontSmoothMixin {

    // Tiny identity cache (no anonymous/inner classes inside the mixin). Render thread only.
    private static Class<?> s1mp1e$yes0, s1mp1e$yes1, s1mp1e$no0, s1mp1e$no1;

    /** True if the TextRenderable is a nest member of BakedSheetGlyph, i.e. a font-atlas glyph. */
    private static boolean s1mp1e$isFontAtlasGlyph(Class<?> c) {
        if (c == s1mp1e$yes0 || c == s1mp1e$yes1) return true;
        if (c == s1mp1e$no0 || c == s1mp1e$no1) return false;
        // NOTE: do NOT use getEnclosingClass()/getDeclaringClass(): the 26.2 jar's InnerClasses
        // attribute on BakedSheetGlyph$GlyphInstance/$EffectInstance has no self-entry (verified with
        // javap -v), so those return null. The NestHost/NestMembers attributes ARE present, so use
        // getNestHost(); the binary-name prefix is a belt-and-braces fallback.
        boolean yes = c.getNestHost() == BakedSheetGlyph.class
                || c.getName().startsWith("net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$");
        if (yes) { s1mp1e$yes1 = s1mp1e$yes0; s1mp1e$yes0 = c; }
        else     { s1mp1e$no1 = s1mp1e$no0;   s1mp1e$no0 = c; }
        return yes;
    }

    @ModifyArg(
            method = "textureSetup()Lnet/minecraft/client/gui/render/TextureSetup;",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/SamplerCache;getClampToEdge(Lcom/mojang/blaze3d/textures/FilterMode;)Lcom/mojang/blaze3d/textures/GpuSampler;"),
            index = 0)
    private FilterMode s1mp1e$linearFontAtlas(FilterMode filter) {
        Object renderable = ((GlyphRenderState) (Object) this).renderable();
        if (renderable != null && s1mp1e$isFontAtlasGlyph(renderable.getClass())) {
            return FilterMode.LINEAR;   // LINEAR for font glyph atlases -> smooth AA instead of jaggies
        }
        return filter;
    }
}
