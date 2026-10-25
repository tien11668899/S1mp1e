package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gl.CompiledShader;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Fixes TTF text (our PingFang) looking "eaten": vanilla applies the glyph coverage TWICE.
 *
 * <p>TTF glyphs live in a 1-channel coverage atlas and draw through {@code rendertype_text_intensity} (and its
 * see-through variant), which samples {@code texture(...).rrrr} — so the colour channels are multiplied by the
 * coverage as well as the alpha. The text layers blend {@code TRANSLUCENT_TRANSPARENCY} (colour = SRC_ALPHA,
 * ONE_MINUS_SRC_ALPHA), so a stroke edge with coverage {@code c} paints {@code c * c} of the text colour over
 * {@code (1 - c)} of the background: a half-covered edge of white text shows 25 % white instead of 50 %. On the dark
 * glass every anti-aliased edge goes near-black and thin strokes look gnawed away. Vanilla's own bitmap font uses
 * {@code rendertype_text} with fully opaque texels, so only TTF text is hit.
 *
 * <p>The fix keeps the colour at the vertex colour and puts the coverage in alpha only ({@code vec4(1, 1, 1, r)}),
 * the correct single-coverage blend for a SRC_ALPHA layer. The {@code < 0.1} discard is left as is: 1.21.1's shader
 * (1.21.4 too) has no GUI/world split, and world text relies on it for depth.
 *
 * <p>1.21.2+ compiles every stage through {@code CompiledShader.compile(id, type, source)} (1.21.1 read it in
 * {@code ShaderStage.load}), so the source is patched on its way in. Only
 * vanilla's exact line is patched, so a resource pack or shader mod that replaced the shader is left alone. Dev
 * toggle: {@code -Ds1mp1e.font.textfix=false}.
 */
@Mixin(CompiledShader.class)
public class TextShaderCoverageFixMixin {
    @Unique private static final boolean S1MP1E$ENABLED = !"false".equalsIgnoreCase(System.getProperty("s1mp1e.font.textfix"));
    @Unique private static final String S1MP1E$GRAY = "texture(Sampler0, texCoord0).rrrr";
    @Unique private static boolean S1MP1E$LOGGED;

    @ModifyVariable(method = "compile", at = @At("HEAD"), argsOnly = true)
    private static String s1mp1e$fixTextCoverage(String src, @Local(argsOnly = true) Identifier id,
                                                 @Local(argsOnly = true) CompiledShader.Type type) {
        if (!S1MP1E$ENABLED || type != CompiledShader.Type.FRAGMENT || src == null || id == null) return src;
        String name = id.toString();
        if (!name.contains("rendertype_text_intensity") || !src.contains(S1MP1E$GRAY)) return src;
        if (!S1MP1E$LOGGED) { S1MP1E$LOGGED = true; System.out.println("[S1mp1e] text shader: single-coverage TTF fix applied"); }
        return src.replace(S1MP1E$GRAY, "vec4(1.0, 1.0, 1.0, texture(Sampler0, texCoord0).r)");
    }
}
