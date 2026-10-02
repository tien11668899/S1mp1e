package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Waiting screen. Vanilla: title at y 80, then (while waiting) "o O o" dots at y 120 and the wrapped message (≤ 360 wide)
 * at y 132 — a 31 px hole under the title but only 3 px above the message. Here the rows are stacked with the one
 * {@link LoadingCard#GAP}: title → GAP → indeterminate {@link LiquidLoader} (replacing the dots) → GAP → message
 * (title → GAP → message when not waiting), inside one glass status card with the uniform {@link LoadingCard} padding.
 */
@Mixin(net.minecraft.client.gui.screens.GenericWaitingScreen.class)
public abstract class GenericWaitingScreenGlassMixin {
   @Unique private static final float TITLE_Y = 80.0F;

   @Shadow private Component messageText;
   @Shadow @Final private boolean showLoadingDots;

   @Unique
   private float lg$loaderTop() {
      return TITLE_Y + LoadingCard.TEXT_H + LoadingCard.GAP;
   }

   @Unique
   private float lg$messageY() {
      return this.showLoadingDots ? lg$loaderTop() + LiquidLoader.ROW_H + LoadingCard.GAP : lg$loaderTop();
   }

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$statusCard(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      Font font = Minecraft.getInstance().font;
      if (font == null) {
         return;
      }
      Component title = ((Screen) (Object) this).getTitle();
      float maxW = title == null ? 0.0F : font.width(title);
      float bottom = TITLE_Y + LoadingCard.TEXT_H;
      if (this.showLoadingDots) {
         maxW = Math.max(maxW, LiquidLoader.TRACK_W);
         bottom = lg$loaderTop() + LiquidLoader.ROW_H;
      }
      if (this.messageText != null) {
         List<FormattedCharSequence> lines = font.split(this.messageText, 360);
         for (FormattedCharSequence line : lines) {
            maxW = Math.max(maxW, font.width(line));
         }
         if (!lines.isEmpty()) {
            bottom = lg$messageY() + lines.size() * LoadingCard.TEXT_H;
         }
      }
      if (maxW <= 0.0F) {
         return;
      }
      float cx = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2.0F;
      LoadingCard.box(g, cx - maxW / 2.0F, TITLE_Y, cx + maxW / 2.0F, bottom);
      if (this.showLoadingDots) {
         LiquidLoader.draw(g, this, cx, lg$loaderTop() + LiquidLoader.ROW_H / 2.0F, -1.0F);
      }
   }

   /** The "o O o" dots: replaced by the liquid loader drawn above. */
   @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
   private void lg$noDots(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
   }

   /** Message on the card's row grid (one GAP under the loader, or under the title when not waiting). */
   @ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/MultiLineLabel;visitLines(Lnet/minecraft/client/gui/TextAlignment;IIILnet/minecraft/client/gui/ActiveTextCollector;)I"),
         index = 2)
   private int lg$messageYArg(int y) {
      return (int) lg$messageY();
   }
}
