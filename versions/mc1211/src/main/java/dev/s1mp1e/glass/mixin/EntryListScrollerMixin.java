package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AppleScroller;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every list page (worlds, servers, packs, languages, controls, stats, …) drew vanilla's grey scroller sprite over a dark
 * track. Both blits in {@code renderWidget} are taken over: the track is dropped, and the knob becomes the macOS overlay
 * scroller ({@link AppleScroller}) at exactly the rect vanilla computed — so dragging stays 1:1.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListScrollerMixin {

   @Shadow private boolean scrolling;
   @Shadow public abstract double getScrollAmount();

   @Unique private int s1mp1e$mx, s1mp1e$my;

   @Inject(method = "renderWidget", at = @At("HEAD"))
   private void s1mp1e$mouse(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      this.s1mp1e$mx = mouseX;
      this.s1mp1e$my = mouseY;
   }

   @Redirect(method = "renderWidget", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
   private void s1mp1e$scroller(DrawContext ctx, Identifier tex, int x, int y, int w, int h) {
      if (tex == null || !tex.getPath().equals("widget/scroller")) return;   // the track ("scroller_background"): dropped
      ClickableWidget self = (ClickableWidget) (Object) this;
      float right = x + w, top = self.getY(), bottom = self.getY() + self.getHeight();
      boolean hover = AppleScroller.near(this.s1mp1e$mx, this.s1mp1e$my, right, top, bottom);
      AppleScroller.draw(ctx, this, right, top, bottom, y, h, this.getScrollAmount(), hover, this.scrolling, 1.0F);
   }
}
