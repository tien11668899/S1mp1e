package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AppleScroller;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ScrollableWidget;
import net.minecraft.client.util.Window;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Every scrollable widget — menu lists (worlds, servers, languages, packs, key binds, options, …) and multi-line text
 * areas — drew vanilla's scroller sprite at its right edge. It becomes the macOS overlay scroller ({@link AppleScroller})
 * at the same knob rect; the track sprite ({@code widget/scroller_background}) is dropped.
 *
 * <p>1.21.2 gave lists and text areas one base, {@code ScrollableWidget}, whose {@code drawScrollbar} draws the track and
 * then the knob (1.21.1 had a scroller of its own in {@code EntryListWidget.renderWidget}, see the 1.21.1
 * {@code EntryListScrollerMixin}). {@code drawScrollbar} gets no mouse position, so hover reads the mouse from the window.
 */
@Mixin(ScrollableWidget.class)
public abstract class ScrollableWidgetScrollerMixin {

   @Shadow private boolean scrollbarDragged;

   @Redirect(method = "drawScrollbar", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Ljava/util/function/Function;Lnet/minecraft/util/Identifier;IIII)V"))
   private void s1mp1e$scroller(DrawContext ctx, java.util.function.Function<Identifier, net.minecraft.client.render.RenderLayer> layers,
                                Identifier tex, int x, int y, int w, int h) {
      if (tex == null || !tex.getPath().equals("widget/scroller")) return;   // the track: dropped
      ClickableWidget self = (ClickableWidget) (Object) this;
      float right = x + w, top = self.getY(), bottom = self.getY() + self.getHeight();
      MinecraftClient mc = MinecraftClient.getInstance();
      Window win = mc.getWindow();
      double mx = mc.mouse.getX() * win.getScaledWidth() / Math.max(1, win.getWidth());
      double my = mc.mouse.getY() * win.getScaledHeight() / Math.max(1, win.getHeight());
      boolean hover = AppleScroller.near(mx, my, right, top, bottom);
      AppleScroller.draw(ctx, this, right, top, bottom, y, h, ((ScrollableWidget) (Object) this).getScrollY(), hover,
            this.scrollbarDragged, 1.0F);
   }
}
