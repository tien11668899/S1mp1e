package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.SelectionGlass;
import dev.s1mp1e.client.gui.GuiAlpha;
import dev.s1mp1e.client.gui.SelectionGlideHost;
import dev.s1mp1e.client.gui.SmoothScrollHost;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.LayoutElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Selection lists (worlds, servers, languages, resource packs …):
 * <ul>
 *   <li><b>Gliding selection box</b> — vanilla draws the selected row's box inside that row's {@code renderItem}, so it
 *       teleports when the selection changes. Here the glass highlight ({@link SelectionGlass}) is drawn once at the HEAD
 *       of {@code renderListItems} (under every row, inside the list scissor) at an eased position kept in CONTENT
 *       coordinates — it glides between rows but stays glued to the content while the list scrolls — and the row's own
 *       box is skipped ({@code SelectionGlassMixin} asks {@link SelectionGlideHost}). Without glass, vanilla's box stays
 *       where it is (no glide).</li>
 *   <li><b>Entries arriving later</b> (the world list after its async load, LAN servers, a search refill …) cascade
 *       in — each rises 6 px and fades in ({@link GuiAlpha}) on a critically damped spring, 30 ms apart. Entries already
 *       there on the list's first frame are left alone.</li>
 *   <li><b>Keyboard scrolling</b> — {@code scroll(int)} (arrow-key navigation keeping the selection in view) glides like
 *       the wheel does ({@link SmoothScrollHost}).</li>
 * </ul>
 *
 * <p>1.21.9+ (javap): {@code renderListItems} → {@code renderItem(g, mouseX, mouseY, delta, entry)}, which draws the box
 * through {@code renderSelection(g, entry, outline)} when {@code entriesCanBeSelected() && getSelected() == entry}.
 * {@code AbstractSelectionList.Entry} is a protected nested class, so entries are handled as their public
 * {@link LayoutElement} face; the box is drawn by our own glass rather than by calling the protected method (26.x does
 * that by reflection, which on 1.21.x's intermediary names cannot find it).
 */
@Mixin(AbstractSelectionList.class)
public abstract class SelectionListGlideMixin implements SelectionGlideHost {

   @Unique private static final float LG_TAU = 0.07F;

   @Shadow protected abstract boolean entriesCanBeSelected();

   @Unique private static final float LG_ENTER_W = 18.0F;   // ~0.3 s settle
   @Unique private static final float LG_ENTER_RISE = 6.0F;
   @Unique private final IdentityHashMap<Object, Long> lg$born = new IdentityHashMap<>();
   @Unique private boolean lg$primed;
   @Unique private boolean lg$pushed;

   @Unique private float lg$selY = Float.NaN;      // eased box top, content coordinates
   @Unique private Object lg$selEntry;              // the entry whose box was drawn this frame, null = none
   @Unique private long lg$selNs;

   @Override
   public boolean liquidglass$glidesOver(Object entry) {
      return entry != null && entry == lg$selEntry;
   }

   @Inject(method = "renderListItems", at = @At("HEAD"))
   private void lg$glidingSelection(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      lg$trackEntries();
      Object selObj = entriesCanBeSelected() ? ((AbstractSelectionList<?>) (Object) this).getSelected() : null;
      if (!(selObj instanceof LayoutElement sel) || !SelectionGlass.usable()) { lg$selY = Float.NaN; lg$selEntry = null; return; }
      double scroll = ((AbstractScrollArea) (Object) this).scrollAmount();
      float target = (float) (sel.getY() + scroll);
      long now = net.minecraft.util.Util.getNanos();
      if (Float.isNaN(lg$selY) || lg$selEntry == null) {
         lg$selY = target;                                  // first selection: appear in place
      } else {
         float dt = lg$selNs == 0L ? 1F / 60F : Math.min(0.05F, (now - lg$selNs) / 1.0e9F);
         lg$selY += (target - lg$selY) * (1F - (float) Math.exp(-dt / LG_TAU));
         if (Math.abs(target - lg$selY) < 0.1F) lg$selY = target;
      }
      lg$selNs = now;
      lg$selEntry = sel;
      float dy = lg$selY - target;
      boolean focused = ((AbstractWidget) (Object) this).isFocused();   // vanilla: white outline when focused
      float x0 = sel.getX(), y0 = sel.getY() + dy;
      SelectionGlass.draw(g, x0, y0, x0 + sel.getWidth(), y0 + sel.getHeight(), focused);
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
      long now = net.minecraft.util.Util.getNanos();
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
   private void lg$entryEnterBegin(GuiGraphics g, int mouseX, int mouseY, float delta, @Coerce Object entry, CallbackInfo ci) {
      lg$pushed = false;
      Long born = lg$born.get(entry);
      if (born == null || born == Long.MIN_VALUE) return;
      float t = (net.minecraft.util.Util.getNanos() - born) / 1.0e9F;
      float p = t <= 0F ? 0F : 1F - (1F + LG_ENTER_W * t) * (float) Math.exp(-LG_ENTER_W * t);
      if (p >= 0.998F) { lg$born.put(entry, Long.MIN_VALUE); return; }
      float inv = 1F - p;
      GuiAlpha.push(1F - inv * inv);
      g.pose().pushMatrix();
      g.pose().translate(0F, LG_ENTER_RISE * inv);
      lg$pushed = true;
   }

   @Inject(method = "renderItem", at = @At("RETURN"))
   private void lg$entryEnterEnd(GuiGraphics g, int mouseX, int mouseY, float delta, @Coerce Object entry, CallbackInfo ci) {
      if (!lg$pushed) return;
      lg$pushed = false;
      g.pose().popMatrix();
      GuiAlpha.pop();
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
