package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Progress screen: a glass status card around the header (y 70), the stage/percent line (y 90 — vanilla draws it only
 * when there is a stage and the progress is non-zero) and a {@link LiquidLoader} one {@link LoadingCard#GAP} below the
 * text: determinate at the percent, indeterminate while it is still 0.
 */
@Mixin(net.minecraft.client.gui.screens.ProgressScreen.class)
public abstract class ProgressScreenGlassMixin {
   @Shadow private Component header;
   @Shadow private Component stage;
   @Shadow private int progress;

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$statusCard(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      Font font = Minecraft.getInstance().font;
      if (font == null) {
         return;
      }
      boolean stageLine = this.stage != null && this.progress != 0;
      int textW = this.header == null ? 0 : font.width(this.header);
      if (stageLine) {
         textW = Math.max(textW, font.width(Component.empty().append(this.stage).append(" " + this.progress + "%")));
      }
      float cx = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2.0F;
      float textBottom = stageLine ? 90.0F + LoadingCard.TEXT_H : 70.0F + LoadingCard.TEXT_H;
      float loaderTop = textBottom + LoadingCard.GAP;
      float w = Math.max(textW, LiquidLoader.TRACK_W);
      LoadingCard.box(g, cx - w / 2.0F, 70.0F, cx + w / 2.0F, loaderTop + LiquidLoader.ROW_H);
      LiquidLoader.draw(g, this, cx, loaderTop + LiquidLoader.ROW_H / 2.0F, stageLine ? this.progress / 100.0F : -1.0F);
   }
}
