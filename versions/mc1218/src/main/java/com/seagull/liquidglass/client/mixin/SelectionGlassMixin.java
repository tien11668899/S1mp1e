package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The selected row of every list (worlds, servers, resource packs, report reasons, …) was vanilla's box: the entry rect
 * filled with the outline colour and a black rect 1 px inside it. It becomes a glass highlight on the button material
 * (the same glass as buttons and tabs) with the hotbar corner — brighter while the list has focus.
 *
 * <p>1.21.8 (javap): {@code renderSelection(g, top, rowWidth, rowHeight, outline, inner)} centres the row in the list
 * ({@code x = getX() + (width ∓ rowWidth) / 2}) and fills {@code top - 2 .. top + rowHeight + 2}; the outline is white
 * while the list has focus, grey otherwise. 26.x passes the entry instead.
 */
@Mixin(AbstractSelectionList.class)
public abstract class SelectionGlassMixin {

   @Inject(method = "renderSelection", at = @At("HEAD"), cancellable = true)
   private void lg$glassSelection(GuiGraphics g, int top, int rowWidth, int rowHeight, int outline, int inner, CallbackInfo ci) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) return;
      ci.cancel();
      AbstractWidget self = (AbstractWidget) (Object) this;
      float x0 = self.getX() + (self.getWidth() - rowWidth) / 2, x1 = self.getX() + (self.getWidth() + rowWidth) / 2;
      float y0 = top - 2, y1 = top + rowHeight + 2;
      if (x1 <= x0 || y1 <= y0) return;
      float half = Math.min(x1 - x0, y1 - y0) / 2.0F;
      float corner = Math.min(1.0F, GlassCorners.HOTBAR_RADIUS / Math.max(1.0F, half));
      boolean focused = (outline & 0xFFFFFF) == 0xFFFFFF;
      GlassWidgets.capsule(g, x0, y0, x1, y1, corner, focused ? 0.81F : 0.55F, 1.0F, true);
   }
}
