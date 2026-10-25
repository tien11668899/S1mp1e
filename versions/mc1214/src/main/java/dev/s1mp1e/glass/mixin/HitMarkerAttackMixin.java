package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HitMarkerModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HitMarkerModule}: the LOCAL player's swing. {@code ClientPlayerInteractionManager.attackEntity} sends the attack
 * packet and runs {@code PlayerEntity.attack} client-side; the integrated server's own player is filtered out by the
 * {@code client.player} identity check. Read-only observation - nothing about the attack is changed.
 */
@Mixin(PlayerEntity.class)
public abstract class HitMarkerAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void s1mp1e$onAttack(Entity target, CallbackInfo ci) {
        if ((Object) this == MinecraftClient.getInstance().player) HitMarkerModule.onAttack(target);
    }
}
