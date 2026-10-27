package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.PackSky;
import net.minecraft.world.dimension.Dimension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The pack's sunset colour ({@link PackSky}): the sunrise / sunset glow keeps vanilla's shape, fade and brightness
 * (the luminance of vanilla's orange at each point) but takes the pack's hue — white for S1mp1e Mono. A sky box has
 * no glow, so then there is none: the fog toward the sun keeps the pack's colour as well.
 */
@Mixin(Dimension.class)
public abstract class PackSunsetMixin {

    @Inject(method = "getSunriseColor", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$packSunset(float timeOfDay, float tickDelta, CallbackInfoReturnable<float[]> cir) {
        float[] c = cir.getReturnValue();
        if (c == null || !PackSky.active) return;
        if (PackSky.skybox) {
            cir.setReturnValue(null);
            return;
        }
        if (!PackSky.hasSunset) return;
        float l = 0.299F * c[0] + 0.587F * c[1] + 0.114F * c[2];
        c[0] = l * PackSky.sunsetR;
        c[1] = l * PackSky.sunsetG;
        c[2] = l * PackSky.sunsetB;
    }
}
