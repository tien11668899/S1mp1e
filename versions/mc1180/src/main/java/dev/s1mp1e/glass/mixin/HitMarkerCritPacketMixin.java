package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HitMarkerModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HitMarkerModule}: the server's CRIT animation - the client's only crit signal (client-side
 * {@code LivingEntity.damage} returns false, so {@code PlayerEntity.attack} never reaches {@code addCritParticles}).
 * The handler first runs on the network thread and re-queues itself onto the main thread, so only the main-thread pass
 * is observed. Read-only.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class HitMarkerCritPacketMixin {

    @Inject(method = "onEntityAnimation", at = @At("HEAD"))
    private void s1mp1e$onAnimate(EntityAnimationS2CPacket packet, CallbackInfo ci) {
        if (packet.getAnimationId() == EntityAnimationS2CPacket.CRIT && MinecraftClient.getInstance().isOnThread()) {
            HitMarkerModule.onCritAnimate(packet.getId());   // 1.18.2: the entity id getter is getId()
        }
    }
}
