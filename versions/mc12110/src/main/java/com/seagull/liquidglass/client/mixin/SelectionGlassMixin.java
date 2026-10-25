package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.SelectionGlass;
import dev.s1mp1e.client.gui.SelectionGlideHost;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.layouts.LayoutElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The selected row of every list (worlds, servers, resource packs, report reasons, …) was vanilla's box: the entry rect
 * filled with the outline colour and a black rect 1 px inside it. It becomes a glass highlight ({@link SelectionGlass}).
 * A row whose highlight {@code SelectionListGlide} already drew this frame, gliding, under every row is skipped here.
 *
 * <p>1.21.9+ (javap): {@code renderSelection(g, entry, outline)} from {@code renderItem}, the outline white while the
 * list has focus, grey otherwise. {@code AbstractSelectionList.Entry} is a protected nested class, so the entry arrives
 * as its public {@link LayoutElement} face. (1.21.8: {@code renderSelection(g, top, width, height, outline, inner)};
 * 26.x: {@code extractSelection}.)
 */
@Mixin(AbstractSelectionList.class)
public abstract class SelectionGlassMixin {

   @Inject(method = "renderSelection", at = @At("HEAD"), cancellable = true)
   private void lg$glassSelection(GuiGraphics g, @Coerce LayoutElement entry, int outline, CallbackInfo ci) {
      if ((Object) this instanceof SelectionGlideHost host && host.liquidglass$glidesOver(entry)) {
         ci.cancel();   // drawn already, at the gliding position
         return;
      }
      if (!SelectionGlass.usable()) return;
      ci.cancel();
      float x0 = entry.getX(), y0 = entry.getY();
      SelectionGlass.draw(g, x0, y0, x0 + entry.getWidth(), y0 + entry.getHeight(), (outline & 0xFFFFFF) == 0xFFFFFF);
   }
}
