package com.seagull.liquidglass.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPanels;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.MerchantGlide;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villager / merchant trade list → the config-menu silky scroll + the shared vertical glass slider
 * ({@link GlassScrollbar}).
 *
 * <p><b>Scrollbar</b> — the vanilla 6x27 thumb is replaced by the config-slider glass thumb (eases to the trade-row
 * ratio, held glass lens with a 1:1 rubber-banded drag while {@code isDragging}).
 *
 * <p><b>Silky content</b> — vanilla remaps the 7 fixed trade rows by whole trades on scroll. Here the trade content
 * GLIDES sub-pixel with the SAME eased value the glass thumb uses. Vanilla keeps a row-aligned logical scroll
 * ({@code scrollOff}) so a trade click always selects the trade under the cursor; while the eased offset differs from
 * that row this mixin suppresses the vanilla loop's own trade icons/arrows and, at the tail of {@code extractContents},
 * redraws the visible trades itself at absolute rows translated by the fractional offset, clipped to the trade window
 * with one extra row so no edge gap shows. The faint glass trade-button capsules are slid by the same offset (via
 * {@link MerchantGlide} + the button mixin). At rest vanilla draws normally. A click mid-glide snaps to the target row
 * first, so it always selects the trade drawn under the cursor.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScrollGlassMixin {

   @Shadow private int scrollOff;
   @Shadow private boolean isDragging;

   @Invoker("extractButtonArrows")
   abstract void liquidglass$arrows(GuiGraphicsExtractor g, MerchantOffer offer, int leftPos, int y);

   @Invoker("extractAndDecorateCostA")
   abstract void liquidglass$costA(GuiGraphicsExtractor g, ItemStack costA, ItemStack baseCostA, int x, int y);

   @Unique private GlassScrollbar liquidglass$bar;
   @Unique private boolean liquidglass$sliding;
   @Unique private int     liquidglass$base;
   @Unique private float   liquidglass$fracPx;

   /** Trade row geometry (relative to leftPos/topPos): 7 rows of 20 px; first row content at (+.., topPos+19). */
   @Unique private static final int LG_ROW_H = 20, LG_VIS = 7, LG_ROW_Y0 = 19, LG_WINDOW_Y = 16, LG_WINDOW_H = 139;
   @Unique private static final int LG_COSTA_X = 10, LG_COSTB_X = 40, LG_RESULT_X = 73;

   @Redirect(
      method = {"extractScroller"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$merchantScroller(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
                                    GuiGraphicsExtractor gEnc, int leftPos, int topPos, int mouseX, int mouseY, MerchantOffers offers) {
      liquidglass$sliding = false;
      int size = offers.size();
      int maxRows = Math.max(1, size - LG_VIS);
      boolean active = size > LG_VIS;
      if (w == 6 && h == 27 && GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         if (liquidglass$bar == null) liquidglass$bar = new GlassScrollbar();
         float ratio = Mth.clamp(scrollOff / (float) maxRows, 0f, 1f);
         // Same 15 px glass thumb as the creative / stonecutter / loom bars (vanilla's 27 px merchant sprite made the
         // capsule and its held lens look oversized - user: "村民那邊的滑動欄玻璃膠囊太大了"). Travel grows by the 12 px
         // saved so the thumb still spans the whole 139 px track (112 + 27 - 15 = 124).
         GlassScrollbar.run(liquidglass$bar, g, x + w / 2f, topPos + 18f, 124f, 15f,
                 ratio, active, isDragging && active, mouseY, GlassPanels.fadeByte() / 255f);
         if (active) {
            float easedTrades = liquidglass$bar.pos() * maxRows;
            if (Math.abs(easedTrades - scrollOff) > 0.02f) {
               liquidglass$sliding = true;
               liquidglass$base = Mth.clamp((int) Math.floor(easedTrades), 0, maxRows);
               liquidglass$fracPx = (easedTrades - liquidglass$base) * LG_ROW_H;
            }
         }
      } else {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
      MerchantGlide.set(liquidglass$sliding, liquidglass$fracPx);
   }

   // ---- suppress vanilla trade content during a glide (redrawn by the overlay) ----

   @Redirect(method = "extractContents",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fakeItem(Lnet/minecraft/world/item/ItemStack;II)V"))
   private void lg$merchantFakeItem(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
      if (!liquidglass$sliding) g.fakeItem(stack, x, y);
   }

   @Redirect(method = "extractContents",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V"))
   private void lg$merchantDecor(GuiGraphicsExtractor g, Font f, ItemStack stack, int x, int y) {
      if (!liquidglass$sliding) g.itemDecorations(f, stack, x, y);
   }

   @Redirect(method = "extractContents",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/MerchantScreen;extractAndDecorateCostA(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;II)V"))
   private void lg$merchantCostA(MerchantScreen self, GuiGraphicsExtractor g, ItemStack costA, ItemStack baseCostA, int x, int y) {
      if (!liquidglass$sliding) liquidglass$costA(g, costA, baseCostA, x, y);
   }

   @Redirect(method = "extractContents",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/MerchantScreen;extractButtonArrows(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/item/trading/MerchantOffer;II)V"))
   private void lg$merchantArrows(MerchantScreen self, GuiGraphicsExtractor g, MerchantOffer offer, int leftPos, int y) {
      if (!liquidglass$sliding) liquidglass$arrows(g, offer, leftPos, y);
   }

   /** Redraw the visible trades at absolute rows, translated by the eased offset, clipped to the trade window. */
   @Inject(method = "extractContents", at = @At("TAIL"))
   private void lg$merchantOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!liquidglass$sliding) return;
      AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor)(Object)this;
      int left = acc.liquidglass$leftPos();
      int top = acc.liquidglass$topPos();
      MerchantOffers offers = ((MerchantMenu) ((AbstractContainerScreen<?>)(Object)this).getMenu()).getOffers();
      int size = offers.size();

      int x0 = left + 4, x1 = left + 4 + 92;
      int y0 = top + LG_WINDOW_Y, y1 = top + LG_WINDOW_Y + LG_WINDOW_H;
      g.enableScissor(x0, y0, x1, y1);
      g.pose().pushMatrix();
      g.pose().translate(0f, -liquidglass$fracPx);
      try {
         for (int tr = 0; tr <= LG_VIS; tr++) {
            int idx = liquidglass$base + tr;
            if (idx < 0 || idx >= size) continue;
            MerchantOffer offer = offers.get(idx);
            int y = top + LG_ROW_Y0 + tr * LG_ROW_H;
            liquidglass$costA(g, offer.getCostA(), offer.getBaseCostA(), left + LG_COSTA_X, y);
            ItemStack costB = offer.getCostB();
            if (!costB.isEmpty()) {
               g.fakeItem(costB, left + LG_COSTB_X, y);
               g.itemDecorations(Minecraft.getInstance().font, costB, left + LG_COSTB_X, y);
            }
            liquidglass$arrows(g, offer, left, y);
            ItemStack result = offer.getResult();
            g.fakeItem(result, left + LG_RESULT_X, y);
            g.itemDecorations(Minecraft.getInstance().font, result, left + LG_RESULT_X, y);
         }
      } finally {
         g.pose().popMatrix();
         g.disableScissor();
      }

      float topK = liquidglass$base > 0 || liquidglass$fracPx > 0.5f ? 1f : 0f;
      float botK = (liquidglass$base + LG_VIS) < size ? 1f : 0f;
      GlassWidgets.scrollEdges(g, x0, y0, x1, y1, 6f, topK, botK, GlassPanels.fadeByte() / 255f);
   }

   /** A click mid-glide snaps to the target row first, so it always selects the trade drawn under the cursor. */
   @Inject(method = "mouseClicked", at = @At("HEAD"))
   private void lg$snapOnClick(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
      if (liquidglass$sliding && liquidglass$bar != null) {
         liquidglass$bar.snapToTarget();
         liquidglass$sliding = false;
         MerchantGlide.set(false, 0f);
      }
   }
}
