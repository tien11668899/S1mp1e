package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPanels;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.level.block.entity.BannerPattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Loom pattern list → the config-menu silky scroll + the shared vertical glass slider ({@link GlassScrollbar}).
 *
 * <p><b>Scrollbar</b> — the vanilla 12x15 thumb is replaced by the config-slider glass thumb (eases to the row-snapped
 * scroll, held glass lens while {@code scrolling}, rubber-bands past the ends).
 *
 * <p><b>Silky content</b> — vanilla snaps the 4x4 banner-pattern grid by whole rows in an inline loop. Here the grid
 * GLIDES sub-pixel with the SAME eased value the glass thumb uses. Vanilla keeps a row-aligned logical scroll
 * ({@code startRow}) so pattern clicks stay correct; while the eased offset differs from that row this mixin suppresses
 * the vanilla loop's own cell drawing (the 14x14 sprite + the banner preview + the hover tooltip) and, at the tail of
 * {@code extractBackground}, redraws the visible patterns itself at their absolute rows translated by the fractional
 * offset, clipped to the 4-row window with one extra row so no edge gap shows. At rest ({@code frac == 0}) vanilla draws
 * normally. A click mid-glide snaps to the target row first so it always selects the pattern drawn under the cursor.
 */
@Mixin(LoomScreen.class)
public abstract class LoomScrollGlassMixin {

   @Shadow private float scrollOffs;
   @Shadow private boolean scrolling;
   @Shadow private int startRow;
   @Shadow private boolean isScrollBarActive() { return false; }
   @Shadow private int totalRowCount() { return 0; }

   @Invoker("extractBannerOnButton")
   abstract void liquidglass$banner(GuiGraphicsExtractor g, int x, int y, TextureAtlasSprite sprite);

   @Unique private GlassScrollbar liquidglass$bar;
   @Unique private boolean liquidglass$sliding;
   @Unique private int     liquidglass$base;      // top visible pattern row
   @Unique private float   liquidglass$fracPx;    // 0..14 sub-pixel offset the grid is slid up by

   /** Loom pattern grid geometry (absolute, added to leftPos/topPos): 4 cols x 4 rows of 14 px at (+60,+13). */
   @Unique private static final int LG_PX0 = 60, LG_PY0 = 13, LG_COLS = 4, LG_VIS = 4, LG_CELL = 14;
   @Unique private static final Identifier LG_PATTERN = Identifier.withDefaultNamespace("container/loom/pattern");
   @Unique private static final Identifier LG_PATTERN_SEL = Identifier.withDefaultNamespace("container/loom/pattern_selected");

