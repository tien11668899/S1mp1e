package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.module.OldAnimationsModule;
import net.minecraft.client.render.entity.layer.AbstractArmorLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.7 red armour: the armour layer opts back into the damage tint, so worn armour flashes red with the body, as it did
 * when 1.7 re-drew the armour passes in the hurt colour. NoHurtCam's "Red flash" off still wins: it stops the tint for
 * every layer.
 */
@Mixin(AbstractArmorLayer.class)
public abstract class OldAnimArmourMixin {

    @Inject(method = "colorsWhenDamaged", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$redArmour(CallbackInfoReturnable<Boolean> cir) {
        if (OldAnimationsModule.redArmour()) cir.setReturnValue(true);
    }
}
