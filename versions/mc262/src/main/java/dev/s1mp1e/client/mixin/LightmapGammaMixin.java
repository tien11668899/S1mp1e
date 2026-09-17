package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.FullbrightModule;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Fullbright (26.2). The lightmap is now built on the GPU: {@code LightmapRenderStateExtractor.extract}
 * reads {@code options.gamma().get()} into {@code LightmapRenderState.brightness}, which {@code Lightmap}
 * uploads as the {@code BrightnessFactor} uniform of {@code lightmap.fsh}
 * ({@code color = mix(clamp(color), notGamma(color), BrightnessFactor)}; the result is written to an
 * 8-bit lightmap target, so an over-bright factor saturates cleanly — the same effect as mc1211's
 * CPU-side boost inside {@code LightmapTextureManager.update}).
 *
 * <p>We {@link Redirect} exactly that {@code OptionInstance.get()} call: the {@link Slice} starts at the
 * {@code Options.gamma()} invoke and {@code ordinal = 0} picks the first {@code get()} after it, so the
 * {@code hideLightningFlash} read before it and the {@code darknessEffectScale} read after it are untouched.
 * Only the value used for rendering is raised; the stored option stays 0..1.
 */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapGammaMixin {

    @Redirect(
        method = "extract(Lnet/minecraft/client/renderer/state/LightmapRenderState;F)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;",
                 ordinal = 0),
        slice = @Slice(from = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/Options;gamma()Lnet/minecraft/client/OptionInstance;")))
    private Object s1mp1e$fullbright(OptionInstance<?> option) {
        Object v = option.get();
        if (FullbrightModule.active() && v instanceof Number) {
            // Must stay a Double: vanilla casts the result with (Double) then calls floatValue().
            return Double.valueOf(Math.max(((Number) v).doubleValue(), FullbrightModule.level()));
        }
        return v;
    }
}
