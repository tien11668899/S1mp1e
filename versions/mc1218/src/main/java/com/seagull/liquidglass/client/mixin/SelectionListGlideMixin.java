package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.GuiAlpha;
import dev.s1mp1e.client.gui.SmoothScrollHost;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Selection lists (worlds, servers, languages, resource packs …):
 * <ul>
 *   <li><b>Gliding selection box</b> — vanilla draws the selected row's box inside that row's {@code renderItem}, so it
 *       teleports when the selection changes. Here the box is drawn once at the HEAD of {@code renderListItems} (under
 *       every row, inside the list scissor) at an eased row top kept in CONTENT coordinates — it glides between rows but
 *       stays glued to the content while the list scrolls — and vanilla's per-row call is skipped. The box itself is still
 *       vanilla's {@code renderSelection} (subclass overrides included), just translated.</li>
 *   <li><b>Entries arriving later</b> (the world list after its async load, LAN servers, a search refill …) cascade
 *       in — each rises 6 px and fades in ({@link GuiAlpha}) on a critically damped spring, 30 ms apart. Entries already
 *       there on the list's first frame are left alone.</li>
 *   <li><b>Keyboard scrolling</b> — {@code scroll(int)} (arrow-key navigation keeping the selection in view) glides like
 *       the wheel does ({@link SmoothScrollHost}).</li>
 * </ul>
 *
 * <p>1.21.8 (javap): rows are drawn by index — {@code renderListItems} calls {@code renderItem(g, mouseX, mouseY, delta,
 * index, left, top, width, height)} with {@code height = itemHeight - 4}, which draws the box through
 * {@code renderSelection(g, top, width, height, outline, inner)} when {@code isSelectedItem(index)}. 26.x passes the
 * entry instead ({@code extractItem} / {@code renderSelection(g, entry, color)}).
 */
@Mixin(AbstractSelectionList.class)
public abstract class SelectionListGlideMixin {

   @Unique private static final float LG_TAU = 0.07F;

   @Shadow @Final protected int itemHeight;

   @Shadow protected abstract boolean isSelectedItem(int index);

   @Shadow protected abstract void renderSelection(GuiGraphics g, int top, int width, int height, int outerColor, int innerColor);

   @Shadow public abstract int getRowTop(int index);

   @Shadow public abstract int getRowWidth();

   @Unique private static final float LG_ENTER_W = 18.0F;   // ~0.3 s settle
   @Unique private static final float LG_ENTER_RISE = 6.0F;
   @Unique private final IdentityHashMap<Object, Long> lg$born = new IdentityHashMap<>();
   @Unique private boolean lg$primed;
   @Unique private boolean lg$pushed;

   @Unique private float lg$selY = Float.NaN;      // eased row top, content coordinates
   @Unique private int lg$selIndex = -1;           // the row whose box was drawn this frame, -1 = none
   @Unique private long lg$selNs;

   @Inject(method = "renderListItems", at = @At("HEAD"))
   private void lg$glidingSelection(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      lg$trackEntries();
      AbstractSelectionList<?> self = (AbstractSelectionList<?>) (Object) this;
      Object sel = self.getSelected();
      int index = sel == null ? -1 : self.children().indexOf(sel);
      if (index < 0 || !this.isSelectedItem(index)) { lg$selY = Float.NaN; lg$selIndex = -1; return; }
      double scroll = ((AbstractScrollArea) (Object) this).scrollAmount();
      int top = this.getRowTop(index);
      float target = (float) (top + scroll);
      long now = net.minecraft.Util.getNanos();
      if (Float.isNaN(lg$selY) || lg$selIndex < 0) {
         lg$selY = target;                                  // first selection: appear in place
      } else {
         float dt = lg$selNs == 0L ? 1F / 60F : Math.min(0.05F, (now - lg$selNs) / 1.0e9F);
         lg$selY += (target - lg$selY) * (1F - (float) Math.exp(-dt / LG_TAU));
         if (Math.abs(target - lg$selY) < 0.1F) lg$selY = target;
      }
      lg$selNs = now;
      lg$selIndex = index;
      int color = ((AbstractWidget) (Object) this).isFocused() ? -1 : -8355712;   // vanilla's two outline colours
      Matrix3x2fStack pose = g.pose();
      pose.pushMatrix();
      pose.translate(0F, lg$selY - target);
      try {
         this.renderSelection(g, top, this.getRowWidth(), this.itemHeight - 4, color, -16777216);
      } finally {
         pose.popMatrix();
      }
   }

