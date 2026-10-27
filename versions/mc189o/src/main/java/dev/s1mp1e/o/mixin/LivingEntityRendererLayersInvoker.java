package dev.s1mp1e.o.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.layer.EntityRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code LivingEntityRenderer.addLayer} is protected; DragonWings adds its layer to the player renderers through this. */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererLayersInvoker {
    @Invoker("addLayer")
    boolean s1mp1e$addLayer(EntityRenderLayer<?> layer);
}
