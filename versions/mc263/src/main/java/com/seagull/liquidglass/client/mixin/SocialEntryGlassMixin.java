package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.social.PlayerEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Social Interactions rows were solid grey blocks ({@code BG_FILL}; a darker {@code BG_FILL_REMOVED} for a player who
 * left). Each becomes a rounded frosted row (hotbar corner); a player who left gets a fainter one. The skin shade and
 * every icon/text are untouched.
 */
@Mixin(PlayerEntry.class)
public abstract class SocialEntryGlassMixin {

   @WrapOperation(method = "extractContent", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
   private void lg$glassRow(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
      if (argb == PlayerEntry.BG_FILL || argb == PlayerEntry.BG_FILL_REMOVED) {
         float r = Math.min(GlassCorners.HOTBAR_RADIUS, Math.min(x1 - x0, y1 - y0) / 2.0F);
         GlassSurface.scrim(g, x0, y0, x1, y1, r, argb == PlayerEntry.BG_FILL ? 0x40000000 : 0x24000000);
      } else {
         original.call(g, x0, y0, x1, y1, argb);
      }
   }
}
