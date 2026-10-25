package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.BrandIntro;
import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World-loading screen (the one seen on every world load). Vanilla 1.21.9+ (javap): {@code render} keeps the progress
 * narration going, then — in singleplayer — the coloured chunk grid centred on screen ({@code renderChunks}, top edge at
 * {@code cy - radius * 2}), the "loading terrain" text 27 px above it, and a 200×2 progress bar 3 px under the text when
 * the tracker has progress. Here the text and a {@link LiquidLoader} (determinate on {@code smoothedProgress} when the
 * tracker has progress, else indeterminate) sit in one glass status card whose bottom edge is exactly one
 * {@link LoadingCard#GAP} above the grid; the vanilla bar is dropped. When the brand loop is available it plays instead,
 * on pure black, for as long as the world takes ("S1mp1e" melts into the mark and back) — grid, text and bar skipped.
 *
 * <p>The hook sits at the {@code statusView()} call, after vanilla's narration, so the narrator keeps announcing the
 * progress (26.x renders through {@code extractRenderState}, outside the narration, and cancels at HEAD).
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenGlassMixin {
   @Shadow private LevelLoadTracker loadTracker;
   @Shadow private float smoothedProgress;
   @Shadow @Final private static Component DOWNLOADING_TERRAIN_TEXT;

   @Unique private static final int LG_VANILLA = 0, LG_CARD = 1, LG_LOOP = 2;
   @Unique private int lg$mode;
   @Unique private int lg$textY;
   @Unique private long lg$introStart;

   @Inject(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/multiplayer/LevelLoadTracker;statusView()Lnet/minecraft/server/level/progress/ChunkLoadStatusView;"))
   private void lg$statusCard(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      this.lg$mode = LG_VANILLA;
      int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
      int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
      // World entry: the seamless brand loop on pure black, in place of the status card + chunk grid. It runs for exactly
      // as long as the world takes; the screen closes itself once the world is ready.
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
      LevelLoadTracker tracker = this.loadTracker;
      if (font == null || tracker == null) {
         return;
      }
      int cx = w / 2, cy = h / 2;
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
      float cw = Math.max(font.width(DOWNLOADING_TERRAIN_TEXT), LiquidLoader.TRACK_W);
      LoadingCard.box(g, cx - cw / 2.0F, this.lg$textY, cx + cw / 2.0F, loaderTop + LiquidLoader.ROW_H);
      LiquidLoader.draw(g, this, cx, loaderTop + LiquidLoader.ROW_H / 2.0F,
            tracker.hasProgress() ? this.smoothedProgress : -1.0F);
      this.lg$mode = LG_CARD;
   }

   /** The chunk grid: hidden behind the brand loop. */
   @Redirect(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;renderChunks(Lnet/minecraft/client/gui/GuiGraphics;IIIILnet/minecraft/server/level/progress/ChunkLoadStatusView;)V"))
   private void lg$grid(GuiGraphics g, int x, int y, int size, int spacing, ChunkLoadStatusView view) {
      if (this.lg$mode != LG_LOOP) LevelLoadingScreen.renderChunks(g, x, y, size, spacing, view);
   }

   /** The status text, moved to the card layout's y (or hidden behind the brand loop). */
   @Redirect(method = "render", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
   private void lg$text(GuiGraphics g, Font font, Component text, int x, int y, int color) {
      if (this.lg$mode == LG_LOOP) return;
      g.drawCenteredString(font, text, x, this.lg$mode == LG_CARD ? this.lg$textY : y, color);
   }

   /** The vanilla 200×2 bar: replaced by the liquid loader (or hidden behind the brand loop). */
   @Inject(method = "drawProgressBar", at = @At("HEAD"), cancellable = true)
   private void lg$noVanillaBar(GuiGraphics g, int x, int y, int w, int h, float progress, CallbackInfo ci) {
      if (this.lg$mode != LG_VANILLA) {
         ci.cancel();
      }
   }
}
