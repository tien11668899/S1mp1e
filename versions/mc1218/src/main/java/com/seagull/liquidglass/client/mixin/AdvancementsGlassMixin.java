package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import com.seagull.liquidglass.client.render.ScreenTransition;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import java.util.Map;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.resources.ResourceLocation;
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
 * <p>Order matters here (26.2 GUI z is pure insertion order). {@code render} draws the tree interior first
 * ({@code renderInside}, a dark {@code fill} + the scissored nodes) and the frame after ({@code renderWindow}). So:
 * <ol>
 *   <li>{@link #lg$advPanel} injects the glass panel at {@code render} HEAD — the earliest stratum, behind
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

   /** The window texture region drawn by {@code renderWindow} (literal 252 x 140 in the vanilla blit). */
   private static final int WINDOW_W = 252;
   private static final int WINDOW_H = 140;

   // 1.21.6-1.21.8 centres the window inline in render() ((width - 252) / 2, (height - 140) / 2); 26.x keeps leftPos/topPos
   @org.spongepowered.asm.mixin.Unique private int lg$left() { return (((net.minecraft.client.gui.screens.Screen) (Object) this).width - WINDOW_W) / 2; }
   @org.spongepowered.asm.mixin.Unique private int lg$top() { return (((net.minecraft.client.gui.screens.Screen) (Object) this).height - WINDOW_H) / 2; }
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

   /** Tab-band extension of the glass sheet per side (GUI px), measured in {@link #lg$measureTabs} one frame earlier. */
   @org.spongepowered.asm.mixin.Unique private int lg$padUp, lg$padDown, lg$padLeft, lg$padRight;

   /**
    * Measure which sides carry a tab row, from the real tab set. Done at {@code renderWindow} (the tree is being drawn,
    * so {@code tabs} is populated — at {@code render} HEAD it is not yet), and stored for the next frame's
    * {@link #lg$advPanel}. The one-frame lag is invisible (tab layout only changes on open / resize).
    */
   @Inject(method = "renderWindow", at = @At("HEAD"))
   private void lg$measureTabs(GuiGraphics g, int mouseX, int mouseY, CallbackInfo ci) {
      int up = 0, down = 0, left = 0, right = 0;
      // Vanilla draws the tab row only when there is more than one tab; match that so a single-tab window gets no empty band.
      if (this.tabs != null && this.tabs.size() > 1) {
         for (AdvancementTab t : this.tabs.values()) {
            // by name: 1.21.6-1.21.8's AdvancementTabType is package-private, so an enum switch can't be compiled here
            switch (String.valueOf(t.getType())) {
               case "ABOVE" -> up = 28;
               case "BELOW" -> down = 28;
               case "LEFT" -> left = 28;
               case "RIGHT" -> right = 28;
               default -> { }
            }
         }
      }
      this.lg$padUp = up;
      this.lg$padDown = down;
      this.lg$padLeft = left;
      this.lg$padRight = right;
   }

   @Inject(method = "render", at = @At("HEAD"))
   private void lg$advPanel(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      int opacity = Math.round(0xFF * ScreenOpenFade.value(Minecraft.getInstance().screen)) & 0xFF;
      // Creative-inventory style: each tab row is a band of the SAME glass sheet (no separate tab tiles) — extend the plate
      // by one tab's depth (28) on every side that carries tabs (measured last frame in lg$measureTabs).
      int x0 = lg$left() - lg$padLeft, y0 = lg$top() - lg$padUp;
      int x1 = lg$left() + WINDOW_W + lg$padRight, y1 = lg$top() + WINDOW_H + lg$padDown;
      GlassSurface.plateOrPaint(g, x0, y0, x1, y1, opacity, 0x99101014);
   }

   @Redirect(
      method = "renderWindow",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/ResourceLocation;IIFFIIII)V"
      )
   )
   private void lg$advFrame(GuiGraphics g, RenderPipeline pipeline, ResourceLocation tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
      // Drop the wooden frame when glass is up (the injected panel shows through at the border); else keep vanilla.
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) {
         g.blit(pipeline, tex, x, y, u, v, w, h, tw, th);
      }
   }
}
