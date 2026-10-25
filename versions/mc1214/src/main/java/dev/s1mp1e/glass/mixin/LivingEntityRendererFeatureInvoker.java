package dev.s1mp1e.glass.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes {@code LivingEntityRenderer.addFeature} so {@code PlayerRendererWingsMixin} can attach the wings feature. */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererFeatureInvoker {
    @SuppressWarnings("rawtypes")
    @Invoker("addFeature")
    boolean s1mp1e$addFeature(FeatureRenderer feature);
}
