package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The advancement category tabs, caught by sprite path. In the glass window ({@code AdvancementsGlassMixin}) each tab row is
 * a band of the SAME glass sheet (creative-inventory style), so the vanilla tab tiles are dropped and only the SELECTED tab
 * gets a lifted glass pill inset into its band. The tab icons (drawn separately via {@code extractIcon}) are untouched, so
 * they still sit on the band. The hover title box and the advancement frames are content (readability / type encoding) and
 * stay vanilla.
 *
 * <p>The tabs are drawn with the 4-int {@code blitSprite(RP, Id, x, y, w, h)} overload (no colour arg), so this must target
 * that one — not the {@code (…IIIII)} variant, which the tabs never use.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class AdvancementSpritesGlassMixin {

   @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
           at = @At("HEAD"), cancellable = true)
   private void lg$advTabs(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, CallbackInfo ci) {
      if (sprite == null) return;
      String p = sprite.getPath();
      if (!p.startsWith("advancements/tab_")) return;
      if (!(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) return;
      ci.cancel(); // drop the vanilla tab tile — the band glass (AdvancementsGlassMixin) is the tab row's surface
      if (!p.endsWith("_selected")) return;
      GuiGraphicsExtractor g = (GuiGraphicsExtractor) (Object) this;
      // Selected tab: a lifted glass pill inset into the band, leaving clear the 4 px the tab sprite overlaps the window.
      float px0 = x + 3, py0 = y + 3, px1 = x + w - 3, py1 = y + h - 3;
      if (p.contains("_above_")) py1 = y + h - 7;
      else if (p.contains("_below_")) py0 = y + 7;
      else if (p.contains("_left_")) px1 = x + w - 7;
      else if (p.contains("_right_")) px0 = x + 7;
      float c = Math.min(1.0F, GlassCorners.HOTBAR_RADIUS / Math.max(1.0F, Math.min(px1 - px0, py1 - py0) / 2.0F));
      GlassWidgets.capsule(g, px0, py0, px1, py1, c, 0.81F, 1.0F, true);
   }
}
