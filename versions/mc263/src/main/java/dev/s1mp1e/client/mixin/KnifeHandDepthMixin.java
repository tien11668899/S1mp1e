package dev.s1mp1e.client.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.s1mp1e.client.knife.KnifeRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.3: remembers the depth target GameRenderer cleared for the hand pass, so the GPU-skinned knife shares it. */
@Mixin(GameRenderer.class)
public class KnifeHandDepthMixin {

    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void s1mp1e$knifeDepthIn(CameraRenderState camera, PlayerRenderState player, GpuTextureView depth, CallbackInfo ci) {
        KnifeRenderer.handDepth = depth;
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void s1mp1e$knifeDepthOut(CameraRenderState camera, PlayerRenderState player, GpuTextureView depth, CallbackInfo ci) {
        KnifeRenderer.handDepth = null;
    }
}
