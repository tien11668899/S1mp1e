package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.wings.DragonWingsLayer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds {@link DragonWingsLayer} to both player renderers (wide and thin arms) after vanilla has added its own layers.
 * The layer draws only for the local player and only while the DragonWings module is on.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererWingsMixin {
    @Inject(method = "<init>(Lnet/minecraft/client/render/entity/EntityRenderDispatcher;Z)V", at = @At("TAIL"))
    private void s1mp1e$addWings(EntityRenderDispatcher dispatcher, boolean thinArms, CallbackInfo ci) {
        ((LivingEntityRendererLayersInvoker) (Object) this).s1mp1e$addLayer(new DragonWingsLayer((PlayerRenderer) (Object) this));
    }
}
