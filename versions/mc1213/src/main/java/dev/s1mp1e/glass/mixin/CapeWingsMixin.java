package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.DragonWingsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the DragonWings module shows wings on the local player, its cape is not drawn (the wings take its place).
 * 1.21.2+: the cape feature gets a render state; its entity id picks out the local player.
 */
@Mixin(CapeFeatureRenderer.class)
public abstract class CapeWingsMixin {
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/client/render/entity/state/PlayerEntityRenderState;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$wingsReplaceCape(MatrixStack matrices, VertexConsumerProvider consumers, int light,
                                         PlayerEntityRenderState state, float limbAngle, float limbDistance,
                                         CallbackInfo ci) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null && state.id == player.getId() && DragonWingsModule.showsOn(player)) ci.cancel();
    }
}
