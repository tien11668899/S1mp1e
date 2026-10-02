package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.BossGhost;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.BossEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss bar = the S1mp1e slider look (user: "血量的顏色要跟滑桿底部一樣是藍的, 藍色血量的下一層就放形狀一樣(照比例稍大,
 * 最左右邊比現在更圓)的液態玻璃"):
 * <ul>
 *   <li>the health fill is the config slider's blue ({@code 0x0A84FF}) as a capsule with fully round ends;</li>
 *   <li>under it, a liquid-glass capsule of the same shape, {@link #MARGIN} GUI px larger on every side, so the two are
 *       concentric (glass radius = fill radius + margin) and the ends are true semicircles
 *       ({@link GlassPipeline#capsule()}; the regular glass program caps the corner at a quarter of the short side).</li>
 * </ul>
 * Vanilla calls {@code extractBar} twice per boss: first with the background sprites (full width) -> the glass capsule,
 * then with the progress sprites (health width) -> the blue fill, so the fill always lies on top of the glass.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossBarGlassMixin {

   @Shadow @Final private static Identifier[] BAR_BACKGROUND_SPRITES;
   @Shadow @Final private static int BAR_HEIGHT;

   /** The config slider's fill blue (iOS systemBlue), opaque. */
   private static final int FILL_ARGB = 0xFF0A84FF;
   /** Glass capsule reaches this far past the fill on every side (concentric). */
   private static final int MARGIN = 2;
   /** Frost 0.5, full-capsule corner knob, no lift; low byte = opacity. */
   private static final int GLASS_KNOBS = 0x80FFFF00;
   private static final int GLASS_OPACITY = 0xE6;

   /** Appear fade length, matching the glass screen-open fade. */
   @Unique private static final float LG_FADE_S = 0.15F;
   /** Per boss: {first seen, last seen} (nanos). A gap longer than {@link #LG_GONE_NS} counts as a fresh appearance. */
   @Unique private static final HashMap<UUID, long[]> lg$seen = new HashMap<>();
   @Unique private static final long LG_GONE_NS = 250_000_000L;
   /** Fade of the boss whose bar was drawn last; vanilla draws each boss's name right after its bar. */
   @Unique private static float lg$curFade = 1F;

   /**
    * Vanilla pops a new boss bar (and its name) in on its first frame. Each boss fades in over 150 ms from the first
    * frame its bar is drawn; the glass capsule, the blue fill and the name all take that alpha.
    */
   @Unique
   private static float lg$bossFade(BossEvent boss) {
      long now = net.minecraft.util.Util.getNanos();
      long[] s = lg$seen.get(boss.getId());
      if (s == null || now - s[1] > LG_GONE_NS) {
         s = new long[]{now, now};
         lg$seen.put(boss.getId(), s);
      }
      s[1] = now;
      if (lg$seen.size() > 32) {
         Iterator<long[]> it = lg$seen.values().iterator();
         while (it.hasNext()) {
            if (now - it.next()[1] > 5_000_000_000L) it.remove();
         }
      }
      float t = (now - s[0]) / 1.0e9F / LG_FADE_S;
      return t <= 0F ? 0F : (t >= 1F ? 1F : t);
   }

   @Inject(
      method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;I[Lnet/minecraft/resources/Identifier;[Lnet/minecraft/resources/Identifier;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private void lg$glassBar(GuiGraphicsExtractor g, int x, int y, BossEvent boss, int width,
                            Identifier[] bar, Identifier[] overlay, CallbackInfo ci) {
      float f = lg$bossFade(boss);
      lg$curFade = f;
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) return;
      ci.cancel();
      // Feed the removal fade-out (BossGhost): mark drawn (cancels any pending ghost) + cache this boss's last draw params.
      UUID id = boss.getId();
      BossGhost.markDrawn(id);
      if (bar == BAR_BACKGROUND_SPRITES) BossGhost.cacheBackground(id, x, y, width, BAR_HEIGHT, boss.getName());
      else BossGhost.cacheFill(id, width, y, BAR_HEIGHT);
      if (f <= 0.004F) return;
      int h = BAR_HEIGHT;
      if (bar == BAR_BACKGROUND_SPRITES) {
         int x0 = x - MARGIN, y0 = y - MARGIN, x1 = x + width + MARGIN, y1 = y + h + MARGIN;
         if (GlassPipeline.capsuleUsable()) {
            TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
            int opacity = Math.round(GLASS_OPACITY * f) & 0xFF;
            ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState().addGuiElement(
               new GlassRectRenderState(GlassPipeline.capsule(), ts, g.pose(), x0, y0, x1, y1, 8, GLASS_KNOBS | opacity, null));
         } else {
            HudGlass.glassBox(g, x0, y0, x1, y1, 0.9F * f);
         }
      } else if (width > 0) {
         int fill = (Math.round(0xFF * f) & 0xFF) << 24 | FILL_ARGB & 0xFFFFFF;
         HudGlass.roundRect(g, x, y, x + width, y + h, h * 0.5F, fill);
      }
   }

   /** The boss name, drawn right after its bar: same fade. */
   @ModifyArg(
      method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"
      ),
      index = 4
   )
   private int lg$fadeName(int color) {
      float f = lg$curFade;
      if (f >= 1F) return color;
      int a = Math.round((color >>> 24 & 0xFF) * f) & 0xFF;
      return a << 24 | color & 0xFFFFFF;
   }
}
