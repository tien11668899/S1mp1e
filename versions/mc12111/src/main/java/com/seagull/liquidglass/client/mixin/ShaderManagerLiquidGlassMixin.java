package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.shaders.ShaderType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves the liquid-glass core shaders straight from the mod jar whenever the vanilla {@link ShaderManager} is asked for
 * a {@code liquidglass:} shader.
 *
 * <p>At draw time {@code GuiRenderer} recompiles a render pipeline through the {@code ShaderManager} (its
 * {@link ShaderManager#getShader} is the render pipeline's shader source), which only knows shaders that the resource
 * reload has already parsed from the packs. Every glass surface is therefore fine in menus / in game — but the S1mp1e
 * boot intro draws <em>during the very first resource reload</em>, before {@code assets/liquidglass/shaders} has been
 * loaded, so the lookup used to fail ("Couldn't find source for shader liquidglass:core/…") and the intro came out
 * black. Reading the {@code .vsh}/{@code .fsh} from the jar here makes every {@code liquidglass:} pipeline compilable at
 * any point in the run, boot included; the jar is the only source for that namespace, so this never shadows a pack.
 */
@Mixin(ShaderManager.class)
public class ShaderManagerLiquidGlassMixin {
   @Inject(method = "getShader", at = @At("HEAD"), cancellable = true)
   private void lg$provideLiquidGlassShader(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
      if (!"liquidglass".equals(id.getNamespace())) {
         return;
      }
      String ext = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
      String path = "/assets/liquidglass/shaders/" + id.getPath() + ext;
      try (InputStream in = ShaderManagerLiquidGlassMixin.class.getResourceAsStream(path)) {
         if (in != null) {
            cir.setReturnValue(new String(in.readAllBytes(), StandardCharsets.UTF_8));
         }
      } catch (Throwable ignored) {
      }
   }
}
