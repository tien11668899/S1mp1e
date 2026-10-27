package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.PackSky;
import net.minecraft.client.world.color.FoliageColors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mono pack ({@link PackSky#mono}) turns the leaf tints the game hard-codes grey: spruce and birch never read the
 * foliage colour map, and the default tint is used where no biome is known (items). Same luminance, no hue.
 */
@Mixin(FoliageColors.class)
public abstract class PackFoliageMixin {

    @Inject(method = "getSpruceColor", at = @At("RETURN"), cancellable = true)
    private static void s1mp1e$spruce(CallbackInfoReturnable<Integer> cir) {
        if (PackSky.active && PackSky.mono) cir.setReturnValue(PackSky.grey(cir.getReturnValue()));
    }

    @Inject(method = "getBirchColor", at = @At("RETURN"), cancellable = true)
    private static void s1mp1e$birch(CallbackInfoReturnable<Integer> cir) {
        if (PackSky.active && PackSky.mono) cir.setReturnValue(PackSky.grey(cir.getReturnValue()));
    }

    @Inject(method = "getDefaultColor", at = @At("RETURN"), cancellable = true)
    private static void s1mp1e$default(CallbackInfoReturnable<Integer> cir) {
        if (PackSky.active && PackSky.mono) cir.setReturnValue(PackSky.grey(cir.getReturnValue()));
    }
}
