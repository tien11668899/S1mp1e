package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AppleScroller;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ScrollableWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Multi-line text areas (book-style edit boxes, the telemetry event list, …) drew vanilla's scroller sprite at their right
 * edge. It becomes the macOS overlay scroller ({@link AppleScroller}) at the same knob rect.
 */
@Mixin(ScrollableWidget.class)
public abstract class ScrollableWidgetScrollerMixin {

   @Shadow protected abstract double getScrollY();

   @Redirect(method = "drawScrollbar", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
   private void s1mp1e$scroller(DrawContext ctx, Identifier tex, int x, int y, int w, int h) {
      ClickableWidget self = (ClickableWidget) (Object) this;
      float right = x + w, top = self.getY(), bottom = self.getY() + self.getHeight();
      AppleScroller.draw(ctx, this, right, top, bottom, y, h, this.getScrollY(), self.isHovered(), false, 1.0F);
   }
}
