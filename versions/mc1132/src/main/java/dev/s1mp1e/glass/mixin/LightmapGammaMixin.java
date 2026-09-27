package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.s1mp1e.client.module.FullbrightModule;
import net.minecraft.class_4226;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fullbright (1.13.2 port). The lightmap manager is UNMAPPED on 1.13.2: {@code net.minecraft.class_4226}
 * (built by the game renderer {@code class_4218}), and its per-frame {@code update(F)V} is
 * {@code method_19171(F)V}. Its brightness curve reads the gamma option as the plain field
 * {@code this.client.options.gamma}, which legacy yarn names {@code GameOptions.field_19985:D}
 * (options.txt key {@code gamma}) — exactly ONE {@code GETFIELD} of it in the method (javap-verified,
 * legacy yarn 1.13.2+build.604-v2). There is no {@code SimpleOption} on 1.13.2, so we modify that field
 * read with MixinExtras {@code @ModifyExpressionValue} (chains with other mods touching the same
 * expression) and hand {@code update} an over-bright gamma while the module is on. {@code update}
 * clamps the final colour, so a large value saturates to full brightness with no artefacts.
 *
 * <p>The option field itself is NEVER written: {@code GameOptions.write()} would otherwise persist the
 * boosted value into {@code options.txt}. Render-side only — nothing is revealed through blocks.
 */
@Mixin(class_4226.class)
public class LightmapGammaMixin {

    @Unique private static boolean s1mp1e$failed;

    @ModifyExpressionValue(
        method = "method_19171(F)V",
        at = @At(value = "FIELD",
                 target = "Lnet/minecraft/client/option/GameOptions;field_19985:D",
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
