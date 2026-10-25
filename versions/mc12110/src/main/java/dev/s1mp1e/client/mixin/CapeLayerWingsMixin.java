package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.s1mp1e.client.module.DragonWingsModule;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the DragonWings module shows wings on the local player, its cape is not drawn (the wings take its place). */
@Mixin(CapeLayer.class)
public abstract class CapeLayerWingsMixin {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$wingsReplaceCape(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                         AvatarRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (DragonWingsModule.showsOn(state)) ci.cancel();
    }
}
