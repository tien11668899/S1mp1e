package dev.s1mp1e.glass.mixin;

import java.util.function.BiFunction;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.client.gl.GlBackend;
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
 * the correct single-coverage blend for a SRC_ALPHA layer. The {@code < 0.1} discard is left as is: the shader has no
 * GUI/world split, and world text relies on it for depth.
 *
 * <p>1.21.5 compiles shaders in the GPU device ({@code GlBackend.compileShader(id, type, defines, sources)}), which
 * reads each stage's source through the {@code sources} function; that function is wrapped so the text stages are
 * patched on their way in (1.21.2-1.21.4: {@code CompiledShader.compile}; 1.21.1: {@code ShaderStage.load}). Only
 * vanilla's exact line is patched, so a resource pack or shader mod that replaced the shader is left alone. Dev
 * toggle: {@code -Ds1mp1e.font.textfix=false}.
 */
@Mixin(GlBackend.class)
public class TextShaderCoverageFixMixin {
    @Unique private static final boolean S1MP1E$ENABLED = !"false".equalsIgnoreCase(System.getProperty("s1mp1e.font.textfix"));
    @Unique private static final String S1MP1E$GRAY = "texture(Sampler0, texCoord0).rrrr";
    @Unique private static boolean S1MP1E$LOGGED;

    @ModifyVariable(method = "compileShader(Lnet/minecraft/util/Identifier;Lcom/mojang/blaze3d/shaders/ShaderType;"
                           + "Lnet/minecraft/client/gl/Defines;Ljava/util/function/BiFunction;)"
                           + "Lnet/minecraft/client/gl/CompiledShader;",
            at = @At("HEAD"), argsOnly = true)
    private BiFunction<Identifier, ShaderType, String> s1mp1e$fixTextCoverage(BiFunction<Identifier, ShaderType, String> sources) {
        if (!S1MP1E$ENABLED || sources == null) return sources;
        return (id, type) -> s1mp1e$patch(id, type, sources.apply(id, type));
    }

    @Unique
    private static String s1mp1e$patch(Identifier id, ShaderType type, String src) {
        if (type != ShaderType.FRAGMENT || src == null || id == null) return src;
        if (!id.toString().contains("rendertype_text_intensity") || !src.contains(S1MP1E$GRAY)) return src;
        if (!S1MP1E$LOGGED) { S1MP1E$LOGGED = true; System.out.println("[S1mp1e] text shader: single-coverage TTF fix applied"); }
        return src.replace(S1MP1E$GRAY, "vec4(1.0, 1.0, 1.0, texture(Sampler0, texCoord0).r)");
    }
}
