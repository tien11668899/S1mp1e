package dev.s1mp1e.glass.mixin;

import java.util.WeakHashMap;

import com.google.common.collect.ImmutableList;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.SegmentedTabs;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TabButtonWidget;
import net.minecraft.client.gui.widget.TabNavigationWidget;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The tab nav bar (create-world's 遊戲 / 世界 / 更多, Video Settings' tabs, …) is drawn as ONE segmented glass control, like
 * the S1mp1e config menu's 戰鬥 / HUD / 視覺: all tabs inside one glass capsule, the selection a lifted glass pill that slides
 * to the chosen tab (eased, never jumps). Vanilla spreads the tabs across the whole bar, so first the buttons are re-packed
 * tight and centred (each sized to its label + padding), then the capsule and pill are drawn at render HEAD so the tab
 * labels land on top. The per-tab capsule ({@code TabButtonGlassMixin}) is suppressed while {@link SegmentedTabs#active}.
 */
@Mixin(TabNavigationWidget.class)
public abstract class SegmentedTabNavMixin {

   /** Padding each side of a tab label, and the gap between tab segments (GUI px) — a compact, launcher-style control. */
   @Unique private static final int S1MP1E_PAD = 18, S1MP1E_GAP = 2;
   /** Pill glide time constant (the config menu's tab slide feel). */
   @Unique private static final float S1MP1E_TAU = 0.075F;
   /** Per bar: pill x0, x1 and the last frame time (nanos). */
   @Unique private static final WeakHashMap<TabNavigationWidget, double[]> S1MP1E_PILL = new WeakHashMap<>();

   @Inject(method = "render", at = @At("HEAD"))
   private void s1mp1e$segment(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      SegmentedTabs.active = false;
      if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;
      ImmutableList<TabButtonWidget> buttons = ((TabNavAccessor) this).s1mp1e$tabButtons();
      if (buttons == null || buttons.size() < 2) return;

      // Re-pack the tabs tight and centred (vanilla spreads them across the whole bar).
      net.minecraft.client.font.TextRenderer font = MinecraftClient.getInstance().textRenderer;
      int n = buttons.size();
      int[] w = new int[n];
      int total = S1MP1E_GAP * (n - 1);
      for (int i = 0; i < n; i++) {
         w[i] = font.getWidth(buttons.get(i).getMessage()) + S1MP1E_PAD * 2;
         total += w[i];
      }
      int cx = (buttons.get(0).getX() + buttons.get(n - 1).getRight()) / 2;
      int cursor = Math.max(2, cx - total / 2);
      for (int i = 0; i < n; i++) {
         TabButtonWidget b = buttons.get(i);
         b.setX(cursor);
         b.setWidth(w[i]);
         cursor += w[i] + S1MP1E_GAP;
      }

      TabButtonWidget first = buttons.get(0), last = buttons.get(n - 1);
      float y0 = first.getY() + 2, y1 = first.getBottom() - 3;
      float x0 = first.getX() + 1, x1 = last.getRight() - 1;
      float a = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
      // the one capsule holding every tab
      AllGlass.capsule(ctx, x0, y0, x1, y1, 1.0F, 0.0F, 0.6F * a);

      TabButtonWidget sel = null;
      for (TabButtonWidget b : buttons) if (b.isCurrentTab()) { sel = b; break; }
      if (sel != null) {
         float tx0 = sel.getX() + 3, tx1 = sel.getRight() - 3;
         long now = Util.getMeasuringTimeNano();
         double[] st = S1MP1E_PILL.get((TabNavigationWidget) (Object) this);
         if (st == null) {
            st = new double[] { tx0, tx1, now };
            S1MP1E_PILL.put((TabNavigationWidget) (Object) this, st);
         }
         float dt = Math.min(0.1F, Math.max(0.0F, (now - (long) st[2]) / 1.0e9F));
         float k = 1.0F - (float) Math.exp(-dt / S1MP1E_TAU);
         st[0] += (tx0 - st[0]) * k;
         st[1] += (tx1 - st[1]) * k;
         st[2] = now;
         if (Math.abs(st[0] - tx0) < 0.05) st[0] = tx0;
         if (Math.abs(st[1] - tx1) < 0.05) st[1] = tx1;
         AllGlass.capsule(ctx, (float) st[0], y0 + 2, (float) st[1], y1 - 2, 1.0F, 0.81F, a);
      }
      SegmentedTabs.active = true;
   }

   @Inject(method = "render", at = @At("RETURN"))
   private void s1mp1e$segmentDone(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      SegmentedTabs.active = false;
   }
}
