package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.HitMarkerModule;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HitMarkerModule}: the server's confirmation that an entity took damage (the client handles the damage event
 * packet here). Client side only; read-only.
 */
@Mixin(LivingEntity.class)
public abstract class HitMarkerDamageMixin {

    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void s1mp1e$onDamage(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) HitMarkerModule.onDamage(self);
    }

    /** Entity event 3 = the server says this entity died (the kill colour). */
    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void s1mp1e$onEvent(byte id, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (id == 3 && self.level().isClientSide()) HitMarkerModule.onDeath(self.getId());
    }
}
