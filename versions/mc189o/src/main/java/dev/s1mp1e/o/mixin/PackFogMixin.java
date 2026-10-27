package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.PackSky;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.dimension.Dimension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The pack's fog colour ({@link PackSky}) as the base of the overworld's fog, with vanilla's day/night falloff; under
 * a sky box, which does not darken at night, the fog keeps its colour too, so far terrain still fades into the
 * horizon. The Nether and the End override {@code getFogColor} and keep their own.
 */
@Mixin(Dimension.class)
public abstract class PackFogMixin {

    @Inject(method = "getFogColor", at = @At("RETURN"), cancellable = true)
    private void s1mp1e$packFog(float timeOfDay, float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
        if (!PackSky.active || !PackSky.hasFog) return;
        float f = PackSky.skybox ? 1.0F
                : MathHelper.clamp(MathHelper.cos(timeOfDay * (float) Math.PI * 2.0F) * 2.0F + 0.5F, 0.0F, 1.0F);
        cir.setReturnValue(new Vec3d(PackSky.fogR * (f * 0.94F + 0.06F), PackSky.fogG * (f * 0.94F + 0.06F),
                PackSky.fogB * (f * 0.91F + 0.09F)));
    }
}
