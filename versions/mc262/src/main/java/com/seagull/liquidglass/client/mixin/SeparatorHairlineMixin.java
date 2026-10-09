package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla closes headers and footers (list pages, tab bars, create world, stats, LAN options, …) with a 2 px embossed
 * separator texture — a hard light/dark double rule that cuts across the glass. Every blit of those four textures becomes
 * the Apple hairline instead: one half-pixel line of faint white, faded out toward both ends. Caught at the single blit
 * call they all go through, so modded screens using the same textures follow too.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class SeparatorHairlineMixin {

   @Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V",
           at = @At("HEAD"), cancellable = true)
   private void lg$hairline(RenderPipeline pipeline, Identifier tex, int x, int y, float u, float v, int w, int h,
                            int texW, int texH, CallbackInfo ci) {
      if (tex == null || !(tex.equals(Screen.HEADER_SEPARATOR) || tex.equals(Screen.FOOTER_SEPARATOR)
            || tex.equals(Screen.INWORLD_HEADER_SEPARATOR) || tex.equals(Screen.INWORLD_FOOTER_SEPARATOR))) {
         return;
      }
      ci.cancel();
      if (w <= 0) return;
      GuiGraphicsExtractor g = (GuiGraphicsExtractor) (Object) this;
      float cy = y + h / 2.0F;
      float fade = Math.min(48.0F, w / 4.0F);
      int core = 0x26FFFFFF;
      // faded ends + solid middle (the round pipeline has no gradient; three segments read as a soft taper)
      GlassWidgets.fillRound(g, x, cy - 0.25F, x + fade, cy + 0.25F, 0x0FFFFFFF, 0.0F);
      GlassWidgets.fillRound(g, x + fade, cy - 0.25F, x + w - fade, cy + 0.25F, core, 0.0F);
      GlassWidgets.fillRound(g, x + w - fade, cy - 0.25F, x + w, cy + 0.25F, 0x0FFFFFFF, 0.0F);
   }
}
