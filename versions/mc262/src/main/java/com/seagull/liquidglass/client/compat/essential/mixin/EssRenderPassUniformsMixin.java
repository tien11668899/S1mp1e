package com.seagull.liquidglass.client.compat.essential.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Essential 1.5 (UniversalCraft 534) × MaLiLib crash fix: "Missing uniform Globals (should be UNIFORM_BUFFER)".
 *
 * <p>UniversalCraft's own render pass ({@code URenderPassImpl}) binds only {@code Projection} and
 * {@code DynamicTransforms}, yet the vanilla text pipelines it draws Essential's text with declare {@code Globals} and
 * {@code Fog} too (verified: every vanilla TEXT pipeline does, with or without MaLiLib). Normally the driver strips the
 * unused blocks and nothing checks them; with MaLiLib installed the check fails and every Essential screen crashes —
 * even without S1mp1e. Fix: bind the same default uniforms vanilla binds for its own passes
 * ({@code RenderSystem.bindDefaultUniforms}: Globals, Fog) before each UniversalCraft draw. Binding a uniform the shader
 * does not use is ignored, so this never changes a draw that already worked.
 *
 * <p>{@code @Pseudo}: UniversalCraft 505 (Essential 1.4) has no {@code URenderPassImpl}; the mixin is then skipped. The
 * pass is read reflectively (no {@code @Shadow}) so a shape change can never fail the mixin.
 */
@Pseudo
@Mixin(targets = "gg.essential.universal.render.URenderPassImpl", remap = false)
public abstract class EssRenderPassUniformsMixin {

   private static java.lang.reflect.Field lg$mcField;
   private static boolean lg$broken;

   @Inject(method = "drawCall", at = @At("HEAD"), require = 0, remap = false)
   private void lg$bindDefaultUniforms(CallbackInfo ci) {
      if (lg$broken) return;
      try {
         if (lg$mcField == null) {
            lg$mcField = this.getClass().getDeclaredField("mc");
            lg$mcField.setAccessible(true);
         }
         Object o = lg$mcField.get(this);
         if (!(o instanceof RenderPass pass)) return;
         GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
         if (globals != null) pass.setUniform("Globals", globals);
         GpuBufferSlice fog = RenderSystem.getShaderFog();
         if (fog != null) pass.setUniform("Fog", fog);
      } catch (Throwable t) {
         lg$broken = true;   // never take Essential down over this
      }
   }
}
