package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.HitMarkerModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link HitMarkerModule}: the server's CRITICAL_HIT animation. In 26.2 this is the client's only crit signal - the local
 * {@code Player.attack} simulation never reaches {@code crit()} because {@code Entity.hurtClient} always returns false
 * (javap-verified). Packet handlers run first on the network thread (and bounce to the main thread), so only the main
 * thread pass is observed. Read-only.
 */
@Mixin(ClientPacketListener.class)
public abstract class HitMarkerCritPacketMixin {

    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void s1mp1e$onAnimate(ClientboundAnimatePacket packet, CallbackInfo ci) {
        if (packet.getAction() == ClientboundAnimatePacket.CRITICAL_HIT && Minecraft.getInstance().isSameThread()) {
            HitMarkerModule.onCritAnimate(packet.getId());
        }
    }
}
