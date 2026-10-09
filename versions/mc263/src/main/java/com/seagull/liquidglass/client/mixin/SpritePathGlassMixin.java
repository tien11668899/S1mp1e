package com.seagull.liquidglass.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Small vanilla UI sprites that have no dedicated draw method of their own, replaced by sprite path where every blit of
 * them lands:
 * <ul>
 *   <li>{@code gamemode_switcher/slot} / {@code selection} (F3+F4) → a faint glass tile / a lifted glass tile;</li>
 *   <li>{@code hud/hotbar} / {@code hud/hotbar_selection} <b>in spectator mode only</b> (the spectator menu draws its own
 *       hotbar; the normal hotbar is already glass via {@code HudHotbarMixin}) → the HUD glass strip / a lifted tile.</li>
 * </ul>
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class SpritePathGlassMixin {

   @Inject(method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
           at = @At("HEAD"), cancellable = true)
   private void lg$pathGlass(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color, CallbackInfo ci) {
      if (sprite == null) return;
      String p = sprite.getPath();
      boolean gmSlot = p.equals("gamemode_switcher/slot"), gmSel = p.equals("gamemode_switcher/selection");
      boolean hb = p.equals("hud/hotbar"), hbSel = p.equals("hud/hotbar_selection");
      if (!(gmSlot || gmSel || hb || hbSel)) return;
      if ((hb || hbSel) && !(Minecraft.getInstance().player != null && Minecraft.getInstance().player.isSpectator())) return;
      if (!(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) return;
      ci.cancel();
      GuiGraphicsExtractor g = (GuiGraphicsExtractor) (Object) this;
      float a = ((color >>> 24) & 0xFF) / 255.0F;
      if (hb) {
         HudGlass.glassTile(g, x, y, x + w, y + h, 0.85F * a);
         return;
      }
      float corner = Math.min(1.0F, GlassCorners.HOTBAR_RADIUS / Math.max(1.0F, Math.min(w, h) / 2.0F));
      boolean lifted = gmSel || hbSel;
      GlassWidgets.capsule(g, x + 1, y + 1, x + w - 1, y + h - 1, corner, lifted ? 0.81F : 0.0F, (lifted ? 1.0F : 0.5F) * a, true);
   }
}
