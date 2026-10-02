package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import com.seagull.liquidglass.client.render.ScreenTransition;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import java.util.Map;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The advancements window is framed in liquid glass: the ornate {@code window.png} frame is dropped and a single refracting
 * panel is laid behind the whole window, so the advancement tree sits on frosted glass with a clean glass border instead of
 * the wooden frame.
 *
 * <p>Order matters here (26.2 GUI z is pure insertion order). {@code extractRenderState} draws the tree interior first
 * ({@code extractInside}, a dark {@code fill} + the scissored nodes) and the frame after ({@code extractWindow}). So:
 * <ol>
 *   <li>{@link #lg$advPanel} injects the glass panel at {@code extractRenderState} HEAD — the earliest stratum, behind
 *       everything. It spans the full {@value #WINDOW_W}&times;{@value #WINDOW_H} window at {@code leftPos/topPos}.</li>
 *   <li>The tree's own dark interior {@code fill} then paints over the panel in the interior, keeping the coloured
 *       advancement lines/icons readable on a dark backing (LOOK SPEC: a scrim under content). The result is the tree
 *       framed by glass at the border and title bar.</li>
 *   <li>{@link #lg$advFrame} drops the {@code WINDOW_LOCATION} blit so the wooden frame no longer covers that glass.</li>
 * </ol>
 * The tabs and the advancement tooltip are left vanilla (readable as-is); only the window frame changes.
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsGlassMixin {

   /** The window texture region drawn by {@code extractWindow} (literal 252 x 140 in the vanilla blit). */
   private static final int WINDOW_W = 252;
   private static final int WINDOW_H = 140;

   @Shadow
   private int leftPos;
   @Shadow
   private int topPos;
   @Shadow
   private Map<AdvancementHolder, AdvancementTab> tabs;
   @Shadow
   private AdvancementTab selectedTab;

   /**
    * Switching advancement tab swaps the whole tree in one frame. Snapshot the outgoing frame and cross-dissolve it over
    * the new tree (the glass window frame and tab row overlap, so only the tree region visibly cross-fades). HEAD, before
    * {@code selectedTab} flips. Skips a no-op re-select of the current tab.
    */
   @Inject(method = "onSelectedTabChanged", at = @At("HEAD"))
   private void lg$dissolveAdvTab(AdvancementHolder holder, CallbackInfo ci) {
      AdvancementTab next = holder == null ? null : this.tabs.get(holder);
      if (next != null && next != this.selectedTab) {
         ScreenTransition.onTabSwitch();
      }
   }

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$advPanel(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      int opacity = Math.round(0xFF * ScreenOpenFade.value(Minecraft.getInstance().gui.screen())) & 0xFF;
      GlassSurface.plateOrPaint(g, this.leftPos, this.topPos, this.leftPos + WINDOW_W, this.topPos + WINDOW_H, opacity, 0x99101014);
   }

   @Redirect(
      method = "extractWindow",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"
      )
   )
   private void lg$advFrame(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
      // Drop the wooden frame when glass is up (the injected panel shows through at the border); else keep vanilla.
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) {
         g.blit(pipeline, tex, x, y, u, v, w, h, tw, th);
      }
   }
}
