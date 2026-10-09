package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Two container widgets still drew vanilla's opaque sprites on top of the glass panel:
 * <ul>
 *   <li>the anvil's rename field ({@code container/anvil/text_field[_disabled]}) — a dark slab; now the same frosted
 *       field as every text box;</li>
 *   <li>the enchanting table's three option rows ({@code container/enchanting_table/enchantment_slot[_disabled|
 *       _highlighted]}) — brown/grey bars; now glass rows on the button material (hovered row lifts like a hovered
 *       button, an unaffordable one is a faint frosted row).</li>
 * </ul>
 * The level orbs, rune text and costs are other sprites/text and draw unchanged on top.
 */
@Mixin({AnvilScreen.class, EnchantmentScreen.class})
public abstract class ContainerFieldsGlassMixin {

   @WrapOperation(method = "extractBackground", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
   private void lg$glassFields(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
                               Operation<Void> original) {
      String p = sprite == null ? "" : sprite.getPath();
      if (p.startsWith("container/anvil/text_field")) {
         GlassSurface.scrim(g, x, y, x + w, y + h, 4.0F, p.endsWith("_disabled") ? 0x14FFFFFF : 0x2EFFFFFF);
         return;
      }
      if (p.startsWith("container/enchanting_table/enchantment_slot") && GlassPipeline.ensureReady() && GlassPipeline.btnUsable()) {
         float corner = Math.min(1.0F, GlassCorners.HOTBAR_RADIUS / Math.max(1.0F, Math.min(w, h) / 2.0F));
         if (p.endsWith("_disabled")) {
            GlassSurface.scrim(g, x, y, x + w, y + h, Math.min(GlassCorners.HOTBAR_RADIUS, h / 2.0F), 0x14FFFFFF);
         } else {
            GlassWidgets.capsule(g, x, y, x + w, y + h, corner, p.endsWith("_highlighted") ? 0.81F : 0.0F, 1.0F, true);
         }
         return;
      }
      original.call(g, pipeline, sprite, x, y, w, h);
   }
}
