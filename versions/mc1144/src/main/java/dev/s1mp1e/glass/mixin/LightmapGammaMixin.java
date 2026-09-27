package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s1mp1e.client.module.FullbrightModule;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fullbright (1.14.4): inside {@code LightmapTextureManager.update(F)V} the brightness curve
 * reads the gamma option as the plain field {@code this.client.options.gamma} — exactly ONE
 * {@code GETFIELD Lnet/minecraft/client/options/GameOptions;gamma:D} in the method
 * (javap-verified, yarn 1.14.4+build.18). There is no {@code SimpleOption} on 1.14.4, so
 * instead of the 1.21.1 reference's {@code @Redirect} of {@code SimpleOption.getValue()} we
 * modify that field read with MixinExtras {@code @ModifyExpressionValue} (chains with other
 * mods touching the same expression) and hand {@code update} an over-bright gamma while the
 * module is on. {@code update} clamps the final colour, so a large value saturates to full
 * brightness with no artefacts.
 *
 * <p>The option field itself is NEVER written: {@code GameOptions.write()} would otherwise
 * persist the boosted value into {@code options.txt}. Render-side only — nothing is revealed
 * through blocks.
 */
@Mixin(LightmapTextureManager.class)
public class LightmapGammaMixin {

    @Unique private static boolean s1mp1e$failed;

    @ModifyExpressionValue(
        method = "update(F)V",
        at = @At(value = "FIELD",
                 target = "Lnet/minecraft/client/options/GameOptions;gamma:D",
                 opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private double s1mp1e$fullbright(double gamma) {
        if (s1mp1e$failed) return gamma;
        try {
            if (FullbrightModule.active()) return Math.max(gamma, FullbrightModule.level());
        } catch (Throwable t) {
            s1mp1e$failed = true;
            System.out.println("[S1mp1e] Fullbright: disabled after error: " + t);
        }
        return gamma;
    }
}
