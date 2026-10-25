package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.TabInfo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({RecipeBookTabButton.class})
public abstract class RecipeTabGlassMixin {
   @Shadow
   @Final
   private TabInfo tabInfo;

   @Inject(
      method = {"renderIcon"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void lg$centeredIcon(GuiGraphics g, CallbackInfo ci) {
      if (GlassPipeline.usable()) {
         ci.cancel();
         RecipeBookTabButton self = (RecipeBookTabButton)(Object)this;
         int x = self.getX();
         int y = self.getY();
         if (this.tabInfo.secondaryIcon().isPresent()) {
            g.renderFakeItem(this.tabInfo.primaryIcon(), x + 1, y + 5);
            g.renderFakeItem((ItemStack)this.tabInfo.secondaryIcon().get(), x + 12, y + 5);
         } else {
            g.renderFakeItem(this.tabInfo.primaryIcon(), x + 6, y + 5);
         }
      }
   }

   @Redirect(
      method = {"renderWidget"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/ResourceLocation;IIII)V"
      )
   )
   private void lg$glassTab(GuiGraphics g, RenderPipeline pipeline, ResourceLocation sprite, int x, int y, int w, int h) {
      if (!GlassPipeline.ensureReady() || !GlassPipeline.usable()) {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }
}
