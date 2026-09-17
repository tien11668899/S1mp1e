package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPanels;
import com.seagull.liquidglass.client.render.GlassPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.CartographyTableScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({ContainerScreen.class, CraftingScreen.class, DispenserScreen.class, HopperScreen.class, ShulkerBoxScreen.class, AbstractFurnaceScreen.class, MerchantScreen.class, EnchantmentScreen.class, BrewingStandScreen.class, ItemCombinerScreen.class, StonecutterScreen.class, GrindstoneScreen.class, LoomScreen.class, CartographyTableScreen.class, BeaconScreen.class})
public abstract class ContainerScreensGlassMixin {
   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"
      )
   )
   private void lg$glassBg(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
      AbstractContainerScreen<?> self = (AbstractContainerScreen<?>)(Object)this;
      AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor)self;
      if (x == acc.liquidglass$leftPos() && y == acc.liquidglass$topPos()) {
         if (GlassPanels.panel(g, self, x, y, acc.liquidglass$imageWidth(), acc.liquidglass$imageHeight())) {
            return;
         }

         g.blit(pipeline, tex, x, y, u, v, w, h, tw, th);
      } else if (!GlassPipeline.usable()) {
         g.blit(pipeline, tex, x, y, u, v, w, h, tw, th);
      }
   }
}
