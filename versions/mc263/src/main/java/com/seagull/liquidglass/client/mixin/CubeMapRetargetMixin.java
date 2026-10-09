package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seagull.liquidglass.client.render.MenuBackdrop;
import net.minecraft.client.renderer.CubeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3: lets {@link MenuBackdrop} render the menu panorama a second time straight INTO the glass backdrop texture, so
 * the panels refract the panorama without ever copying out of the main target mid-frame (that copy faults the NVIDIA
 * driver on 26.3, intermittently, whatever copy path is used). While {@link MenuBackdrop#retargetView()} is non-null,
 * the pass {@code CubeMap.render} opens draws into that view instead of the main target's colour; depth stays the main
 * target's (same size).
 */
@Mixin(CubeMap.class)
public class CubeMapRetargetMixin {
   @WrapOperation(method = "render(FF)V", at = @At(value = "INVOKE",
         target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;getColorTextureView()Lcom/mojang/renderpearl/api/textures/GpuTextureView;"))
   private GpuTextureView lg$retargetColour(RenderTarget target, Operation<GpuTextureView> original) {
      GpuTextureView v = MenuBackdrop.retargetView();
      return v != null ? v : original.call(target);
   }
}