   /** Stamp entries the first time they are seen; those present on the very first frame count as settled. */
   @Unique
   private void lg$trackEntries() {
      List<?> ch = ((AbstractSelectionList<?>) (Object) this).children();
      if (!lg$primed) {
         for (Object e : ch) lg$born.put(e, Long.MIN_VALUE);
         lg$primed = true;
         return;
      }
      long now = net.minecraft.Util.getNanos();
      int k = 0;
      for (Object e : ch) {
         if (!lg$born.containsKey(e)) { lg$born.put(e, now + Math.min(k * 30_000_000L, 240_000_000L)); k++; }
      }
      if (lg$born.size() > ch.size() + 32) {           // forget removed entries
         IdentityHashMap<Object, Long> keep = new IdentityHashMap<>();
         for (Object e : ch) keep.put(e, lg$born.get(e));
         lg$born.clear();
         lg$born.putAll(keep);
      }
   }

   /** A newly arrived entry: draw it risen-from-below and faded, easing in (the selection box is drawn elsewhere). */
   @Inject(method = "renderItem", at = @At("HEAD"))
   private void lg$entryEnterBegin(GuiGraphics g, int mouseX, int mouseY, float delta, int index, int left, int top,
                                   int width, int height, CallbackInfo ci) {
      lg$pushed = false;
      List<?> ch = ((AbstractSelectionList<?>) (Object) this).children();
      if (index < 0 || index >= ch.size()) return;
      Object entry = ch.get(index);
      Long born = lg$born.get(entry);
      if (born == null || born == Long.MIN_VALUE) return;
      float t = (net.minecraft.Util.getNanos() - born) / 1.0e9F;
      float p = t <= 0F ? 0F : 1F - (1F + LG_ENTER_W * t) * (float) Math.exp(-LG_ENTER_W * t);
      if (p >= 0.998F) { lg$born.put(entry, Long.MIN_VALUE); return; }
      float inv = 1F - p;
      GuiAlpha.push(1F - inv * inv);
      g.pose().pushMatrix();
      g.pose().translate(0F, LG_ENTER_RISE * inv);
      lg$pushed = true;
   }

   @Inject(method = "renderItem", at = @At("RETURN"))
   private void lg$entryEnterEnd(GuiGraphics g, int mouseX, int mouseY, float delta, int index, int left, int top,
                                 int width, int height, CallbackInfo ci) {
      if (!lg$pushed) return;
      lg$pushed = false;
      g.pose().popMatrix();
      GuiAlpha.pop();
   }

   /** Vanilla's per-row selection box: already drawn (gliding, under all rows) above. */
   @Redirect(
      method = "renderItem",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/components/AbstractSelectionList;renderSelection(Lnet/minecraft/client/gui/GuiGraphics;IIIII)V")
   )
   private void lg$skipRowSelection(AbstractSelectionList<?> self, GuiGraphics g, int top, int width, int height, int outer,
                                    int inner, GuiGraphics gEnc, int mouseX, int mouseY, float delta, int index) {
      if (index != lg$selIndex) this.renderSelection(g, top, width, height, outer, inner);   // safety: only skip the one we drew
   }

   /** Arrow-key navigation keeps the selection in view by gliding, like the wheel. */
   @Redirect(
      method = "scroll(I)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/AbstractSelectionList;setScrollAmount(D)V")
   )
   private void lg$smoothKeyboardScroll(AbstractSelectionList<?> self, double amount) {
      ((SmoothScrollHost) (Object) self).liquidglass$glideTo(amount);
   }
}
