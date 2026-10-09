package com.seagull.liquidglass.client.mixin;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3 boot path: before the resource reload finishes, UI draws through the <em>fallback</em> {@code PipelineCache},
 * whose {@code ShaderSource} is the anonymous {@code GameRenderer$1} (it reads shaders from the {@code ResourceManager}).
 * The S1mp1e boot intro draws in exactly that window and compiles the {@code liquidglass:core/s1mp1e_intro} pipeline
 * on demand; without Fabric API our assets are not a resource pack {@code GameRenderer$1} can see, so the lookup returns
 * null, the pipeline fails to compile, and {@code RenderSystem.getCompiledPipeline} throws
 * ("Failed to find or load pipeline liquidglass:pipeline/s1mp1e_intro"). Serving the {@code liquidglass:} shaders from
 * the jar here makes the boot intro compilable. The runtime {@code ShaderManager.Configs} source is handled by
 * {@link ShaderManagerLiquidGlassMixin}; both route through {@code GlassPipeline.liquidGlassShader}.
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer$1")
public class BootShaderSourceMixin {
   @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
   private void lg$provideLiquidGlassShader(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
      String src = com.seagull.liquidglass.client.render.GlassPipeline.liquidGlassShader(id, type);
      if (src != null) {
         cir.setReturnValue(src);
      }
   }
}
