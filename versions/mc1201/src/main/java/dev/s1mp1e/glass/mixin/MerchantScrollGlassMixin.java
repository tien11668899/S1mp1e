package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villager / merchant trade list → the shared vertical glass slider (C) + silky sub-pixel trade glide (D).
 *
 * <p>Merchant draws its trades + scrollbar in {@code render} (not the swallowed {@code drawBackground}), so the trades
 * already show on the glass panel drawn by the shared container path.
 *
 * <p><b>Scrollbar (C).</b> The vanilla 6x27 thumb is replaced by the shared 15 px glass thumb (matching the creative /
 * stonecutter / loom bars — 26.2 found the 27 px sprite made the held lens look oversized), travel grown to 124 so the
 * thumb still spans the 139 px track; eases to the trade-row ratio, held glass lens with a 1:1 rubber-banded drag.
 *
 * <p><b>Silky content (D).</b> Vanilla remaps the 7 fixed trade rows by whole trades on scroll. Here the trade content
 * GLIDES sub-pixel with the SAME eased value the glass thumb uses. Vanilla keeps a row-aligned logical scroll
 * ({@code indexStartOffset}) so a trade click always selects the trade under the cursor; while the eased offset differs
 * from that row this mixin suppresses the vanilla loop's own trade icons / decorations / arrows / first-buy draws and,
 * right after the trade loop, redraws the visible trades itself at absolute rows translated by the fractional offset,
 * clipped to the trade window with one extra row so no edge gap shows. A mid-glide click snaps to the target row first.
 *
 * <p>The 1.20.1 trade helpers ({@code renderFirstBuyItem}, {@code renderArrow}, {@code drawItemWithoutEntity}) all draw
 * through {@code context.getMatrices()}, so — unlike the loom's own-matrix banner — the merchant glide is TRUE sub-pixel
 * via a context-matrix translate. The trade-button widgets are NOT glassed (ButtonGlassMixin deliberately skips
 * HandledScreen widgets) and are vanilla click regions, so there is no glass capsule to slide (26.2's
 * MerchantButtonGlideMixin has no counterpart here); only the item content glides, re-aligning at rest.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScrollGlassMixin {

    @Unique private static final int LG_VIS = 7, LG_ROW_Y0 = 19, LG_ROW_H = 20;
    @Unique private static final int LG_COSTA_X = 10, LG_COSTB_X = 40, LG_RESULT_X = 73;
    @Unique private static final int LG_WINDOW_Y = 16, LG_WINDOW_H = 139;

    @Shadow int indexStartOffset;
    @Shadow private boolean scrolling;
    @Shadow protected abstract void renderScrollbar(DrawContext context, int x, int y, TradeOfferList tradeOffers);
    @Shadow protected abstract void renderFirstBuyItem(DrawContext context, ItemStack adjusted, ItemStack original, int x, int y);
    @Shadow protected abstract void renderArrow(DrawContext context, TradeOffer tradeOffer, int leftPos, int y);

    @Unique private GlassScrollbar s1mp1e$scrollbar;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private int s1mp1e$px() { return ((HandledScreenAccessor) (Object) this).s1mp1e$x(); }
    @Unique private int s1mp1e$py() { return ((HandledScreenAccessor) (Object) this).s1mp1e$y(); }
    @Unique private TradeOfferList s1mp1e$offers() {
        return ((MerchantScreenHandler) ((HandledScreen<?>) (Object) this).getScreenHandler()).getRecipes();
    }

    /** Scroller sprite → glass slider + the per-frame glide-state computation (runs before the trade loop in render). */
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "renderScrollbar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/village/TradeOfferList;)V"))
    private void s1mp1e$scroller(MerchantScreen self, DrawContext ctx, int i, int j, TradeOfferList offers,
                                 DrawContext ctxEnc, int mouseX, int mouseY, float delta) {
        s1mp1e$sliding = false;
        int size = offers.size();
        if (!GlassProgram.ensureReady() || !GlassProgram.usable() || size <= LG_VIS) {
            this.renderScrollbar(ctx, i, j, offers);   // self == this; shadow
            return;
        }
        if (s1mp1e$scrollbar == null) s1mp1e$scrollbar = new GlassScrollbar();
        int maxRows = size - LG_VIS;
        float ratio = maxRows <= 0 ? 0f : MathHelper.clamp((float) indexStartOffset / maxRows, 0f, 1f);
        // merchant track: 15 px thumb, top at topPos+18, thumb-top travel 124 (139 area - 15 thumb).
        GlassScrollbar.run(s1mp1e$scrollbar, ctxEnc, i + 94 + 3f, j + 18f, 124f, 15f,
                ratio, true, scrolling, mouseY, 1.0f);
        if (maxRows > 0) {
            float easedTrades = s1mp1e$scrollbar.pos() * maxRows;
            if (Math.abs(easedTrades - indexStartOffset) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedTrades), 0, maxRows);
                s1mp1e$glideFracPx = (easedTrades - s1mp1e$glideBase) * LG_ROW_H;
            }
        }
    }

    // ---- suppress the vanilla trade content during a glide (redrawn by the overlay) ----

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "renderFirstBuyItem(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$firstBuy(MerchantScreen self, DrawContext ctx, ItemStack adjusted, ItemStack original, int x, int y) {
        if (!s1mp1e$sliding) this.renderFirstBuyItem(ctx, adjusted, original, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "renderArrow(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/village/TradeOffer;II)V"))
    private void s1mp1e$arrow(MerchantScreen self, DrawContext ctx, TradeOffer offer, int leftPos, int y) {
        if (!s1mp1e$sliding) this.renderArrow(ctx, offer, leftPos, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawItemWithoutEntity(Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$tradeItem(DrawContext ctx, ItemStack stack, int x, int y) {
        if (!s1mp1e$sliding) ctx.drawItemWithoutEntity(stack, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawItemInSlot(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$tradeDecor(DrawContext ctx, TextRenderer font, ItemStack stack, int x, int y) {
        if (!s1mp1e$sliding) ctx.drawItemInSlot(font, stack, x, y);
    }

    /** Redraw the visible trades at absolute rows, translated by the eased offset, clipped to the trade window. Injected
     *  right after the trade loop (the first read of {@code selectedIndex}), before drawLevelInfo / tooltips. */
    @Inject(method = "render",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;selectedIndex:I"))
    private void s1mp1e$overlay(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        TradeOfferList offers = s1mp1e$offers();
        int size = offers.size();
        int px = s1mp1e$px(), py = s1mp1e$py();
        TextRenderer font = MinecraftClient.getInstance().textRenderer;

        int x0 = px + 4, x1 = px + 4 + 92;
        int y0 = py + LG_WINDOW_Y, y1 = py + LG_WINDOW_Y + LG_WINDOW_H;
        ctx.enableScissor(x0, y0, x1, y1);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, -s1mp1e$glideFracPx, 100f);   // z=100 lifts items over the panel, y = glide
        try {
            for (int tr = 0; tr <= LG_VIS; tr++) {
                int idx = s1mp1e$glideBase + tr;
                if (idx < 0 || idx >= size) continue;
                TradeOffer offer = offers.get(idx);
                int y = py + LG_ROW_Y0 + tr * LG_ROW_H;
                this.renderFirstBuyItem(ctx, offer.getAdjustedFirstBuyItem(), offer.getOriginalFirstBuyItem(), px + LG_COSTA_X, y);
                ItemStack costB = offer.getSecondBuyItem();
                if (!costB.isEmpty()) {
                    ctx.drawItemWithoutEntity(costB, px + LG_COSTB_X, y);
                    ctx.drawItemInSlot(font, costB, px + LG_COSTB_X, y);
                }
                this.renderArrow(ctx, offer, px, y);
                ItemStack sell = offer.getSellItem();
                ctx.drawItemWithoutEntity(sell, px + LG_RESULT_X, y);
                ctx.drawItemInSlot(font, sell, px + LG_RESULT_X, y);
            }
        } finally {
            ctx.getMatrices().pop();
            ctx.disableScissor();
        }

        float topK = s1mp1e$glideBase > 0 || s1mp1e$glideFracPx > 0.5f ? 1f : 0f;
        float botK = (s1mp1e$glideBase + LG_VIS) < size ? 1f : 0f;
        GlassWidgets.scrollEdges(x0, y0, x1, y1, 6f, topK, botK, 1.0f);
    }

    /** A click mid-glide snaps to the target row first, so it always selects the trade drawn under the cursor. */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding && s1mp1e$scrollbar != null) {
            s1mp1e$scrollbar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }
}
