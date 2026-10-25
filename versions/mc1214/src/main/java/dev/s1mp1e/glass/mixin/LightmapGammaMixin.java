package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.FullbrightModule;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Fullbright: inside {@code LightmapTextureManager.update} (method_3313, verified yarn
 * 1.21.1+build.3) the brightness curve reads the gamma option via
 * {@code options.getGamma().getValue()}. We {@link Redirect} exactly that
 * {@code SimpleOption.getValue()} call — pinned with a {@link Slice} that starts at the
 * {@code GameOptions.getGamma()} invoke so no other option read is touched — and return an
 * over-bright gamma while the module is on (the vanilla option itself is clamped to 0..1, so
 * boosting here is the only way to exceed "Bright"). {@code update} clamps the final colour, so
 * a large value saturates to full brightness with no artefacts.
 */
@Mixin(LightmapTextureManager.class)
public class LightmapGammaMixin {

    @Redirect(
        method = "update(F)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/option/SimpleOption;getValue()Ljava/lang/Object;"),
        slice = @Slice(from = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/option/GameOptions;getGamma()Lnet/minecraft/client/option/SimpleOption;")))
    private Object s1mp1e$fullbright(SimpleOption<?> option) {
        Object v = option.getValue();
        if (FullbrightModule.active() && v instanceof Number) {
            return Double.valueOf(Math.max(((Number) v).doubleValue(), FullbrightModule.level()));
        }
        return v;
    }
}
