package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import dev.s1mp1e.client.gui.MerchantGlide;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.util.Identifier;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Villager / merchant trade list → container glass (panel + lattice + hover) + the shared vertical glass slider (C) + the
 * silky sub-pixel trade glide (D). 1.21.1 (DrawContext) analogue of 26.2's {@code MerchantScrollGlassMixin} +
 * {@code MerchantGlide} + {@code MerchantButtonGlideMixin}; the item-glide mechanism is the one 1.20.1 proved.
 *
 * <h3>Seams (verified from the decompiled 1.21.1 {@code MerchantScreen})</h3>
 * {@code drawBackground} draws the body PNG ({@code drawTexture(Identifier,IIIFFIIII)} ordinal 0) — reached through the
 * generic container mixin's routing, like stonecutter/loom. {@code render} = {@code super.render} (panel, then the 7
 * {@code WidgetButtonPage} trade buttons 88×20 at {@code (x+5, y+18+20r)}, slots), then {@code renderScrollbar} (6×27
 * thumb sprite), then the trade loop: per visible trade {@code renderFirstBuyItem} (costA at x+10), costB
 * {@code drawItemWithoutEntity/drawItemInSlot} at x+40, {@code renderArrow}, the result at x+73, all at row y
 * {@code y+19+20r} under {@code translate(0,0,100)}; then the first read of {@code selectedIndex} (level bar, tooltips).
 * {@code indexStartOffset} is the row-aligned top trade (clicks: {@code button.index + indexStartOffset}).
 *
 * <h3>Glide (D)</h3>
 * The glass thumb's motion is stepped at {@code render} HEAD (so the eased value is known before the buttons draw) and
 * painted in place of the vanilla thumb. While the eased trade offset differs from {@code indexStartOffset}: the loop's
 * trade icons / decorations / arrows / first-buy draws are suppressed and redrawn after the loop at absolute rows
 * {@code base..base+7} (one extra) translated by the fractional offset, scissored to the trade window; the 7 button
 * frames slide with them via {@link MerchantGlide} (+ the entering row's frame). All of these draw through the context
 * matrix, so the glide is TRUE sub-pixel. A mid-glide click snaps to the target first.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantGlassMixin implements GlideProbe, ScrollDragOwner {

    @Unique private static final int LG_VIS = 7, LG_ROW_Y0 = 19, LG_ROW_H = 20;
    @Unique private static final int LG_COSTA_X = 10, LG_COSTB_X = 40, LG_RESULT_X = 73;

    @Shadow int indexStartOffset;
    @Shadow private boolean scrolling;
    @Shadow private void renderFirstBuyItem(DrawContext context, ItemStack adjusted, ItemStack original, int x, int y) {
        throw new AssertionError();
    }
    @Shadow private void renderArrow(DrawContext context, TradeOffer tradeOffer, int x, int y) {
        throw new AssertionError();
    }

    @Unique private final ContainerGlass.State s1mp1e$glass = new ContainerGlass.State();
    @Unique private final GlassScrollbar s1mp1e$bar = new GlassScrollbar();
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    @Unique private boolean s1mp1e$barActive;
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }
    @Unique private TradeOfferList s1mp1e$offers() {
        return ((MerchantScreenHandler) ((HandledScreen<?>) (Object) this).getScreenHandler()).getRecipes();
    }

    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$captureMouse(DrawContext ctx, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
    }

    /** Step the glass thumb and decide the glide BEFORE anything draws (the trade buttons draw inside super.render). */
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$stepGlide(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        s1mp1e$sliding = false;
        s1mp1e$barActive = false;
        MerchantGlide.end();
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        TradeOfferList offers = s1mp1e$offers();
        int size = offers.size();
        if (size == 0) return;
        HandledScreenAccessor a = s1mp1e$acc();
        int px = a.s1mp1e$x(), py = a.s1mp1e$y();
        boolean active = size > LG_VIS;
        int maxRows = Math.max(0, size - LG_VIS);
        int row = MathHelper.clamp(indexStartOffset, 0, maxRows);
        float ratio = maxRows <= 0 ? 0f : (float) row / maxRows;
        // merchant track: 15 px glass thumb (the 26.2 sizing, not the oversized 27 px sprite), top y+18, travel 124
        GlassScrollbar.step(s1mp1e$bar, px + 94 + 3f, py + 18f, 124f, 15f, ratio, scrolling && active, mouseY);
        s1mp1e$barActive = active;
        if (!active) return;
        float eased = s1mp1e$bar.pos() * maxRows;
        if (Math.abs(eased - row) <= 0.02f) return;
        s1mp1e$sliding = true;
        s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(eased), 0, maxRows);
        s1mp1e$glideFracPx = (eased - s1mp1e$glideBase) * LG_ROW_H;

        // Trade-button frames glide with the items: the 7 WidgetButtonPage buttons (88x20 at x+5).
        List<ClickableWidget> btns = new ArrayList<ClickableWidget>();
        ClickableWidget bottom = null;
        for (Element e : ((Screen) (Object) this).children()) {
            if (e instanceof ButtonWidget b && b.getWidth() == 88 && b.getHeight() == 20 && b.getX() == px + 5) {
                btns.add(b);
                if (bottom == null || b.getY() > bottom.getY()) bottom = b;
            }
        }
        boolean extra = s1mp1e$glideBase + LG_VIS < size;
        MerchantGlide.begin(btns, bottom, s1mp1e$glideFracPx,
                px + 4, py + 18, px + 94, py + 18 + LG_VIS * LG_ROW_H, extra);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$endGlide(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MerchantGlide.end();
    }

    // ---- A/B: body PNG → container glass ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIFFIIII)V"))
    private void s1mp1e$glassBody(DrawContext ctx, Identifier tex, int x, int y, int z,
                                  float u, float v, int w, int h, int tw, int th) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            ctx.drawTexture(tex, x, y, z, u, v, w, h, tw, th);
            return;
        }
        HandledScreenAccessor a = s1mp1e$acc();
        ContainerGlass.draw(s1mp1e$glass, ctx, a.s1mp1e$x(), a.s1mp1e$y(),
                a.s1mp1e$backgroundWidth(), a.s1mp1e$backgroundHeight(),
                a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(), a.s1mp1e$cursorDragging(),
                s1mp1e$mouseX, s1mp1e$mouseY);
    }

    // ---- C: scroller thumb (6×27 sprite) → the glass slider, painted at the motion stepped at HEAD ----
    @Redirect(method = "renderScrollbar",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIIII)V"))
    private void s1mp1e$glassScroller(DrawContext ctx, Identifier tex, int bx, int by, int bz, int bw, int bh) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            ctx.drawGuiTexture(tex, bx, by, bz, bw, bh);
            return;
        }
        ctx.draw();
        s1mp1e$bar.paint(ctx, s1mp1e$barActive, 1f);
    }

    // ---- D: suppress the vanilla trade content during a glide (redrawn by the overlay) ----

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
    private void s1mp1e$arrow(MerchantScreen self, DrawContext ctx, TradeOffer offer, int x, int y) {
        if (!s1mp1e$sliding) this.renderArrow(ctx, offer, x, y);
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

    /** Redraw the visible trades at absolute rows translated by the eased offset, clipped to the trade window —
     *  right after the trade loop (the first read of {@code selectedIndex}), before the level bar / tooltips. */
    @Inject(method = "render",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;selectedIndex:I"))
    private void s1mp1e$overlay(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        TradeOfferList offers = s1mp1e$offers();
        int size = offers.size();
        HandledScreenAccessor a = s1mp1e$acc();
        int px = a.s1mp1e$x(), py = a.s1mp1e$y();
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        int x0 = px + 4, x1 = px + 94;
        int y0 = py + 18, y1 = py + 18 + LG_VIS * LG_ROW_H;
        ctx.enableScissor(x0, y0, x1, y1);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, -s1mp1e$glideFracPx, 100f);   // z=100 as vanilla's trade loop; y = glide
        try {
            for (int tr = 0; tr <= LG_VIS; tr++) {                     // 7 visible + ONE extra row
                int idx = s1mp1e$glideBase + tr;
                if (idx < 0 || idx >= size) continue;
                TradeOffer offer = offers.get(idx);
                int y = py + LG_ROW_Y0 + tr * LG_ROW_H;
                this.renderFirstBuyItem(ctx, offer.getDisplayedFirstBuyItem(), offer.getOriginalFirstBuyItem(),
                        px + LG_COSTA_X, y);
                ItemStack costB = offer.getDisplayedSecondBuyItem();
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
        // iOS-26 scroll-edge whisper (fresh composite grab: blurs the trades it sits on, never itself — R4)
        if (GlassProgram.edgeUsable()) {
            ctx.draw();
            SceneCapture.grabNow();
            float topK = s1mp1e$glideBase > 0 || s1mp1e$glideFracPx > 0.5f ? 1f : 0f;
            float botK = (s1mp1e$glideBase + LG_VIS) < size ? 1f : 0f;
            GlassWidgets.scrollEdges(x0, y0, x1, y1, 6f, topK, botK, 1f);
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
    public boolean s1mp1e$probeGliding() { return s1mp1e$sliding; }

    @Override
    public void s1mp1e$endScrollDrag() { scrolling = false; }

    @Override
    public float s1mp1e$probeOffsetPx() {
        return s1mp1e$bar.pos() * Math.max(0, s1mp1e$offers().size() - LG_VIS) * LG_ROW_H;
    }
}
