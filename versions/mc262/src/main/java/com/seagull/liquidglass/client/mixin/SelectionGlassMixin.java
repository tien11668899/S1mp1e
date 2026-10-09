package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.layouts.LayoutElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The selected row of every list (worlds, servers, resource packs, report reasons, …) was vanilla's box: the entry rect
 * filled with the outline colour and a black rect 1 px inside it. It becomes a glass highlight on the button material
 * (the same glass as buttons and tabs) with the hotbar corner — brighter while the list has focus. {@code SelectionListGlide}
 * still decides where the highlight is (it calls this method at the gliding position), so the glide is kept.
 */
@Mixin(AbstractSelectionList.class)
public abstract class SelectionGlassMixin {

   @Inject(method = "extractSelection", at = @At("HEAD"), cancellable = true)
   private void lg$glassSelection(GuiGraphicsExtractor g, @Coerce LayoutElement entry, int outline, CallbackInfo ci) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) return;
      ci.cancel();
      float x0 = entry.getX(), y0 = entry.getY(), x1 = x0 + entry.getWidth(), y1 = y0 + entry.getHeight();
      if (x1 <= x0 || y1 <= y0) return;
      float half = Math.min(x1 - x0, y1 - y0) / 2.0F;
      float corner = Math.min(1.0F, GlassCorners.HOTBAR_RADIUS / Math.max(1.0F, half));
      boolean focused = (outline & 0xFFFFFF) == 0xFFFFFF;
      GlassWidgets.capsule(g, x0, y0, x1, y1, corner, focused ? 0.81F : 0.55F, 1.0F, true);
   }
}
