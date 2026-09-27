package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HitMarkerModule;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HitMarkerModule}: the server's entity statuses - 1.16.5 has no damage-event packet yet, so a melee hurt arrives as
 * status 2 and a death as status 3 through {@code LivingEntity.handleStatus(byte)}. Client side only; read-only.
 */
@Mixin(LivingEntity.class)
public abstract class HitMarkerDamageMixin {

    @Inject(method = "handleStatus", at = @At("HEAD"))
    private void s1mp1e$onStatus(byte status, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.world.isClient) return;
        if (status == 2) HitMarkerModule.onDamage(self);
        else if (status == 3) HitMarkerModule.onDeath(self.getEntityId());
    }
}
