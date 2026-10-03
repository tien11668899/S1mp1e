package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.module.ParticlesModule;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TrackingEmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every particle — vanilla or from another mod, block cracks and tracking emitters included — is handed to
 * {@code ParticleEngine.add}; {@link ParticlesModule} decides there whether it is kept. A dropped particle is simply
 * never added, so it costs nothing afterwards.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleLimitMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$limit(Particle particle, CallbackInfo ci) {
        if (ParticlesModule.drop(particle instanceof TrackingEmitter)) ci.cancel();
    }
}
