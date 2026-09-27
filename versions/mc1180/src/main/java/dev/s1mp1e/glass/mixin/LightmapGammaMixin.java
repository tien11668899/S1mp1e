package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s1mp1e.client.module.FullbrightModule;
import net.minecraft.client.render.LightmapTextureManager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fullbright: inside {@code LightmapTextureManager.update(F)V} the brightness curve reads the gamma
 * option and we return an over-bright value while the module is on. {@code update} clamps the final
 * lightmap colour, so a large value saturates to full brightness with no artefacts.
 *
 * <p>1.18.2 rewrite: the 1.19+ lines wrap {@code SimpleOption.getValue()} after
 * {@code GameOptions.getGamma()}, but {@code SimpleOption} does not exist on 1.18.2 — here
 * {@code GameOptions.gamma} is a plain {@code public double} FIELD. javap -c of
 * {@code LightmapTextureManager} (yarn 1.18.2+build.4) shows exactly ONE
 * {@code getfield GameOptions.gamma:D} in the whole class (bc 489, inside {@code update(F)V},
 * immediately followed by {@code d2f}), so the read is targeted directly with no {@code @Slice}.
 *
 * <p>MixinExtras {@code @ModifyExpressionValue} (0.3.5, bundled with Fabric Loader 0.15.11) instead of
 * a {@code @Redirect}, so another mod modifying the same read still composes with ours. The vanilla
 * read always happens; a failure in our part returns the vanilla value untouched. Only the value the
 * lightmap sees is changed — the option itself (and options.txt) is never written.
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
            if (FullbrightModule.active()) return Math.max(gamma, FullbrightModule.level());
        } catch (Throwable ignored) {
            // fall back to the vanilla gamma
        }
        return gamma;
    }
}
