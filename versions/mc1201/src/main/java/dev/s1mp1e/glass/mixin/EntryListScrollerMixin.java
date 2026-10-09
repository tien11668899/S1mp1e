package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AppleScroller;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.EntryListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every list page (worlds, servers, packs, languages, controls, stats, …). 1.20.1 has no GUI sprites: {@code render}
 * draws the scrollbar with three {@code fill(IIIII)} calls — a full-height black track, a grey knob body and a lighter
 * knob highlight (colours {@code 0xFF000000 / 0xFF808080 / 0xFFC0C0C0}). The track is dropped and the knob becomes the
 * macOS overlay scroller ({@link AppleScroller}) at exactly the rect vanilla computed, so dragging stays 1:1 and the
 * scroller is simply not drawn when the list fits (vanilla skips the whole block). Colour-matched rather than counted so
 * other fills in {@code render} (there are none) could never be mistaken for the bar.
 */
@Mixin(EntryListWidget.class)
public abstract class EntryListScrollerMixin {

   @Shadow private boolean scrolling;
   @Shadow public abstract double getScrollAmount();

   @Unique private int s1mp1e$mx, s1mp1e$my;
   @Unique private float s1mp1e$right, s1mp1e$top, s1mp1e$bottom;

   @Inject(method = "render", at = @At("HEAD"))
   private void s1mp1e$mouse(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      this.s1mp1e$mx = mouseX;
      this.s1mp1e$my = mouseY;
   }

   @Redirect(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
   private void s1mp1e$scroller(DrawContext ctx, int x0, int y0, int x1, int y1, int argb) {
      if (argb == 0xFF000000) {                 // the track: stash its rect, draw nothing
         this.s1mp1e$right = x1;
         this.s1mp1e$top = y0;
         this.s1mp1e$bottom = y1;
         return;
      }
      if ((argb & 0xFFFFFF) == 0x808080) {       // the knob body: the overlay scroller at this rect
         boolean hover = AppleScroller.near(this.s1mp1e$mx, this.s1mp1e$my, this.s1mp1e$right, this.s1mp1e$top, this.s1mp1e$bottom);
         AppleScroller.draw(ctx, this, this.s1mp1e$right, this.s1mp1e$top, this.s1mp1e$bottom,
               y0, y1 - y0, this.getScrollAmount(), hover, this.scrolling, 1.0F);
         return;
      }
      // the knob highlight (0xFFC0C0C0): dropped
   }
}
