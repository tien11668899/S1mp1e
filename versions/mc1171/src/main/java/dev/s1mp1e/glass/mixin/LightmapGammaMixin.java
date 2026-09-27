package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.client.module.FullbrightModule;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.LightmapTextureManager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fullbright, 1.17.1 target: inside {@code LightmapTextureManager.update(F)V} (yarn 1.17.1+build.65) the
 * brightness curve reads the gamma option straight from the public field {@code GameOptions.gamma} (double).
 * {@code SimpleOption} does not exist on 1.17.1 (it arrived in 1.19), so instead of the 1.19.2 reference's
 * wrapped {@code SimpleOption.getValue()} call we wrap that field read. javap-verified: {@code update(F)V}
 * has exactly ONE {@code GETFIELD GameOptions.gamma:D} (bc 489), and it is the only reference to the field
 * in the whole class, so no slice is needed (refmap: class_315.field_1840:D).
 *
 * <p>While the module is on we return an over-bright gamma. The vanilla option itself is clamped to 0..1,
 * so boosting here is the only way to exceed "Bright"; {@code update} clamps the final colour, so a large
 * value saturates to full brightness with no artefacts.
 *
 * <p>{@code @WrapOperation} (MixinExtras 0.3.5, bundled with Fabric Loader 0.15.11) instead of a
 * {@code @Redirect}, so another mod wrapping the same read still composes with ours. The original read
 * always runs; a failure in our part returns the vanilla value untouched.
 */
@Mixin(LightmapTextureManager.class)
public class LightmapGammaMixin {

    @WrapOperation(
        method = "update(F)V",
        at = @At(value = "FIELD",
                 target = "Lnet/minecraft/client/option/GameOptions;gamma:D",
                 opcode = Opcodes.GETFIELD))
    private double s1mp1e$fullbright(GameOptions options, Operation<Double> original) {
        double v = original.call(options);
        try {
            if (FullbrightModule.active()) {
                return Math.max(v, FullbrightModule.level());
            }
        } catch (Throwable ignored) {
            // fall back to the vanilla gamma
        }
        return v;
    }
}
