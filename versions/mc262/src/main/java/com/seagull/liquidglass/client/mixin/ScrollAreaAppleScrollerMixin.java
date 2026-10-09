package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.s1mp1e.client.gui.AppleScroller;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * the pointer. The knob uses vanilla's own {@code scrollBarY()/scrollerHeight()}, and the vanilla hit strip and cursors are
 * untouched, so clicking and dragging work exactly as before. A list that fits draws nothing (no disabled track).
 */
@Mixin(AbstractScrollArea.class)
public abstract class ScrollAreaAppleScrollerMixin {

   @Shadow private boolean scrolling;

   @Shadow protected abstract int scrollBarX();

   @Shadow public abstract int scrollBarY();

   @Shadow protected abstract int scrollerHeight();

   @Shadow protected abstract boolean scrollable();

   @Shadow public abstract double scrollAmount();

   @Shadow public abstract int scrollbarWidth();

   @Shadow protected abstract boolean isOverScrollbar(double x, double y);

   @Inject(method = "extractScrollbar", at = @At("HEAD"), cancellable = true)
   private void lg$appleScroller(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
      ci.cancel();
      if (!this.scrollable()) return;
      AbstractWidget self = (AbstractWidget) (Object) this;
      float right = this.scrollBarX() + this.scrollbarWidth();
      float top = self.getY(), bottom = self.getBottom();
      boolean over = this.isOverScrollbar(mouseX, mouseY);
      boolean hover = over || AppleScroller.near(mouseX, mouseY, right, top, bottom);
      AppleScroller.draw(g, this, right, top, bottom, this.scrollBarY(), this.scrollerHeight(), this.scrollAmount(),
            hover, this.scrolling, self.getAlpha());
      if (over) g.requestCursor(this.scrolling ? CursorTypes.RESIZE_NS : CursorTypes.POINTING_HAND);
   }
}
