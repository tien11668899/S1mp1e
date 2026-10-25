package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.AppleScroller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every scrolling page (world / server / language / resource-pack / key-binding / stats / debug-option lists, multi-line
 * text boxes) drew vanilla's grey scroller sprite and its dark track. Replaced by the macOS overlay scroller
 * ({@link AppleScroller}): a thin knob that shows while the list moves and fades away, widening over a glass track under
 * the pointer. The knob uses vanilla's own {@code scrollBarY()/scrollerHeight()}, and the vanilla hit strip is
 * untouched, so clicking and dragging work exactly as before. A list that fits draws nothing (no disabled track).
 *
 * <p>1.21.8: {@code renderScrollbar(g)} gets no mouse position (read from the mouse handler), the bar is the fixed
 * {@code SCROLLBAR_WIDTH} (6) wide, and there is no cursor API yet (26.x also sets the hand / resize cursor).
 */
@Mixin(AbstractScrollArea.class)
public abstract class ScrollAreaAppleScrollerMixin {

   @Shadow private boolean scrolling;

   @Shadow protected abstract int scrollBarX();

   @Shadow protected abstract int scrollBarY();

   @Shadow protected abstract int scrollerHeight();

   @Shadow protected abstract boolean scrollbarVisible();

   @Shadow public abstract double scrollAmount();

   @Inject(method = "renderScrollbar", at = @At("HEAD"), cancellable = true)
   private void lg$appleScroller(GuiGraphics g, CallbackInfo ci) {
      ci.cancel();
      if (!this.scrollbarVisible()) return;
      AbstractWidget self = (AbstractWidget) (Object) this;
      Minecraft mc = Minecraft.getInstance();
      double mouseX = mc.mouseHandler.getScaledXPos(mc.getWindow()), mouseY = mc.mouseHandler.getScaledYPos(mc.getWindow());
      int x0 = this.scrollBarX();
      float right = x0 + 6;   // AbstractScrollArea.SCROLLBAR_WIDTH
      float top = self.getY(), bottom = self.getBottom();
      boolean over = self.visible && mouseX >= x0 && mouseX <= right && mouseY >= top && mouseY < bottom;
      boolean hover = over || AppleScroller.near(mouseX, mouseY, right, top, bottom);
      AppleScroller.draw(g, this, right, top, bottom, this.scrollBarY(), this.scrollerHeight(), this.scrollAmount(),
            hover, this.scrolling, ((AbstractWidgetTextInvoker) self).liquidglass$alpha());
   }
}
