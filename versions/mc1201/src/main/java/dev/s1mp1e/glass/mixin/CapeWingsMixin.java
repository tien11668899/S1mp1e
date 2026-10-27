package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.DragonWingsModule;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the DragonWings module shows wings on the local player, its cape is not drawn (the wings take its place). */
@Mixin(CapeFeatureRenderer.class)
public abstract class CapeWingsMixin {
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/client/network/AbstractClientPlayerEntity;FFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$wingsReplaceCape(MatrixStack matrices, VertexConsumerProvider consumers, int light,
                                         AbstractClientPlayerEntity player, float limbAngle, float limbDistance,
                                         float tickDelta, float animationProgress, float headYaw, float headPitch,
                                         CallbackInfo ci) {
        if (DragonWingsModule.showsOn(player)) ci.cancel();
    }
}
