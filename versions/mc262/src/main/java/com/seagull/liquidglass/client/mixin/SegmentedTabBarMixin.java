package com.seagull.liquidglass.client.mixin;

import java.util.WeakHashMap;

import com.google.common.collect.ImmutableList;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A menu tab bar (Create World's 遊戲 / 世界 / 更多, Video Settings' tabs, …) is one segmented control, like the S1mp1e
 * menu's 戰鬥 / HUD / 視覺: all tabs sit inside ONE glass capsule and the selection is a lifted glass pill that slides to
 * the chosen tab (eased, never jumps). The individual tab capsules are no longer drawn ({@code TabGlassMixin} only adds a
 * faint hover glow). Drawn at the bar's render HEAD, so the tab labels land on top.
 */
@Mixin(MenuTabBar.class)
public abstract class SegmentedTabBarMixin {

   /** Pill glide time constant (the S1mp1e menu's tab slide feel). */
   @Unique private static final float LG_TAU = 0.075F;
   /** Per bar: pill x0, x1 and the last frame time (double: nanos stay exact). */
   @Unique private static final WeakHashMap<MenuTabBar, double[]> LG_PILL = new WeakHashMap<>();

   @Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
   private void lg$segment(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      dev.s1mp1e.client.gui.SegmentedTabs.active = false;
      if (!(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) return;
      ImmutableList<TabButton> buttons = ((TabNavigationBarAccessor) (TabNavigationBar) (Object) this).liquidglass$tabButtons();
      if (buttons == null || buttons.isEmpty()) return;
      TabButton first = buttons.getFirst(), last = buttons.get(buttons.size() - 1);
      float y0 = first.getY() + 2, y1 = first.getBottom() - 3;
      float x0 = first.getX() + 1, x1 = last.getRight() - 1;
      float a = ScreenOpenFade.value(Minecraft.getInstance().gui.screen());
      // the one capsule holding every tab
      GlassWidgets.capsule(g, x0, y0, x1, y1, 1.0F, 0.0F, 0.6F * a, true);

      TabButton sel = null;
      for (TabButton b : buttons) if (b.isSelected()) { sel = b; break; }
      if (sel != null) {
         float tx0 = sel.getX() + 3, tx1 = sel.getRight() - 3;
         long now = Util.getNanos();
         double[] st = LG_PILL.get((MenuTabBar) (Object) this);
         if (st == null) {
            st = new double[] { tx0, tx1, now };
            LG_PILL.put((MenuTabBar) (Object) this, st);
         }
         float dt = Math.min(0.1F, Math.max(0.0F, (now - (long) st[2]) / 1.0e9F));
         float k = 1.0F - (float) Math.exp(-dt / LG_TAU);
         st[0] += (tx0 - st[0]) * k;
         st[1] += (tx1 - st[1]) * k;
         st[2] = now;
         if (Math.abs(st[0] - tx0) < 0.05) st[0] = tx0;
         if (Math.abs(st[1] - tx1) < 0.05) st[1] = tx1;
         GlassWidgets.capsule(g, (float) st[0], y0 + 2, (float) st[1], y1 - 2, 1.0F, 0.81F, a, true);
      }
      dev.s1mp1e.client.gui.SegmentedTabs.active = true;
   }

   @Inject(method = "extractWidgetRenderState", at = @At("RETURN"))
   private void lg$segmentDone(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      dev.s1mp1e.client.gui.SegmentedTabs.active = false;
   }

   /** Padding each side of a tab label, and the gap between tab segments (GUI px) — a compact, launcher-style control. */
   @Unique private static final int LG_PAD = 18, LG_GAP = 2;

   /**
    * Vanilla spreads the tabs evenly across the whole bar width, so the segmented capsule and its pill come out huge with
    * big empty gaps. Re-pack them right after layout: each tab sized to its label + padding, the segments flush with a 2 px
    * gap, the whole group centred on where vanilla put them. Clicks follow because each {@link TabButton}'s hit test uses
    * its own x / width. Re-runs on every resize (vanilla re-spreads, we re-pack).
    */
   @Inject(method = "arrangeElements", at = @At("RETURN"))
   private void lg$compact(int width, CallbackInfo ci) {
      ImmutableList<TabButton> buttons = ((TabNavigationBarAccessor) (TabNavigationBar) (Object) this).liquidglass$tabButtons();
      if (buttons == null || buttons.size() < 2) return;
      net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
      int n = buttons.size();
      int[] w = new int[n];
      int total = LG_GAP * (n - 1);
      for (int i = 0; i < n; i++) {
         w[i] = font.width(buttons.get(i).getMessage()) + LG_PAD * 2;
         total += w[i];
      }
      int cx = (buttons.getFirst().getX() + buttons.get(n - 1).getRight()) / 2;
      int cursor = Math.max(2, cx - total / 2);
      for (int i = 0; i < n; i++) {
         TabButton b = buttons.get(i);
         b.setX(cursor);
         b.setWidth(w[i]);
         cursor += w[i] + LG_GAP;
      }
   }
}
