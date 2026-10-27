package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.PackSky;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The pack's sky colour ({@link PackSky}) in place of the biome's temperature-based one; {@code World.getSkyColor}
 * still scales it for the time of day, rain, thunder and lightning.
 */
@Mixin(Biome.class)
public abstract class PackSkyMixin {

    @Inject(method = "getSkyColor", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$packSky(float temperature, CallbackInfoReturnable<Integer> cir) {
        if (PackSky.active && PackSky.hasSky) cir.setReturnValue(PackSky.sky);
    }
}
