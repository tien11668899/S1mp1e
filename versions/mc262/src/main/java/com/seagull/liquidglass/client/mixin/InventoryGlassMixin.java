package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPanels;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({InventoryScreen.class})
public abstract class InventoryGlassMixin {
   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"
      )
   )
   private void lg$glassPanel(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
      if (!GlassPanels.panel(g, (AbstractContainerScreen<?>)(Object)this, x, y, w, h)) {
         g.blit(pipeline, tex, x, y, u, v, w, h, tw, th);
      }
   }
}
