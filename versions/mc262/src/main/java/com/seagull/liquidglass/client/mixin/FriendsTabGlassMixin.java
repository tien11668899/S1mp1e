package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The friends overlay's tabs (好友 / 待處理) used vanilla's square tab sprites plus a 1 px underline under the selected
 * label. Same treatment as the menu tabs ({@code TabGlassMixin}): a glass capsule on the button program, selected
 * brightest, hovered lit, resting faint — and no underline (the bright capsule is the selection, like an Apple segmented
 * control).
 */
@Mixin(targets = "net.minecraft.client.gui.screens.friends.FriendsOverlayTabButton")
public abstract class FriendsTabGlassMixin {

   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V")
   )
   private void lg$glassTab(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.btnUsable())) {
         g.blitSprite(pipeline, sprite, x, y, w, h);
         return;
      }
      TabButton self = (TabButton) (Object) this;
      float lift = self.isSelected() ? 0.81F : (self.isHoveredOrFocused() ? 0.5F : 0.0F);
      int liftG = Math.round(255.0F * (1.0F - lift)) & 0xFF;
      int opacity = self.isSelected() ? 0xFF : (self.isHoveredOrFocused() ? 0xB3 : 0x66);
      int col = (self.active ? 255 : 102) << 24 | 0xFF0000 | liftG << 8 | opacity;
      ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState()
         .addGuiElement(new GlassRectRenderState(GlassPipeline.btn(), TextureSetup.noTexture(), g.pose(),
            x + 2, y + 2, x + w - 2, y + h - 2, 10, col, null));
   }

   @Inject(method = "renderFocusUnderline", at = @At("HEAD"), cancellable = true)
   private void lg$noUnderline(GuiGraphicsExtractor g, Font font, int color, CallbackInfo ci) {
      if (GlassPipeline.ensureReady() && GlassPipeline.btnUsable()) ci.cancel();
   }
}
