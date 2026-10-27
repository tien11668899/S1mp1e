package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.module.HitboxModule;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** F3+B boxes drawn the {@link HitboxModule} way while it is on. */
@Mixin(EntityRenderDispatcher.class)
public abstract class HitboxMixin {

    @Inject(method = "renderHitbox", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$hitbox(Entity entity, double dx, double dy, double dz, float yaw, float tickDelta, CallbackInfo ci) {
        if (HitboxModule.render(entity, dx, dy, dz, tickDelta)) ci.cancel();
    }
}
