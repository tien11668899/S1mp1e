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
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The tab nav bar (create-world's 遊戲 / 世界 / 更多, …) is drawn as ONE segmented glass control, like the S1mp1e config
 * menu's tabs: all tabs inside one glass capsule, the selection a lifted glass pill that slides to the chosen tab (eased,
 * never jumps). The per-tab capsule ({@code TabButtonGlassMixin}) is suppressed while {@link SegmentedTabs#active}.
 *
 * <p>1.20.1 {@code TabNavigationWidget.render} paints its own background first — a black bar {@code fill} and the
 * header-separator {@code drawTexture} under the grid — THEN loops the tab buttons. The black bar is dropped (the glass
 * control floats on the screen backdrop), the separator is re-issued through DrawContext so it becomes the #6 hairline
 * ({@code AllGlassTextureMixin}), and the capsule + sliding pill are drawn in that same {@code drawTexture} redirect,
 * i.e. after the background and immediately before the tab labels, so the labels land on top. The tabs are re-packed
 * tight and centred there too (vanilla spreads them across the bar); the capsule reads the buttons' positions back after
 * re-packing, so it stays aligned with the labels whether or not the re-pack takes.
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
   private void s1mp1e$reset(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      SegmentedTabs.active = false;
   }

   /** Drop the black top-bar fill (glass control floats on the backdrop). */
   @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
   private void s1mp1e$dropBar(DrawContext ctx, int x0, int y0, int x1, int y1, int argb) {
      if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) ctx.fill(x0, y0, x1, y1, argb);
   }

   /** Drop the menu-list background texture and, in its place (after the bg, before the tab labels), draw the segmented
    *  glass capsule + sliding selection pill. */
   @Redirect(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"))
   private void s1mp1e$segment(DrawContext ctx, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
      // This blit is the HEADER separator under the bar; drawing it through DrawContext lets AllGlassTextureMixin turn
      // it into the #6 hairline (and leaves it vanilla when there is no glass).
      ctx.drawTexture(tex, x, y, u, v, w, h, tw, th);
      if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;
      ImmutableList<TabButtonWidget> buttons = ((TabNavAccessor) this).s1mp1e$tabButtons();
      if (buttons == null || buttons.size() < 2) return;

      // Re-pack the tabs tight and centred (vanilla spreads them across the whole bar).
      net.minecraft.client.font.TextRenderer font = MinecraftClient.getInstance().textRenderer;
      int n = buttons.size();
      int[] cw = new int[n];
      int total = S1MP1E_GAP * (n - 1);
      for (int i = 0; i < n; i++) {
         cw[i] = font.getWidth(buttons.get(i).getMessage()) + S1MP1E_PAD * 2;
         total += cw[i];
      }
      int cx = (buttons.get(0).getX() + buttons.get(n - 1).getX() + buttons.get(n - 1).getWidth()) / 2;
      int cursor = Math.max(2, cx - total / 2);
      for (int i = 0; i < n; i++) {
         TabButtonWidget b = buttons.get(i);
         b.setX(cursor);
         b.setWidth(cw[i]);
         cursor += cw[i] + S1MP1E_GAP;
      }

      TabButtonWidget first = buttons.get(0), last = buttons.get(n - 1);
      float y0 = first.getY() + 2, y1 = first.getY() + first.getHeight() - 3;
      float x0 = first.getX() + 1, x1 = last.getX() + last.getWidth() - 1;
      float a = ScreenOpenFade.value(MinecraftClient.getInstance().currentScreen);
      // the one capsule holding every tab
      AllGlass.capsule(ctx, x0, y0, x1, y1, 1.0F, 0.0F, 0.6F * a);

      TabButtonWidget sel = null;
      for (TabButtonWidget b : buttons) if (b.isCurrentTab()) { sel = b; break; }
      if (sel != null) {
         float tx0 = sel.getX() + 3, tx1 = sel.getX() + sel.getWidth() - 3;
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
      SegmentedTabs.active = true;   // for the upcoming tab-button loop; reset at RETURN
   }

   @Inject(method = "render", at = @At("RETURN"))
   private void s1mp1e$segmentDone(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      SegmentedTabs.active = false;
   }
}
