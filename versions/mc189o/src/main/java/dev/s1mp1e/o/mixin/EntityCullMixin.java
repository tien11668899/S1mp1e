package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.cull.EntityCull;
import net.minecraft.client.render.Culler;
import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Start of the world's entity pass: {@link EntityCull} takes the camera position for the frame. The per-entity
 * decision is made where every entity is drawn ({@code EntityCullDispatchMixin}), because the entity loop itself is not
 * always vanilla's: Argentum's Celeritas renderer takes {@code renderEntities} over with its own loop.
 */
@Mixin(WorldRenderer.class)
public abstract class EntityCullMixin {

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void s1mp1e$cullFrame(Entity camera, Culler culler, float tickDelta, CallbackInfo ci) {
        EntityCull.beginFrame(camera, tickDelta);
    }
}
