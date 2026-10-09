package dev.s1mp1e.client.mixin;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes TTF text (our PingFang) looking "eaten": vanilla applies the glyph coverage TWICE.
 *
 * <p>TTF glyphs live in a 1-channel coverage atlas and draw through the {@code IS_GRAYSCALE} variant of
 * {@code core/text}, which samples {@code texture(...).rrrr} — so the colour channels are multiplied by the coverage
 * as well as the alpha. Every text pipeline blends {@code TRANSLUCENT} (colour = SRC_ALPHA, ONE_MINUS_SRC_ALPHA), so a
 * stroke edge with coverage {@code c} paints {@code c * c} of the text colour over {@code (1 - c)} of the background:
 * a half-covered edge of white text shows 25 % white instead of 50 %. On the dark glass every anti-aliased edge goes
 * near-black and thin strokes look gnawed away. Vanilla's own bitmap font is fully opaque per texel, so only TTF
 * text is hit.
 *
 * <p>The fix keeps the colour at the vertex colour and puts the coverage in alpha only ({@code vec4(1, 1, 1, r)}),
 * which is the correct single-coverage blend for a SRC_ALPHA pipeline. GUI text also drops the {@code < 0.1}
 * discard to {@code < 0.01}, so faint edge texels (and text mid fade-in) aren't cut. World/see-through text keeps
 * vanilla's 0.1 discard (it guards depth there).
 *
 * <p>26.3 compiles pipelines from TWO shader sources: {@code GameRenderer$1} preloads the startup/GUI pipelines
 * straight from the resource manager, and {@code ShaderManager$Configs} serves the reload (the text pipelines come from
 * here in practice). Both implement {@code ShaderSource.getShader}, so both are patched.
 * Only vanilla's exact lines are patched, so a resource pack or shader mod that replaced {@code text.fsh} is left
 * alone. Dev toggle: {@code -Ds1mp1e.font.textfix=false}.
 */
@Mixin(targets = {"net.minecraft.client.renderer.GameRenderer$1", "net.minecraft.client.renderer.ShaderManager$Configs"})
public class TextShaderCoverageFixMixin {
    @Unique private static final boolean S1MP1E$ENABLED = !"false".equalsIgnoreCase(System.getProperty("s1mp1e.font.textfix"));
    @Unique private static final String S1MP1E$GRAY = "texture(Sampler0, texCoord0).rrrr";
    @Unique private static final String S1MP1E$DISCARD = "if (color.a < 0.1) {";
    @Unique private static boolean S1MP1E$LOGGED;

    @Inject(method = "getShader", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$fixTextCoverage(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        if (!S1MP1E$ENABLED || type != ShaderType.FRAGMENT) return;
        if (!"minecraft".equals(id.getNamespace()) || !"core/text".equals(id.getPath())) return;
        String src = cir.getReturnValue();
        if (src == null || !src.contains(S1MP1E$GRAY)) return;
        String out = src.replace(S1MP1E$GRAY, "vec4(1.0, 1.0, 1.0, texture(Sampler0, texCoord0).r)");
        if (out.contains(S1MP1E$DISCARD)) {
            out = out.replace(S1MP1E$DISCARD,
                    "\n#ifdef IS_GUI\n    if (color.a < 0.01) {\n#else\n    if (color.a < 0.1) {\n#endif\n");
        }
        if (!S1MP1E$LOGGED) { S1MP1E$LOGGED = true; System.out.println("[S1mp1e] text shader: single-coverage TTF fix applied"); }
        cir.setReturnValue(out);
    }
}
