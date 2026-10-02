package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.GuiAlpha;
import dev.s1mp1e.client.gui.SmoothScrollHost;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.LayoutElement;
import java.lang.reflect.Method;
import org.joml.Matrix3x2fStack;
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
 *   <li><b>Gliding selection box</b> — vanilla draws the selected row's box inside that row's {@code extractItem}, so it
 *       teleports when the selection changes. Here the box is drawn once at the HEAD of {@code extractListItems} (under
 *       every row, inside the list scissor) at an eased position kept in CONTENT coordinates — it glides between rows but
 *       stays glued to the content while the list scrolls — and vanilla's per-row call is skipped. The box itself is still
 *       vanilla's {@code extractSelection} (subclass overrides included), just translated.</li>
 *   <li><b>Entries arriving later</b> (the world list after its async load, LAN servers, a search refill …) cascade
 *       in — each rises 6 px and fades in ({@link GuiAlpha}) on a critically damped spring, 30 ms apart. Entries already
 *       there on the list's first frame are left alone.</li>
 *   <li><b>Keyboard scrolling</b> — {@code scroll(int)} (arrow-key navigation keeping the selection in view) glides like
 *       the wheel does ({@link SmoothScrollHost}).</li>
 * </ul>
 */
@Mixin(AbstractSelectionList.class)
public abstract class SelectionListGlideMixin {

   @Unique private static final float LG_TAU = 0.07F;

   @Shadow protected abstract boolean entriesCanBeSelected();

   /**
    * {@code AbstractSelectionList.Entry} is a protected nested class, so it can't be named from this package: entries are
    * handled as their public {@link LayoutElement} face, and the protected {@code extractSelection} is invoked through a
    * cached reflective handle (still a virtual call, so a list that overrides the box look keeps it).
    */
   @Unique private static Method lg$extractSelection;
   @Unique private static boolean lg$reflectFailed;

   @Unique
   private boolean lg$drawSelection(GuiGraphicsExtractor g, Object entry, int color) {
      try {
         if (lg$extractSelection == null) {
            Class<?> entryCls = Class.forName("net.minecraft.client.gui.components.AbstractSelectionList$Entry");
            Method m = AbstractSelectionList.class.getDeclaredMethod("extractSelection", GuiGraphicsExtractor.class, entryCls, int.class);
            m.setAccessible(true);
            lg$extractSelection = m;
         }
         lg$extractSelection.invoke(this, g, entry, color);
         return true;
      } catch (Throwable t) {
         if (!lg$reflectFailed) { lg$reflectFailed = true; System.out.println("[S1mp1e] list selection glide disabled: " + t); }
         return false;
      }
   }

   @Unique private static final float LG_ENTER_W = 18.0F;   // ~0.3 s settle
   @Unique private static final float LG_ENTER_RISE = 6.0F;
   @Unique private final IdentityHashMap<Object, Long> lg$born = new IdentityHashMap<>();
   @Unique private boolean lg$primed;
   @Unique private boolean lg$pushed;

   @Unique private float lg$selY = Float.NaN;      // eased box top, content coordinates
   @Unique private Object lg$selEntry;
   @Unique private long lg$selNs;

   @Inject(method = "extractListItems", at = @At("HEAD"))
   private void lg$glidingSelection(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      lg$trackEntries();
      Object selObj = entriesCanBeSelected() ? ((AbstractSelectionList<?>) (Object) this).getSelected() : null;
      if (lg$reflectFailed || !(selObj instanceof LayoutElement sel)) { lg$selY = Float.NaN; lg$selEntry = null; return; }
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
      int color = ((AbstractWidget) (Object) this).isFocused() ? -1 : -8355712;   // vanilla's two outline colours
      Matrix3x2fStack pose = g.pose();
      pose.pushMatrix();
      pose.translate(0F, lg$selY - target);
      try {
         if (!lg$drawSelection(g, sel, color)) lg$selEntry = null;   // failed: let vanilla draw its own box
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
   @Inject(method = "extractItem", at = @At("HEAD"))
   private void lg$entryEnterBegin(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, @Coerce Object entry, CallbackInfo ci) {
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

   @Inject(method = "extractItem", at = @At("RETURN"))
   private void lg$entryEnterEnd(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, @Coerce Object entry, CallbackInfo ci) {
      if (!lg$pushed) return;
      lg$pushed = false;
      g.pose().popMatrix();
      GuiAlpha.pop();
   }

   /** Vanilla's per-row selection box: already drawn (gliding, under all rows) above. */
   @Redirect(
      method = "extractItem",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/components/AbstractSelectionList;extractSelection(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/components/AbstractSelectionList$Entry;I)V")
   )
   private void lg$skipRowSelection(AbstractSelectionList self, GuiGraphicsExtractor g, @Coerce LayoutElement entry, int color) {
      if (lg$selEntry != entry) lg$drawSelection(g, entry, color);   // safety: only skip the one we drew
   }

   /** Arrow-key navigation keeps the selection in view by gliding, like the wheel. */
   @Redirect(
      method = "scroll(I)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/AbstractSelectionList;setScrollAmount(D)V")
   )
   private void lg$smoothKeyboardScroll(AbstractSelectionList self, double amount) {
      ((SmoothScrollHost) (Object) self).liquidglass$glideTo(amount);
   }
}