   /** Scroller thumb → glass slider, and the per-frame glide-state computation. */
   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$loomScroller(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
                                GuiGraphicsExtractor gEnc, int mouseX, int mouseY, float delta) {
      // 14x14 pattern cells are suppressed during a glide (redrawn by lg$loomOverlay); leave everything else vanilla.
      if (w == 14 && h == 14) {
         if (!liquidglass$sliding) g.blitSprite(pipeline, sprite, x, y, w, h);
         return;
      }
      // The 12x15 thumb: the once-per-frame glide-state computation lives here (drawn before the pattern loop).
      if (w == 12 && h == 15) {
         liquidglass$sliding = false;
         boolean active = isScrollBarActive();
         if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
            if (liquidglass$bar == null) liquidglass$bar = new GlassScrollbar();
            float top = ((AbstractContainerScreenAccessor)(Object)this).liquidglass$topPos();
            int offscreen = Math.max(0, totalRowCount() - LG_VIS);
            float ratio = offscreen <= 0 ? 0f : Math.min(1f, startRow / (float) offscreen);
            GlassScrollbar.run(liquidglass$bar, g, x + w / 2f, top + 13f, 41f, 15f,
                    ratio, active, scrolling && active, mouseY, GlassPanels.fadeByte() / 255f);
            if (active && offscreen > 0) {
               float easedRows = liquidglass$bar.pos() * offscreen;
               if (Math.abs(easedRows - startRow) > 0.02f) {
                  liquidglass$sliding = true;
                  liquidglass$base = Mth.clamp((int) Math.floor(easedRows), 0, offscreen);
                  liquidglass$fracPx = (easedRows - liquidglass$base) * LG_CELL;
               }
            }
         } else {
            g.blitSprite(pipeline, sprite, x, y, w, h);
         }
         return;
      }
      // Any other sprite (16x16 slot markers, 26x26 error) draws vanilla and never touches the glide flag.
      g.blitSprite(pipeline, sprite, x, y, w, h);
   }

   /** Suppress the vanilla banner preview on each pattern cell during a glide (redrawn by the overlay). */
   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/inventory/LoomScreen;extractBannerOnButton(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"
      )
   )
   private void lg$loomBanner(LoomScreen self, GuiGraphicsExtractor g, int x, int y, TextureAtlasSprite sprite) {
      if (!liquidglass$sliding) liquidglass$banner(g, x, y, sprite);
   }

   /** Suppress the hover-pattern tooltip during a glide (it would name the row-aligned pattern, not the eased one). */
   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setTooltipForNextFrame(Lnet/minecraft/network/chat/Component;II)V"
      )
   )
   private void lg$loomTooltip(GuiGraphicsExtractor g, Component text, int mx, int my) {
      if (!liquidglass$sliding) g.setTooltipForNextFrame(text, mx, my);
   }

   /** Redraw the visible patterns at their absolute rows, translated by the eased offset, clipped to the 4-row window. */
   @Inject(method = "extractBackground", at = @At("TAIL"))
   private void lg$loomOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!liquidglass$sliding) return;
      AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor)(Object)this;
      int x0 = acc.liquidglass$leftPos() + LG_PX0;
      int y0 = acc.liquidglass$topPos() + LG_PY0;
      LoomMenu menu = (LoomMenu) ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)(Object)this).getMenu();
      List<Holder<BannerPattern>> patterns = menu.getSelectablePatterns();
      int selected = menu.getSelectedBannerPatternIndex();

      g.enableScissor(x0, y0, x0 + LG_COLS * LG_CELL, y0 + LG_VIS * LG_CELL);
      g.pose().pushMatrix();
      g.pose().translate(0f, -liquidglass$fracPx);
      try {
         for (int vr = 0; vr <= LG_VIS; vr++) {
            int row = liquidglass$base + vr;
            int py = y0 + vr * LG_CELL;
            for (int c = 0; c < LG_COLS; c++) {
               int idx = row * LG_COLS + c;
               if (idx < 0 || idx >= patterns.size()) continue;
               int px = x0 + c * LG_CELL;
               g.blitSprite(RenderPipelines.GUI_TEXTURED, idx == selected ? LG_PATTERN_SEL : LG_PATTERN, px, py, LG_CELL, LG_CELL);
               TextureAtlasSprite bs = g.getSprite(Sheets.getBannerSprite(patterns.get(idx)));
               liquidglass$banner(g, px, py, bs);
            }
         }
      } finally {
         g.pose().popMatrix();
         g.disableScissor();
      }

      int offscreen = Math.max(0, totalRowCount() - LG_VIS);
      float topK = liquidglass$base > 0 || liquidglass$fracPx > 0.5f ? 1f : 0f;
      float botK = (liquidglass$base + LG_VIS) < totalRowCount() ? 1f : 0f;
      GlassWidgets.scrollEdges(g, x0, y0, x0 + LG_COLS * LG_CELL, y0 + LG_VIS * LG_CELL, 5f, topK, botK,
              GlassPanels.fadeByte() / 255f);
      if (offscreen == 0) liquidglass$sliding = false;
   }

   /** A click mid-glide snaps to the target row first, so it always selects the pattern drawn under the cursor. */
   @Inject(method = "mouseClicked", at = @At("HEAD"))
   private void lg$snapOnClick(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
      if (liquidglass$sliding && liquidglass$bar != null) {
         liquidglass$bar.snapToTarget();
         liquidglass$sliding = false;
      }
   }
}
