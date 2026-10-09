package com.seagull.liquidglass.client.compat.modmenu;

import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mod Menu draws its own list selection (a grey frame around a black fill) instead of the vanilla one our
 * {@code SelectionGlassMixin} turns to glass — so its selected row stood out as a solid black bar. Draw the same glass_btn
 * capsule (hotbar corner, focus lift) over the same box: {@code (x, y-2) .. (x+width, y+height+2)}.
 */
@Pseudo
@Mixin(targets = "com.terraformersmc.modmenu.gui.widget.ModListWidget")
public abstract class ModMenuSelectionGlassMixin {

   @Inject(method = "drawSelectionHighlight(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIII)V", at = @At("HEAD"), cancellable = true)
   private void lg$glassSelection(GuiGraphicsExtractor g, int x, int y, int width, int height, int borderColor, int fillColor,
                                  CallbackInfo ci) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) return;
      ci.cancel();
      float x0 = x, y0 = y - 2, x1 = x + width, y1 = y + height + 2;
      if (x1 <= x0 || y1 <= y0) return;
      float corner = Math.min(1.0F, GlassCorners.HOTBAR_RADIUS / Math.max(1.0F, Math.min(x1 - x0, y1 - y0) / 2.0F));
      boolean focused = (borderColor & 0xFFFFFF) == 0xFFFFFF;
      GlassWidgets.capsule(g, x0, y0, x1, y1, corner, focused ? 0.81F : 0.55F, 1.0F, true);
   }
}
