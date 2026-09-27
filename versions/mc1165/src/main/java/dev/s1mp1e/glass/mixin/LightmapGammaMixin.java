package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s1mp1e.client.module.FullbrightModule;
import net.minecraft.client.render.LightmapTextureManager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fullbright: inside {@code LightmapTextureManager.update(F)V} the brightness curve reads the gamma
 * option directly from the field {@code GameOptions.gamma:D} (1.16.5 has no {@code SimpleOption} — that
 * arrived in 1.18). We {@link ModifyExpressionValue} exactly that {@code GETFIELD gamma:D} and return an
 * over-bright gamma while the module is on (the vanilla slider itself is clamped to 0..1, so boosting
 * here is the only way to exceed "Bright"). {@code update} clamps the final colour, so a large value
 * saturates to full brightness with no artefacts.
 *
 * <p>{@code @ModifyExpressionValue} (not {@code @Redirect}) so this chains cleanly with any other mod
 * that also touches the gamma read; a {@code @Redirect} would hard-conflict. The single {@code GETFIELD}
 * of {@code gamma:D} in {@code update(F)V} is javap-verified on yarn 1.16.5+build.10.
 */
@Mixin(LightmapTextureManager.class)
public class LightmapGammaMixin {

    @ModifyExpressionValue(
        method = "update(F)V",
        at = @At(value = "FIELD",
                 target = "Lnet/minecraft/client/option/GameOptions;gamma:D",
                 opcode = Opcodes.GETFIELD))
    private double s1mp1e$fullbright(double gamma) {
        try {
            if (FullbrightModule.active()) {
                return Math.max(gamma, FullbrightModule.level());
            }
        } catch (Throwable t) {
            // Any failure -> leave the vanilla gamma value untouched.
        }
        return gamma;
    }
}
