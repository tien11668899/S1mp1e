package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.module.FullbrightModule;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Fullbright: inside {@code LightmapTextureManager.update(F)V} (yarn 1.19.2+build.28) the brightness
 * curve reads the gamma option via {@code options.getGamma().getValue()}. We wrap exactly that
 * {@code SimpleOption.getValue()} call, pinned with a {@link Slice} that starts at the
 * {@code GameOptions.getGamma()} invoke (javap-verified: bc 598 getGamma / bc 601 getValue is the ONLY
 * getValue in the slice; the earlier bc 75/78 getValue reads getDarknessEffectScale and is outside it),
 * and return an over-bright gamma while the module is on. The vanilla option itself is clamped to 0..1,
 * so boosting here is the only way to exceed "Bright"; {@code update} clamps the final colour, so a large
 * value saturates to full brightness with no artefacts.
 *
 * <p>1.19.2 port: {@code @WrapOperation} (MixinExtras 0.3.5, bundled with Fabric Loader 0.15.11) instead
 * of the reference's {@code @Redirect}, so another mod wrapping the same call still composes with ours.
 * The original call always runs; a failure in our part returns the vanilla value untouched.
 */
@Mixin(LightmapTextureManager.class)
public class LightmapGammaMixin {

    @WrapOperation(
        method = "update(F)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/option/SimpleOption;getValue()Ljava/lang/Object;"),
        slice = @Slice(from = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/option/GameOptions;getGamma()Lnet/minecraft/client/option/SimpleOption;")))
    private Object s1mp1e$fullbright(SimpleOption<?> option, Operation<Object> original) {
        Object v = original.call(option);
        try {
            if (FullbrightModule.active() && v instanceof Number) {
                return Double.valueOf(Math.max(((Number) v).doubleValue(), FullbrightModule.level()));
            }
        } catch (Throwable ignored) {
            // fall back to the vanilla gamma
        }
        return v;
    }
}
