package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.module.DragonWingsModule;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.entity.layer.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the DragonWings module shows wings on the local player, its cape is not drawn (the wings take its place). */
@Mixin(CapeLayer.class)
public abstract class CapeLayerWingsMixin {
    @Inject(method = "render(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;FFFFFFF)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$wingsReplaceCape(ClientPlayerEntity player, float limbAngle, float limbDistance, float tickDelta,
                                         float age, float headYaw, float headPitch, float scale, CallbackInfo ci) {
        if (DragonWingsModule.showsOn(player)) ci.cancel();
    }
}
