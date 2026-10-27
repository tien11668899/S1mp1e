package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.cull.EntityCull;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity culling and render distance ({@link EntityCull}) where each world entity is drawn:
 * {@code render(Entity, float, boolean)}, which vanilla's entity loop and Argentum's Celeritas loop both go through.
 * A culled entity still gets its name tag; the spectator outline pass ({@code solidRender}), which exists to show
 * entities through walls, is never culled. Also times every entity draw for the DevShot comparison.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityCullDispatchMixin {

    @Shadow private boolean solidRender;

    @Inject(method = "render(Lnet/minecraft/entity/Entity;FZ)Z", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$cull(Entity entity, float tickDelta, boolean skipHitbox, CallbackInfoReturnable<Boolean> cir) {
        if (solidRender) return;
        int v = EntityCull.verdict(entity, tickDelta);
        if (v == EntityCull.RENDER) {
            EntityCull.drawStart();
            return;
        }
        if (v == EntityCull.LABEL_ONLY) ((EntityRenderDispatcher) (Object) this).renderNameTag(entity, tickDelta);
        cir.setReturnValue(false);
    }

    @Inject(method = "render(Lnet/minecraft/entity/Entity;FZ)Z", at = @At("RETURN"))
    private void s1mp1e$drawn(Entity entity, float tickDelta, boolean skipHitbox, CallbackInfoReturnable<Boolean> cir) {
        EntityCull.drawEnd();
    }
}
