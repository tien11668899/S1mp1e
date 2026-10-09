package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AppleScroller;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ScrollableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Multi-line text areas (book-style edit boxes, the telemetry event list, …). 1.20.1 {@code drawScrollbar} draws a grey
 * knob body ({@code 0xFF808080}) and a lighter highlight ({@code 0xFFC0C0C0}) with {@code fill} — no track. The knob
 * becomes the macOS overlay scroller ({@link AppleScroller}) at the same rect; the highlight is dropped.
 */
@Mixin(ScrollableWidget.class)
public abstract class ScrollableWidgetScrollerMixin {

   @Shadow protected abstract double getScrollY();

   @Redirect(method = "drawScrollbar", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
   private void s1mp1e$scroller(DrawContext ctx, int x0, int y0, int x1, int y1, int argb) {
      if ((argb & 0xFFFFFF) != 0x808080) return;   // highlight (0xFFC0C0C0): dropped
      ClickableWidget self = (ClickableWidget) (Object) this;
      float right = x1, top = self.getY(), bottom = self.getY() + self.getHeight();
      AppleScroller.draw(ctx, this, right, top, bottom, y0, y1 - y0, this.getScrollY(), self.isHovered(), false, 1.0F);
   }
}
