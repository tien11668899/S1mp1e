package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.BrandIntro;
import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World-loading screen (the one seen on every world load). Vanilla: "loading terrain" text, a 200×2 progress bar 3 px
 * under it, and — in singleplayer — the coloured chunk grid centred on screen, the text 27 px above the grid. Here the
 * text and a {@link LiquidLoader} (determinate on {@code smoothedProgress} when the tracker has progress, else
 * indeterminate) sit in one glass status card; with the grid the text is lifted so the card's bottom edge is exactly
 * one {@link LoadingCard#GAP} above the grid. The vanilla bar is dropped.
 */
@Mixin(net.minecraft.client.gui.screens.LevelLoadingScreen.class)
public abstract class LevelLoadingScreenGlassMixin {
   @Shadow private LevelLoadTracker loadTracker;
   @Shadow private float smoothedProgress;
   @Shadow @Final private static Component DOWNLOADING_TERRAIN_TEXT;

   @Unique private int lg$textY = Integer.MIN_VALUE;
   @Unique private boolean lg$drew;
   @Unique private long lg$introStart;

   @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
   private void lg$statusCard(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      this.lg$drew = false;
      this.lg$textY = Integer.MIN_VALUE;
      // World entry: the seamless brand loop ("S1mp1e" melts into the mark and back, forever) on pure black, in place
      // of the status card + chunk grid. It runs for exactly as long as the world takes; the screen closes itself once
      // the world is ready.
      if (BrandIntro.ready(BrandIntro.MODE_LOOP)) {
         long now = Util.getNanos();   // ns clock, sampled once per frame: ms steps judder at high refresh rates
         if (this.lg$introStart == 0L) {
            this.lg$introStart = now;
         }
         int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
         int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
         g.fill(0, 0, w, h, 0xFF000000);
         BrandIntro.draw(g, (float) ((now - this.lg$introStart) / 1.0E9), BrandIntro.MODE_LOOP, 1.0F);
         this.lg$drew = true;   // keeps the vanilla progress bar cancelled
         ci.cancel();
         return;
      }
      Font font = Minecraft.getInstance().font;
      LevelLoadTracker tracker = this.loadTracker;
      if (font == null || tracker == null) {
         return;
      }
      int cx = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2;
      int cy = Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2;
      ChunkLoadStatusView view = tracker.statusView();
      float textY;
      if (view != null) {
         // card bottom = text + GAP + loader + PAD_Y; keep it one GAP above the grid's top edge
         float gridTop = cy - view.radius() * 2;
         textY = gridTop - LoadingCard.GAP - LoadingCard.PAD_Y - LiquidLoader.ROW_H - LoadingCard.GAP - LoadingCard.TEXT_H;
      } else {
         textY = cy - 50;
      }
      this.lg$textY = Math.round(textY);
      float loaderTop = this.lg$textY + LoadingCard.TEXT_H + LoadingCard.GAP;
      float w = Math.max(font.width(DOWNLOADING_TERRAIN_TEXT), LiquidLoader.TRACK_W);
      LoadingCard.box(g, cx - w / 2.0F, this.lg$textY, cx + w / 2.0F, loaderTop + LiquidLoader.ROW_H);
      LiquidLoader.draw(g, this, cx, loaderTop + LiquidLoader.ROW_H / 2.0F,
            tracker.hasProgress() ? this.smoothedProgress : -1.0F);
      this.lg$drew = true;
   }

   /** The status text, moved to the card layout's y. */
   @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
   private void lg$text(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
      g.centeredText(font, text, x, this.lg$textY != Integer.MIN_VALUE ? this.lg$textY : y, color);
   }

   /** The vanilla 200×2 bar: replaced by the liquid loader. */
   @Inject(method = "drawProgressBar", at = @At("HEAD"), cancellable = true)
   private void lg$noVanillaBar(GuiGraphicsExtractor g, int x, int y, int w, int h, float progress, CallbackInfo ci) {
      if (this.lg$drew) {
         ci.cancel();
      }
   }
}
