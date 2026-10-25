package dev.s1mp1e.client.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reaches the protected {@code LivingEntityRenderer.addLayer} so a mixin can attach an extra render layer. */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererLayersInvoker {
    @SuppressWarnings("rawtypes")
    @Invoker("addLayer")
    boolean s1mp1e$addLayer(RenderLayer layer);
}
