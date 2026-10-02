package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.MerchantGlide;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.widget.AbstractButtonWidget;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.container.MerchantContainer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TraderOfferList;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Villager / merchant trade list -> the shared vertical glass slider (C) + the silky sub-pixel trade glide (D). 1.14.4
 * ({@link MatrixStack}, legacy fixed-function GL) analogue of 26.2's {@code MerchantScrollGlassMixin} +
 * {@code MerchantGlide} + {@code MerchantButtonGlideMixin}.
 *
 * <p>Unlike the stonecutter / loom, the 1.14.4 merchant draws its trade list AND scrollbar in {@code render()} (not
 * {@code drawBackground}), so the generic swallow never erased it — the merchant panel (glass + lattice + hover) comes
 * from that swallow and the trades draw on top.
 *
 * <h3>Seams (1.14.4 bytecode)</h3>
 * {@code render}: {@code renderBackground}, {@code super.render} (panel, the 7 {@code WidgetButtonPage} trade buttons
 * 89x20 at {@code (x+5, y+18+20r)}), then the trade block: {@code method_20221(x, y, offers)} (6x27 thumb),
 * then per visible trade {@code method_20222} (costA at x+10), costB {@code ItemRenderer.renderInGui} /
 * {@code renderGuiItemOverlay} at x+40, {@code method_20223}, the result at x+73, all at row {@code y = y+19+20r} with
 * {@code itemRenderer.zOffset = 100}; then the first {@code GETFIELD selectedIndex} (level bar, tooltips).
 * {@code field_19163} is the row-aligned top trade.
 *
 * <h3>Glide (D)</h3>
 * The glass thumb's motion is stepped at {@code render} HEAD (so the eased value is known before the trade buttons draw
 * in {@code super.render}) and painted in place of the vanilla thumb. While the eased trade offset differs from the
 * logical row: the loop's first-buy / cost-B / arrow / result draws are suppressed and redrawn after the loop at
 * absolute rows {@code base..base+7} (ONE extra) under a GlStateManager global-matrix translate of the fractional offset —
 * items, count text, the discount strike and the arrow sprite all compose that global matrix, so everything slides by
 * exactly the same sub-pixel amount — scissored to the trade window; the 7 trade-button frames slide with them via
 * {@link MerchantGlide} + {@code MerchantTradeButtonMixin}. The trade hover tooltip is suppressed mid-glide. A mid-glide
 * click snaps to the target first.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScrollGlassMixin implements ScrollDragOwner {

    @Unique private static final int LG_VIS = 7, LG_ROW_Y0 = 19, LG_ROW_H = 20;
    @Unique private static final int LG_COSTA_X = 10, LG_COSTB_X = 40, LG_RESULT_X = 73;

    @Shadow private int field_19163;
    @Shadow private boolean field_19164;
    @Shadow private void method_20222(ItemStack adjusted, ItemStack original, int x, int y) { throw new AssertionError(); }
    @Shadow private void method_20223(TradeOffer tradeOffer, int x, int y) { throw new AssertionError(); }
    @Shadow private void method_20221(int x, int y, TraderOfferList tradeOffers) { throw new AssertionError(); }

    @Unique private final GlassScrollbar s1mp1e$bar = new GlassScrollbar();
    @Unique private int s1mp1e$mouseY;
    @Unique private boolean s1mp1e$barActive;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }
    @Unique private TraderOfferList s1mp1e$offers() {
        return ((MerchantContainer) ((ContainerScreen<?>) (Object) this).getContainer()).getRecipes();
    }

    /** Step the glass thumb and decide the glide BEFORE anything draws (the trade buttons draw inside super.render). */
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$stepGlide(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        s1mp1e$mouseY = mouseY;
        s1mp1e$sliding = false;
        s1mp1e$barActive = false;
        MerchantGlide.end();
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        TraderOfferList offers = s1mp1e$offers();
        int size = offers.size();
        if (size == 0) return;
        HandledScreenAccessor a = s1mp1e$acc();
        int px = a.s1mp1e$x(), py = a.s1mp1e$y();
        boolean active = size > LG_VIS;
        int maxRows = Math.max(0, size - LG_VIS);
        int row = MathHelper.clamp(field_19163, 0, maxRows);
        float ratio = maxRows <= 0 ? 0f : (float) row / maxRows;
        // merchant track: the shared 15 px glass thumb centred on the 6 px vanilla track at x+94 (top y+24, travel 112).
        GlassScrollbar.step(s1mp1e$bar, px + 94 + 3f, py + 24f, 112f, 15f, ratio, field_19164 && active, mouseY);
        s1mp1e$barActive = active;
        if (!active) return;
        float eased = s1mp1e$bar.pos() * maxRows;
        if (Math.abs(eased - row) <= 0.02f) return;
        s1mp1e$sliding = true;
        s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(eased), 0, maxRows);
        s1mp1e$glideFracPx = (eased - s1mp1e$glideBase) * LG_ROW_H;

        // Arm the trade-button frame glide (the 7 WidgetButtonPage buttons 89x20 at x+5); the bottom one also paints
        // the ENTERING row's capsule (it has no widget of its own yet) when there is a trade below the window.
        List<AbstractButtonWidget> btns = new ArrayList<AbstractButtonWidget>();
        AbstractButtonWidget bottom = null;
        for (Element e : ((Screen) (Object) this).children()) {
            if (e instanceof AbstractButtonWidget) {
                AbstractButtonWidget w = (AbstractButtonWidget) e;
                if (w.getWidth() == 89 && ((ClickableWidgetAccessor) w).s1mp1e$getHeight() == 20 && w.x == px + 5) {
                    btns.add(w);
                    if (bottom == null || w.y > bottom.y) bottom = w;
                }
            }
        }
        boolean extra = s1mp1e$glideBase + LG_VIS < size;
        MerchantGlide.begin(btns, bottom, s1mp1e$glideFracPx, px + 4, py + 18, px + 94, py + 18 + LG_VIS * LG_ROW_H,
                extra);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$endGlide(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MerchantGlide.end();
    }

    /** C: the scroller (method_20221) -> the glass slider, painted at the motion stepped at render HEAD. */
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;method_20221(IILnet/minecraft/village/TraderOfferList;)V"))
    private void s1mp1e$glassScroller(MerchantScreen self, int i, int j, TraderOfferList offers) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            this.method_20221(i, j, offers);   // self == this; call the shadow
            return;
        }
        s1mp1e$bar.paint(s1mp1e$barActive, 1.0f);
    }

    // ---- D: suppress the vanilla trade content during a glide (redrawn by the overlay) ----

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;method_20222(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$firstBuy(MerchantScreen self, ItemStack adjusted, ItemStack original, int x, int y) {
        if (!s1mp1e$sliding) this.method_20222(adjusted, original, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;method_20223(Lnet/minecraft/village/TradeOffer;II)V"))
    private void s1mp1e$arrow(MerchantScreen self, TradeOffer offer, int x, int y) {
        if (!s1mp1e$sliding) this.method_20223(offer, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/item/ItemRenderer;renderGuiItem(Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$tradeItem(ItemRenderer ir, ItemStack stack, int x, int y) {
        if (!s1mp1e$sliding) ir.renderGuiItem(stack, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/item/ItemRenderer;renderGuiItemOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$tradeDecor(ItemRenderer ir, TextRenderer font, ItemStack stack, int x, int y) {
        if (!s1mp1e$sliding) ir.renderGuiItemOverlay(font, stack, x, y);
    }

    /** Redraw the visible trades at absolute rows translated by the eased offset, clipped to the trade window — right
     *  after the trade loop (the first read of {@code selectedIndex}), before the level bar / tooltips. */
    @Inject(method = "render",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;field_19161:I"))
    private void s1mp1e$overlay(int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        TraderOfferList offers = s1mp1e$offers();
        int size = offers.size();
        HandledScreenAccessor a = s1mp1e$acc();
        int px = a.s1mp1e$x(), py = a.s1mp1e$y();
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemRenderer ir = mc.getItemRenderer();
        TextRenderer font = mc.textRenderer;

        int x0 = px + 4, x1 = px + 94;
        int y0 = py + 18, y1 = py + 18 + LG_VIS * LG_ROW_H;
        float savedZ = ir.zOffset;
        ir.zOffset = 100f;                                    // the vanilla trade-loop item depth
        GlassWidgets.beginScissor(x0, y0, x1, y1);            // render() is at pose identity here -> absolute
        GlStateManager.pushMatrix();
        GlStateManager.translatef(0f, -s1mp1e$glideFracPx, 0f);
        try {
            for (int tr = 0; tr <= LG_VIS; tr++) {            // 7 visible + ONE extra row
                int idx = s1mp1e$glideBase + tr;
                if (idx < 0 || idx >= size) continue;
                TradeOffer offer = offers.get(idx);
                int y = py + LG_ROW_Y0 + tr * LG_ROW_H;       // absolute; the global matrix supplies -fracPx
                this.method_20222(offer.getAdjustedFirstBuyItem(), offer.getOriginalFirstBuyItem(),
                        px + LG_COSTA_X, y);
                ItemStack costB = offer.getSecondBuyItem();
                if (!costB.isEmpty()) {
                    ir.renderGuiItem(costB, px + LG_COSTB_X, y);
                    ir.renderGuiItemOverlay(font, costB, px + LG_COSTB_X, y);
                }
                this.method_20223(offer, px, y);
                ItemStack sell = offer.getSellItem();
                ir.renderGuiItem(sell, px + LG_RESULT_X, y);
                ir.renderGuiItemOverlay(font, sell, px + LG_RESULT_X, y);
            }
        } finally {
            GlStateManager.popMatrix();
            GlassWidgets.endScissor();
            ir.zOffset = savedZ;
        }
        // iOS-26 scroll-edge whisper (fresh composite grab: blurs the trades it sits on, never itself — R4)
        if (GlassProgram.edgeUsable()) {
            SceneCapture.grabNow();
            float topK = s1mp1e$glideBase > 0 || s1mp1e$glideFracPx > 0.5f ? 1f : 0f;
            float botK = (s1mp1e$glideBase + LG_VIS) < size ? 1f : 0f;
            GlassWidgets.scrollEdges(x0, y0, x1, y1, 6f, topK, botK, 1.0f);
        }
    }

    /** A click mid-glide snaps to the target row first, so it always selects the trade drawn under the cursor. */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding) {
            s1mp1e$bar.snapToTarget();
            s1mp1e$sliding = false;
            MerchantGlide.end();
        }
    }

    @Override
    public void s1mp1e$endScrollDrag() { field_19164 = false; }
}
