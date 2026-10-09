package com.seagull.liquidglass.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The bar above the hotbar (experience, horse jump, locator) was vanilla's dark segmented sprite under the glass hotbar.
 * Caught by sprite path where every blit of it lands (like {@link SfIconMixin}):
 * <ul>
 *   <li>{@code hud/*_bar_background} → a glass capsule track (the button/tab glass material — a true capsule, which the
 *       refracting program can't make at 5 px tall);</li>
 *   <li>{@code hud/experience_bar_progress} → a round-ended system-green fill, {@code hud/jump_bar_progress} system orange,
 *       {@code hud/jump_bar_cooldown} a dim grey — each exactly the width vanilla would have drawn.</li>
 * </ul>
 * Waypoint dots/arrows on the locator bar and the level number are other sprites/text and draw unchanged on top.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ContextualBarGlassMixin {

   @Inject(method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
           at = @At("HEAD"), cancellable = true)
   private void lg$track(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color, CallbackInfo ci) {
      if (sprite == null) return;
      String p = sprite.getPath();
      if (!(p.equals("hud/experience_bar_background") || p.equals("hud/jump_bar_background") || p.equals("hud/locator_bar_background"))) return;
      if (!(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) return;
      ci.cancel();
      float a = ((color >>> 24) & 0xFF) / 255.0F;
      GlassWidgets.capsule((GuiGraphicsExtractor) (Object) this, x - 0.5F, y - 0.5F, x + w + 0.5F, y + h + 0.5F, 1.0F, 0.0F, 0.9F * a, true);
   }

   @Inject(method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIIII)V",
           at = @At("HEAD"), cancellable = true)
   private void lg$fill(RenderPipeline pipeline, Identifier sprite, int texW, int texH, int u, int v, int x, int y, int w, int h,
                        int color, CallbackInfo ci) {
      if (sprite == null) return;
      String p = sprite.getPath();
      int rgb;
      if (p.equals("hud/experience_bar_progress")) rgb = 0x30D158;
      else if (p.equals("hud/jump_bar_progress")) rgb = 0xFF9F0A;
      else if (p.equals("hud/jump_bar_cooldown")) rgb = 0x8E8E93;
      else return;
      if (!(GlassPipeline.ensureReady() && GlassPipeline.roundUsable())) return;
      ci.cancel();
      if (w <= 0) return;
      int a = Math.round(((color >>> 24) & 0xFF) * 0.95F);
      GlassWidgets.fillRound((GuiGraphicsExtractor) (Object) this, x, y + 0.5F, x + w, y + h - 0.5F, a << 24 | rgb, (h - 1) / 2.0F);
   }
}
