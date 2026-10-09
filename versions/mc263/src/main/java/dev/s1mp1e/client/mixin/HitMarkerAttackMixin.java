package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.HitMarkerModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HitMarkerModule}: the LOCAL player's swing. {@code MultiPlayerGameMode.attack} sends the attack packet and then
 * runs {@code Player.attack} client-side (javap-verified), whose {@code attackVisualEffects} calls {@code crit(target)}
 * when the client decides the swing is critical. The integrated server's own ServerPlayer is filtered out by the
 * {@code mc.player} identity check. Read-only observation - nothing about the attack is changed.
 */
@Mixin(Player.class)
public abstract class HitMarkerAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void s1mp1e$onAttack(Entity target, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player) HitMarkerModule.onAttack(target);
    }

    @Inject(method = "crit", at = @At("HEAD"))
    private void s1mp1e$onCrit(Entity target, CallbackInfo ci) {
        if ((Object) this == Minecraft.getInstance().player) HitMarkerModule.onCrit(target);
    }
}
