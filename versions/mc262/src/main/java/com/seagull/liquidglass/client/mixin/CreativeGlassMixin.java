package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPanels;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import com.seagull.liquidglass.client.render.GlassTabs;
import com.seagull.liquidglass.client.render.ScreenTransition;
import dev.s1mp1e.client.gui.GlassGlideHost;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The creative inventory becomes liquid glass to match the survival inventory and container family:
 * <ul>
 *   <li><b>Item-panel body</b> — {@code extractBackground}'s {@code blit(...IIFFIIII)} of the tab body texture is redirected
 *       to {@link GlassPanels#panel} (the same refracting panel + slot separators + 150 ms open fade the other containers
 *       use), so the item grid sits on the identical frosted glass.</li>
 *   <li><b>Tabs</b> — square glass tiles fused to the panel (see {@link GlassTabs}).</li>
 *   <li><b>Scrollbar knob</b> — the {@code SCROLLER} sprite becomes the shared vertical glass slider.</li>
 *   <li><b>Silky content</b> — the 45-slot item grid now GLIDES sub-pixel with the eased scrollbar value exactly like the
 *       S1mp1e config menu, instead of stepping by whole rows. Vanilla keeps a row-aligned logical scroll (so clicks,
 *       hit-testing and tooltips are correct at rest); while the eased thumb offset differs from that row, the visible
 *       item stacks are drawn by this screen (from {@code ItemPickerMenu.items} + the eased row) translated by the
 *       fractional offset and clipped to the grid window with one extra row so no edge gap shows, vanilla's own slot
 *       drawing + slot highlight/tooltip for the grid are suppressed for the frame ({@link GlassGlideHost}), and a click
 *       mid-glide snaps to the target row first so it always acts on the item drawn under the cursor.</li>
 *   <li><b>Search field</b> — a thin light glass scrim behind the search {@link EditBox}.</li>
 * </ul>
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeGlassMixin implements GlassGlideHost {

   /** The vanilla static "which tab is open" field, so a tab tile knows if it is the selected (merged) one. */
   @Shadow
   private static CreativeModeTab selectedTab;

   /** Vanilla scroll state: {@code scrollOffs} 0..1, {@code scrolling} true while the thumb is dragged. */
   @Shadow private float scrollOffs;
   @Shadow private boolean scrolling;
   @Shadow private boolean canScroll() { return false; }

   /** The shared vertical glass scrollbar for this creative screen (the S1mp1e config slider, stood vertical). */
   @Unique private GlassScrollbar liquidglass$scrollbar;

   /** Sub-pixel glide state, recomputed once per frame in the scroller redirect. */
   @Unique private boolean liquidglass$sliding;
   @Unique private int     liquidglass$glideBase;    // top fully/partly visible item row
   @Unique private float   liquidglass$glideFracPx;  // 0..18 sub-pixel offset the grid is slid up by
   @Unique private int     liquidglass$glideRowCount;

   /** Item grid geometry (slot-relative): 9 cols x 5 rows of 18 px, origin (9,18) — see ItemPickerMenu ctor. */
   @Unique private static final int LG_GRID_X = 9;
   @Unique private static final int LG_GRID_Y = 18;
   @Unique private static final int LG_COLS = 9;
   @Unique private static final int LG_VIS_ROWS = 5;
   @Unique private static final int LG_PITCH = 18;

   /** Item-grid body → the shared glass container panel (with slot separators + open fade). */
   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"
      )
   )
   private void lg$creativeBody(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
      AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>)(Object)this;
      // ONE glass sheet: the body plus both tab rows (GlassTabs.BAND above and below) - tabs fused, no seam.
      if (!GlassPanels.panel(g, screen, x, y, w, h, GlassTabs.BAND, GlassTabs.BAND)) {
         GlassTabs.reset();
         g.blit(pipeline, tex, x, y, u, v, w, h, tw, th);
      }
   }

   /**
    * The scrollbar knob → the shared vertical glass slider, AND the per-frame glide-state computation. The bar eases
    * toward the ROW-SNAPPED scroll ratio (so it settles exactly on a vanilla row), becomes a held glass lens while
    * dragged, and rubber-bands past the ends. The eased offset drives {@link #liquidglass$drawGlideOverlay}.
    */
   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$creativeScroller(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
                                    GuiGraphicsExtractor gEnc, int mouseX, int mouseY, float delta) {
      liquidglass$sliding = false;
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         if (liquidglass$scrollbar == null) liquidglass$scrollbar = new GlassScrollbar();
         AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor)(Object)this;
         float top = acc.liquidglass$topPos();
         boolean active = canScroll();

         int rc = liquidglass$rowCount();
         int row = rc <= 0 ? 0 : Mth.clamp(Math.round(scrollOffs * rc), 0, rc);
         float targetRatio = rc <= 0 ? 0f : (float) row / rc;

         // creative track: thumb 12x15, top at topPos+18, thumb-top travel 97 (mouseDragged divides by 112-15)
         GlassScrollbar.run(liquidglass$scrollbar, g, x + w / 2f, top + 18f, 97f, 15f,
                 targetRatio, active, scrolling && active, mouseY, GlassPanels.fadeByte() / 255f);

         if (active && rc > 0) {
            float easedRows = liquidglass$scrollbar.pos() * rc;
            liquidglass$glideRowCount = rc;
            if (Math.abs(easedRows - row) > 0.02f) {
               liquidglass$sliding = true;
               liquidglass$glideBase = Mth.clamp((int) Math.floor(easedRows), 0, rc);
               liquidglass$glideFracPx = (easedRows - liquidglass$glideBase) * LG_PITCH;
            }
         }
      } else {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }

   /** A thin light glass backing behind the search field (its text extraction still runs on top). */
   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/EditBox;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"
      )
   )
   private void lg$creativeSearch(EditBox box, GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
      if (box.visible && box.getWidth() > 0 && GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         int x0 = box.getX() - 3;
         int y0 = box.getY() - 2;
         int x1 = box.getX() + box.getWidth() + 3;
         int y1 = box.getY() + box.getHeight() + 2;
         GlassSurface.scrim(g, x0, y0, x1, y1, 3.0F, 0x33FFFFFF);
      }
      box.extractRenderState(g, mouseX, mouseY, delta);
   }

   /** Every tab sprite → a SQUARE glass tile in the body-panel material, fused to the panel ({@link GlassTabs}). */
   @Redirect(
      method = {"extractTabButton"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$creativeTab(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
                               GuiGraphicsExtractor gEnc, int mouseX, int mouseY, CreativeModeTab tab) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         boolean selected = tab == selectedTab;
         AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor)(Object)this;
         int left = acc.liquidglass$leftPos();
         int panelTop = acc.liquidglass$topPos();
         int iw = acc.liquidglass$imageWidth();
         int ih = acc.liquidglass$imageHeight();
         boolean top = tab.row() == CreativeModeTab.Row.TOP;
         // hover = the pointer inside this tab's cell of the fused band
         float c = GlassTabs.cellW(iw);
         float cx0 = left + tab.column() * c;
         int by0 = top ? panelTop - GlassTabs.BAND : panelTop + ih;
         boolean hovered = !selected && mouseX >= cx0 && mouseX < cx0 + c && mouseY >= by0 && mouseY < by0 + GlassTabs.BAND;
         GlassTabs.deferTile(tab.column(), top, selected, hovered);
         if (selected) {
            GlassTabs.flush(g, this, left, panelTop, iw, ih, GlassPanels.fadeByte());
         }
      } else {
         if (tab == selectedTab) {
            GlassTabs.reset();
         }
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }

   /** The tab icon follows the same deferral so an unselected tab's icon stays on top of its own glass tile. */
   @Redirect(
      method = {"extractTabButton"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;item(Lnet/minecraft/world/item/ItemStack;II)V"
      )
   )
   private void lg$creativeTabIcon(GuiGraphicsExtractor g, ItemStack stack, int ix, int iy,
                                   GuiGraphicsExtractor gEnc, int mouseX, int mouseY, CreativeModeTab tab) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         boolean top = tab.row() == CreativeModeTab.Row.TOP;
         if (tab != selectedTab) {
            GlassTabs.deferIcon(stack, tab.column(), top);   // unselected tabs are extracted before the body: defer
         } else {
            // the selected tab is extracted after the body (and after GlassTabs.flush): draw at its centred spot now
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor)(Object)this;
            g.item(stack, GlassTabs.iconX(acc.liquidglass$leftPos(), tab.column(), acc.liquidglass$imageWidth()),
                   GlassTabs.iconY(acc.liquidglass$topPos(), acc.liquidglass$imageHeight(), top));
         }
      } else {
         g.item(stack, ix, iy);
      }
   }

   /** Tab hit boxes follow the fused band's equal cells (GlassTabs), so clicks / hover tooltips match what is drawn. */
   @Inject(method = "getTabX", at = @At("HEAD"), cancellable = true)
   private void lg$tabX(CreativeModeTab tab, CallbackInfoReturnable<Integer> cir) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         cir.setReturnValue(GlassTabs.tabX(tab.column(), ((AbstractContainerScreenAccessor)(Object)this).liquidglass$imageWidth()));
      }
   }

   /** A click while the grid is mid-glide snaps to the target row first, so it acts on the item drawn under the cursor. */
   @Inject(method = "mouseClicked", at = @At("HEAD"))
   private void lg$snapOnClick(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
      if (liquidglass$sliding && liquidglass$scrollbar != null) {
         liquidglass$scrollbar.snapToTarget();
         liquidglass$sliding = false;
      }
   }

   /**
    * Switching creative category tab swaps the whole item grid in one frame. Snapshot the outgoing frame and cross-dissolve
    * it over the new tab (the unchanged panel/tabs/hotbar overlap, so only the item grid visibly cross-fades). HEAD, before
    * the static {@code selectedTab} flips, so the snapshot holds the old tab. Skips a re-select of the same tab.
    */
   @Inject(method = "selectTab", at = @At("HEAD"))
   private void lg$dissolveTab(CreativeModeTab tab, CallbackInfo ci) {
      if (selectedTab != tab) {
         ScreenTransition.onTabSwitch();
      }
   }

   // ---- GlassGlideHost -----------------------------------------------------------------------------

   @Override
   public boolean liquidglass$gliding() { return liquidglass$sliding; }

   @Override
   public boolean liquidglass$isGlideSlot(Slot slot) {
      return slot.x >= LG_GRID_X && slot.x < LG_GRID_X + LG_COLS * LG_PITCH
          && slot.y >= LG_GRID_Y && slot.y < LG_GRID_Y + LG_VIS_ROWS * LG_PITCH;
   }

   /**
    * Draw the visible item stacks at their gliding positions. Called at the tail of {@code extractSlots}, i.e. inside
    * the {@code leftPos/topPos}-translated matrix, so slot-relative coordinates line up with the panel exactly like the
    * vanilla slots this replaces. Six rows are drawn (five visible + one) translated up by the fractional offset and
    * scissored to the grid window, so a row leaving the top is clipped and a row entering the bottom never shows a gap.
    */
   @Override
   public void liquidglass$drawGlideOverlay(GuiGraphicsExtractor g) {
      java.util.List<ItemStack> items = liquidglass$items();
      if (items == null) return;
      // This overlay runs inside the leftPos/topPos-translated matrix (extractSlots). enableScissor transforms its
      // rect by the current pose, so the scissor is given in the SAME slot-relative coordinates as the item draws.
      g.enableScissor(LG_GRID_X, LG_GRID_Y, LG_GRID_X + LG_COLS * LG_PITCH, LG_GRID_Y + LG_VIS_ROWS * LG_PITCH);
      g.pose().pushMatrix();
      g.pose().translate(0f, -liquidglass$glideFracPx);
      try {
         for (int vr = 0; vr <= LG_VIS_ROWS; vr++) {
            int row = liquidglass$glideBase + vr;
            int y = LG_GRID_Y + vr * LG_PITCH;
            for (int col = 0; col < LG_COLS; col++) {
               int idx = row * LG_COLS + col;
               if (idx < 0 || idx >= items.size()) continue;
               ItemStack st = items.get(idx);
               if (st.isEmpty()) continue;
               g.item(st, LG_GRID_X + col * LG_PITCH, y);
            }
         }
      } finally {
         g.pose().popMatrix();
         g.disableScissor();
      }

      // Config-menu scroll-edge whisper: dim the outer edge where content runs off, if there is content beyond.
      int totalRows = liquidglass$glideRowCount + LG_VIS_ROWS;
      float topK = liquidglass$glideBase > 0 || liquidglass$glideFracPx > 0.5f ? 1f : 0f;
      float botK = (liquidglass$glideBase + LG_VIS_ROWS) < totalRows ? 1f : 0f;
      GlassWidgets.scrollEdges(g, LG_GRID_X, LG_GRID_Y, LG_GRID_X + LG_COLS * LG_PITCH,
              LG_GRID_Y + LG_VIS_ROWS * LG_PITCH, 6f, topK, botK, GlassPanels.fadeByte() / 255f);
   }

   @Unique
   private int liquidglass$rowCount() {
      java.util.List<ItemStack> items = liquidglass$items();
      if (items == null) return 0;
      return Math.max(0, Mth.positiveCeilDiv(items.size(), LG_COLS) - LG_VIS_ROWS);
   }

   @Unique
   private java.util.List<ItemStack> liquidglass$items() {
      try {
         Object menu = ((AbstractContainerScreen<?>)(Object)this).getMenu();
         if (menu instanceof CreativeModeInventoryScreen.ItemPickerMenu picker) {
            return picker.items;
         }
      } catch (Throwable ignored) {}
      return null;
   }
}
