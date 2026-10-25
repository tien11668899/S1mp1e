package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HitMarkerModule;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HitMarkerModule}: the server's confirmation that an entity took damage - 1.21.1's client handles the
 * {@code EntityDamageS2CPacket} through {@code LivingEntity.onDamaged(DamageSource)}. Client side only; read-only.
 */
@Mixin(LivingEntity.class)
public abstract class HitMarkerDamageMixin {

    @Inject(method = "onDamaged", at = @At("HEAD"))
    private void s1mp1e$onDamage(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.getWorld().isClient()) HitMarkerModule.onDamage(self);
    }

    /** Entity status 3 = the server says this entity died (the kill colour). */
    @Inject(method = "handleStatus", at = @At("HEAD"))
    private void s1mp1e$onStatus(byte status, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (status == 3 && self.getWorld().isClient()) HitMarkerModule.onDeath(self.getId());
    }
}
