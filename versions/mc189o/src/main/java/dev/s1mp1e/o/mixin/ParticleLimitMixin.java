package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.module.ParticlesModule;
import net.minecraft.client.ParticleManager;
import net.minecraft.client.entity.particle.Particle;
import net.minecraft.entity.Entity;
import net.minecraft.entity.particle.ParticleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every particle — vanilla or from another mod, block cracks included — is handed to {@code ParticleManager.add};
 * crit / enchant bursts start as emitters through {@code addEmitter}. {@link ParticlesModule} decides there whether
 * each is kept. A dropped particle is simply never added, so it costs nothing afterwards.
 */
@Mixin(value = ParticleManager.class, priority = 1100)
public abstract class ParticleLimitMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$limit(Particle particle, CallbackInfo ci) {
        if (ParticlesModule.drop(false)) ci.cancel();
    }

    @Inject(method = "addEmitter", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$limitEmitter(Entity entity, ParticleType type, CallbackInfo ci) {
        if (ParticlesModule.drop(true)) ci.cancel();
    }
}
