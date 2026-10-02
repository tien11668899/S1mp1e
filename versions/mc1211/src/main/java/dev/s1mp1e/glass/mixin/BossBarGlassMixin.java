package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.BossGhost;
import dev.s1mp1e.glass.render.GlassProgram;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss bar = the S1mp1e slider look (G3): the health fill becomes the config slider's blue ({@code 0x0A84FF}) as a capsule
 * with fully round ends; under it a liquid-glass capsule of the same shape {@link #MARGIN} px larger on every side
 * (concentric), with TRUE semicircle ends.
 *
 * <p>1.21.1 path (verified with javap): {@code BossBarHud.renderBossBar(DrawContext, int x, int y, BossBar, int width,
 * Identifier[] a, Identifier[] b)} is called twice per bar by the 4-arg overload — first with the BACKGROUND texture arrays
 * (full width) and then, if progress &gt; 0, with the PROGRESS arrays (health width). We inject at HEAD and distinguish by
 * the first array's identity: the background call becomes the glass capsule, the progress call becomes the blue fill on top,
 * and each vanilla draw is cancelled.
 *
 * <p><b>Appear / remove fade.</b> Each boss fades IN over 150 ms from the first frame its bar is drawn (glass, fill and name
 * all take one alpha), and fades OUT when removed via {@link BossGhost} (fed the drawn set + cached draw params here;
 * {@code BossOverlayGhostMixin} rotates and paints the ghosts around the boss-bar HUD layer).
 */
@Mixin(BossBarHud.class)
public abstract class BossBarGlassMixin {

   @Shadow @Final private static Identifier[] BACKGROUND_TEXTURES;
   @Shadow @Final private static Identifier[] NOTCHED_BACKGROUND_TEXTURES;
   @Shadow @Final private static int HEIGHT;

   /** The config slider's fill blue (iOS systemBlue), opaque. */
   private static final int FILL_ARGB = 0xFF0A84FF;
   /** Glass capsule reaches this far past the fill on every side (concentric). */
   private static final int MARGIN = 2;
   /** LENS opacity (26.2 0xE6) and frost (26.2 0x80 = 0.5). */
   private static final float GLASS_OPACITY = 0xE6 / 255f;
   private static final float GLASS_FROST   = 0x80 / 255f;

   /** Appear fade length, matching the glass screen-open fade. */
   @Unique private static final float LG_FADE_S = 0.15F;
   /** Per boss: {first seen, last seen} (nanos). A gap longer than {@link #LG_GONE_NS} counts as a fresh appearance. */
   @Unique private static final HashMap<UUID, long[]> lg$seen = new HashMap<>();
   @Unique private static final long LG_GONE_NS = 250_000_000L;
   /** Fade of the boss whose bar was drawn last; vanilla draws each boss's name right after its bar. */
   @Unique private static float lg$curFade = 1F;

   /**
    * Vanilla pops a new boss bar (and its name) in on its first frame. Each boss fades in over 150 ms from the first frame
    * its bar is drawn; the glass capsule, the blue fill and the name all take that alpha.
    */
   @Unique
   private static float lg$bossFade(BossBar bar) {
      long now = System.nanoTime();
      UUID id = bar.getUuid();
      long[] s = lg$seen.get(id);
      if (s == null || now - s[1] > LG_GONE_NS) {
         s = new long[]{now, now};
         lg$seen.put(id, s);
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
      method = "renderBossBar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/entity/boss/BossBar;I[Lnet/minecraft/util/Identifier;[Lnet/minecraft/util/Identifier;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private void s1mp1e$glassBar(DrawContext ctx, int x, int y, BossBar bar, int width,
                                Identifier[] a, Identifier[] b, CallbackInfo ci) {
      if (!GlassProgram.ensureReady()) return;   // pipeline down -> vanilla boss bar
      ci.cancel();
      int h = HEIGHT;
      boolean background = (a == BACKGROUND_TEXTURES || a == NOTCHED_BACKGROUND_TEXTURES);
      float f = lg$bossFade(bar);
      lg$curFade = f;
      // Feed the removal fade-out (BossGhost): mark drawn (cancels any pending ghost) + cache this boss's last draw params.
      UUID id = bar.getUuid();
      BossGhost.markDrawn(id);
      if (background) BossGhost.cacheBackground(id, x, y, width, h, bar.getName());
      else BossGhost.cacheFill(id, width, y, h);
      if (f <= 0.004F) return;
      if (background) {
         HudGlass.capsuleCtx(ctx, x - MARGIN, y - MARGIN, x + width + MARGIN, y + h + MARGIN,
                             GLASS_OPACITY * f, GLASS_FROST);
      } else if (width > 0) {
         int fill = (Math.round(0xFF * f) & 0xFF) << 24 | FILL_ARGB & 0xFFFFFF;
         HudGlass.roundFillCtx(ctx, x, y, x + width, y + h, h * 0.5f, fill);
      }
   }

   /** The boss name, drawn right after its bar in {@code render}: same fade. */
   @ModifyArg(
      method = "render(Lnet/minecraft/client/gui/DrawContext;)V",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"),
      index = 4
   )
   private int s1mp1e$fadeName(int color) {
      float f = lg$curFade;
      if (f >= 1F) return color;
      int a = Math.round((color >>> 24 & 0xFF) * f) & 0xFF;
      return a << 24 | color & 0xFFFFFF;
   }
}
