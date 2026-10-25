package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.BrandIntro;
import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Singleplayer world-loading screen. Vanilla 1.21.8 (javap): {@code render} keeps the progress narration going, then
 * draws the coloured chunk grid centred on screen ({@code renderChunks(g, listener, cx, cy, 2, 0)}, top edge at
 * {@code cy - diameter}) and the percentage text above it. Here the text and a {@link LiquidLoader} (determinate on the
 * listener's 0-100 progress) sit in one glass status card whose bottom edge is exactly one {@link LoadingCard#GAP} above
 * the grid. When the brand loop is available it plays instead, on pure black, for as long as the world takes ("S1mp1e"
 * melts into the mark and back) — grid and text are then skipped.
 *
 * <p>The hook sits right before {@code renderChunks}, after vanilla's narration, so the narrator keeps announcing the
 * progress. (26.x: {@code LevelLoadTracker} / {@code ChunkLoadStatusView}, a progress bar, and a HEAD cancel — its
 * narration lives outside {@code render}.)
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenGlassMixin {
   @Shadow @Final private StoringChunkProgressListener progressListener;

   @Shadow
   private Component getFormattedProgress() {
      throw new AssertionError();
   }

   @Unique private static final int LG_VANILLA = 0, LG_CARD = 1, LG_LOOP = 2;
   @Unique private int lg$mode;
   @Unique private int lg$textY;
   @Unique private long lg$introStart;

   @Inject(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;renderChunks(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/server/level/progress/StoringChunkProgressListener;IIII)V"))
   private void lg$statusCard(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      this.lg$mode = LG_VANILLA;
      int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
      int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
      if (BrandIntro.ready(BrandIntro.MODE_LOOP)) {
         long now = Util.getNanos();   // ns clock, sampled once per frame: ms steps judder at high refresh rates
         if (this.lg$introStart == 0L) {
            this.lg$introStart = now;
         }
         g.fill(0, 0, w, h, 0xFF000000);
         BrandIntro.draw(g, (float) ((now - this.lg$introStart) / 1.0E9), BrandIntro.MODE_LOOP, 1.0F);
         this.lg$mode = LG_LOOP;
         return;
      }
      Font font = Minecraft.getInstance().font;
      if (font == null || this.progressListener == null) {
         return;
      }
      int cx = w / 2, cy = h / 2;
      // card bottom = text + GAP + loader + PAD_Y; keep it one GAP above the grid's top edge
      float gridTop = cy - this.progressListener.getDiameter();
      float textY = gridTop - LoadingCard.GAP - LoadingCard.PAD_Y - LiquidLoader.ROW_H - LoadingCard.GAP - LoadingCard.TEXT_H;
      this.lg$textY = Math.round(textY);
      float loaderTop = this.lg$textY + LoadingCard.TEXT_H + LoadingCard.GAP;
      float cw = Math.max(font.width(this.getFormattedProgress()), LiquidLoader.TRACK_W);
      LoadingCard.box(g, cx - cw / 2.0F, this.lg$textY, cx + cw / 2.0F, loaderTop + LiquidLoader.ROW_H);
      LiquidLoader.draw(g, this, cx, loaderTop + LiquidLoader.ROW_H / 2.0F, this.progressListener.getProgress() / 100.0F);
      this.lg$mode = LG_CARD;
   }

   /** The chunk grid: hidden behind the brand loop. */
   @Redirect(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;renderChunks(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/server/level/progress/StoringChunkProgressListener;IIII)V"))
   private void lg$grid(GuiGraphics g, StoringChunkProgressListener listener, int x, int y, int size, int spacing) {
      if (this.lg$mode != LG_LOOP) LevelLoadingScreen.renderChunks(g, listener, x, y, size, spacing);
   }

   /** The percentage text, moved to the card layout's y (or hidden behind the brand loop). */
   @Redirect(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
   private void lg$text(GuiGraphics g, Font font, Component text, int x, int y, int color) {
      if (this.lg$mode == LG_LOOP) return;
      g.drawCenteredString(font, text, x, this.lg$mode == LG_CARD ? this.lg$textY : y, color);
   }
}
