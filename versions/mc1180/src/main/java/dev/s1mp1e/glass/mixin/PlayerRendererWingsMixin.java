package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.wings.DragonWingsFeature;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds {@link DragonWingsFeature} to every player renderer (both skin models) after vanilla has added its own features.
 * The feature draws only for the local player and only while the DragonWings module is on.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerRendererWingsMixin {
    @SuppressWarnings("unchecked")
    @Inject(method = "<init>", at = @At("TAIL"))
    private void s1mp1e$addWings(EntityRendererFactory.Context context, boolean slim, CallbackInfo ci) {
        FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> parent =
                (FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>>) (Object) this;
        ((LivingEntityRendererFeatureInvoker) (Object) this).s1mp1e$addFeature(new DragonWingsFeature(parent));
    }
}
