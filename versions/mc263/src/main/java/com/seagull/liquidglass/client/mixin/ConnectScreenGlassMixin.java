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
 * Connecting screen: a glass status card around the status line (centred at height/2 − 50) and an indeterminate
 * {@link LiquidLoader} one {@link LoadingCard#GAP} below it. The cancel button (height/4 + 132) stays its own glass
 * button, well clear of the card.
 */
@Mixin(net.minecraft.client.gui.screens.ConnectScreen.class)
public abstract class ConnectScreenGlassMixin {
   @Shadow private volatile Component status;

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$statusCard(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      Font font = Minecraft.getInstance().font;
      Component s = this.status;
      if (font == null || s == null) {
         return;
      }
      float cx = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2.0F;
      float y = Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2 - 50;
      float loaderTop = y + LoadingCard.TEXT_H + LoadingCard.GAP;
      float w = Math.max(font.width(s), LiquidLoader.TRACK_W);
      LoadingCard.box(g, cx - w / 2.0F, y, cx + w / 2.0F, loaderTop + LiquidLoader.ROW_H);
      LiquidLoader.draw(g, this, cx, loaderTop + LiquidLoader.ROW_H / 2.0F, -1.0F);
   }
}
