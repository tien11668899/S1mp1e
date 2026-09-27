package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.client.gui.GuiScissor;
import dev.s1mp1e.client.gui.MerchantGlide;
import dev.s1mp1e.client.gui.ScrollDragOwner;
import dev.s1mp1e.glass.render.ContainerGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
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
 * silky sub-pixel trade glide (D). 1.17.1 ({@link MatrixStack}, core profile) analogue of 26.2's
 * {@code MerchantScrollGlassMixin} + {@code MerchantGlide} + {@code MerchantButtonGlideMixin}, structured like the
 * verified 1.21.1 sibling.
 *
 * <h3>Seams (javap-verified, 1.17.1 / yarn build.65)</h3>
 * {@code drawBackground}: the body blit is the STATIC {@code drawTexture(MatrixStack,IIIFFIIII)} ordinal 0 (256×512
 * atlas; owner {@code MerchantScreen}); ordinal 1 is the red "out of stock" marker of a disabled selected trade (kept
 * vanilla — information). The generic container mixin routes the merchant out of its drawBackground swallow so that
 * marker survives. {@code render} = {@code renderBackground}, {@code HandledScreen.render} (panel, the 7
 * {@code WidgetButtonPage} trade buttons 89×20 at {@code (x+5, y+18+20r)}, slots), then {@code renderScrollbar} (6×27
 * thumb), then the trade loop: per visible trade {@code renderFirstBuyItem} (costA at x+10), costB
 * {@code ItemRenderer.renderInGui}/{@code renderGuiItemOverlay} at x+40, {@code renderArrow}, the result at x+73, all at
 * row y {@code y+19+20r} with {@code itemRenderer.zOffset = 100}; then the first {@code GETFIELD selectedIndex} (level
 * bar, tooltips). {@code indexStartOffset} is the row-aligned top trade (clicks: {@code button.index + indexStartOffset}).
 *
 * <h3>Glide (D) — TRUE sub-pixel</h3>
 * The glass thumb's motion is stepped at {@code render} HEAD (so the eased value is known before the trade buttons draw)
 * and painted in place of the vanilla thumb. While the eased trade offset differs from {@code indexStartOffset}: the
 * loop's first-buy / cost-B / arrow / result draws are suppressed and redrawn after the loop at absolute rows
 * {@code base..base+7} (ONE extra) under a RenderSystem model-view translate of the fractional offset — items, count
 * text, the discount strike and the arrow sprite all compose that model-view, so everything slides by exactly the same
 * sub-pixel amount — scissored to the trade window; the 7 trade-button glass capsules slide with them via
 * {@link MerchantGlide} (+ the entering row's capsule). The trade hover tooltip is suppressed mid-glide by
 * {@code MerchantTradeButtonMixin} (vanilla maps the pointer to the row-aligned buttons). A mid-glide click snaps to the
 * target first.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScrollGlassMixin implements GlideProbe, ScrollDragOwner {

    @Unique private static final Identifier S1MP1E_TEX = new Identifier("textures/gui/container/villager2.png");
    @Unique private static final int LG_VIS = 7, LG_ROW_Y0 = 19, LG_ROW_H = 20;
    @Unique private static final int LG_COSTA_X = 10, LG_COSTB_X = 40, LG_RESULT_X = 73;

    @Shadow int indexStartOffset;
    @Shadow private boolean scrolling;
    @Shadow protected abstract void renderFirstBuyItem(MatrixStack matrices, ItemStack adjusted, ItemStack original, int x, int y);
    @Shadow protected abstract void renderArrow(MatrixStack matrices, TradeOffer tradeOffer, int x, int y);
    @Shadow protected abstract void renderScrollbar(MatrixStack matrices, int x, int y, TradeOfferList tradeOffers);

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
    private void s1mp1e$captureMouse(MatrixStack matrices, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
    }

    /** Step the glass thumb and decide the glide BEFORE anything draws (the trade buttons draw inside super.render). */
    @Inject(method = "render", at = @At("HEAD"))
    private void s1mp1e$stepGlide(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
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
        // merchant track: the shared 15 px glass thumb (26.2 sizing, not the oversized 27 px sprite) centred on the 6 px
        // vanilla track at x+94. Its CENTRE follows the vanilla 27 px thumb's centre exactly (y+31.5 .. y+143.5):
        // thumb-top y+24, travel 112 — vanilla's own drag divisor (139 - 27) with the same grab point, so a held glass
        // thumb maps the pointer 1:1 onto vanilla's indexStartOffset (no correction glide on release).
        GlassScrollbar.step(s1mp1e$bar, px + 94 + 3f, py + 24f, 112f, 15f, ratio, scrolling && active, mouseY);
        s1mp1e$barActive = active;
        if (!active) return;
        float eased = s1mp1e$bar.pos() * maxRows;
        if (Math.abs(eased - row) <= 0.02f) return;
        s1mp1e$sliding = true;
        s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(eased), 0, maxRows);
        s1mp1e$glideFracPx = (eased - s1mp1e$glideBase) * LG_ROW_H;

        // Trade-button frames glide with the items: the 7 WidgetButtonPage buttons (89x20 at x+5).
        List<ClickableWidget> btns = new ArrayList<ClickableWidget>();
        ClickableWidget bottom = null;
        for (Element e : ((Screen) (Object) this).children()) {
            if (e instanceof ButtonWidget) {
                ButtonWidget b = (ButtonWidget) e;
                if (b.getWidth() == 89 && b.getHeight() == 20 && b.x == px + 5) {
                    btns.add(b);
                    if (bottom == null || b.y > bottom.y) bottom = b;
                }
            }
        }
        boolean extra = s1mp1e$glideBase + LG_VIS < size;
        MerchantGlide.begin(btns, bottom, s1mp1e$glideFracPx, px + 4, py + 18, px + 94, py + 18 + LG_VIS * LG_ROW_H, extra);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void s1mp1e$endGlide(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MerchantGlide.end();
    }

    // ---- A: body PNG (static drawTexture ordinal 0) → container glass; the out-of-stock marker stays vanilla ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIFFIIII)V"))
    private void s1mp1e$glassBody(MatrixStack matrices, int x, int y, int z, float u, float v, int w, int h,
                                  int tw, int th) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            net.minecraft.client.gui.DrawableHelper.drawTexture(matrices, x, y, z, u, v, w, h, tw, th);
            return;
        }
        HandledScreenAccessor a = s1mp1e$acc();
        ContainerGlass.draw(s1mp1e$glass, a.s1mp1e$x(), a.s1mp1e$y(), a.s1mp1e$backgroundWidth(),
                a.s1mp1e$backgroundHeight(), a.s1mp1e$handler().slots, a.s1mp1e$cursorDragSlots(),
                a.s1mp1e$cursorDragging(), s1mp1e$mouseX, s1mp1e$mouseY);
        // the following out-of-stock marker blit relies on the position_tex shader vanilla set at drawBackground HEAD
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShaderTexture(0, S1MP1E_TEX);
    }

    // ---- C: the scroller thumb (6×27 sprite) → the glass slider, painted at the motion stepped at HEAD ----
    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "renderScrollbar(Lnet/minecraft/client/util/math/MatrixStack;IILnet/minecraft/village/TradeOfferList;)V"))
    private void s1mp1e$glassScroller(MerchantScreen self, MatrixStack matrices, int i, int j, TradeOfferList offers) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            this.renderScrollbar(matrices, i, j, offers);   // self == this; call the shadow
            return;
        }
        s1mp1e$bar.paint(matrices, s1mp1e$barActive, s1mp1e$glass.fade());
        // the trade loop's sprite draws (arrow, discount strike) rely on the position_tex shader + villager texture
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShaderTexture(0, S1MP1E_TEX);
    }

    // ---- D: suppress the vanilla trade content during a glide (redrawn by the overlay) ----

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "renderFirstBuyItem(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$firstBuy(MerchantScreen self, MatrixStack matrices, ItemStack adjusted, ItemStack original, int x, int y) {
        if (!s1mp1e$sliding) this.renderFirstBuyItem(matrices, adjusted, original, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;"
                            + "renderArrow(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/village/TradeOffer;II)V"))
    private void s1mp1e$arrow(MerchantScreen self, MatrixStack matrices, TradeOffer offer, int x, int y) {
        if (!s1mp1e$sliding) this.renderArrow(matrices, offer, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/item/ItemRenderer;renderInGui(Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$tradeItem(ItemRenderer ir, ItemStack stack, int x, int y) {
        if (!s1mp1e$sliding) ir.renderInGui(stack, x, y);
    }

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/item/ItemRenderer;"
                            + "renderGuiItemOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;II)V"))
    private void s1mp1e$tradeDecor(ItemRenderer ir, TextRenderer font, ItemStack stack, int x, int y) {
        if (!s1mp1e$sliding) ir.renderGuiItemOverlay(font, stack, x, y);
    }

    /** Redraw the visible trades at absolute rows translated by the eased offset, clipped to the trade window — right
     *  after the trade loop (the first read of {@code selectedIndex}), before the level bar / tooltips. */
    @Inject(method = "render",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/MerchantScreen;selectedIndex:I"))
    private void s1mp1e$overlay(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!s1mp1e$sliding) return;
        TradeOfferList offers = s1mp1e$offers();
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
        GuiScissor.enable(x0, y0, x1, y1);                    // render() is at pose identity here -> absolute
        MatrixStack mv = RenderSystem.getModelViewStack();
        mv.push();
        mv.translate(0.0, -s1mp1e$glideFracPx, 0.0);          // one fractional glide for items AND matrices sprites
        RenderSystem.applyModelViewMatrix();
        try {
            for (int tr = 0; tr <= LG_VIS; tr++) {             // 7 visible + ONE extra row
                int idx = s1mp1e$glideBase + tr;
                if (idx < 0 || idx >= size) continue;
                TradeOffer offer = offers.get(idx);
                int y = py + LG_ROW_Y0 + tr * LG_ROW_H;        // absolute; the model-view supplies -fracPx
                this.renderFirstBuyItem(matrices, offer.getAdjustedFirstBuyItem(), offer.getOriginalFirstBuyItem(),
                        px + LG_COSTA_X, y);
                ItemStack costB = offer.getSecondBuyItem();
                if (!costB.isEmpty()) {
                    ir.renderInGui(costB, px + LG_COSTB_X, y);
                    ir.renderGuiItemOverlay(font, costB, px + LG_COSTB_X, y);
                }
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                RenderSystem.setShaderTexture(0, S1MP1E_TEX);
                this.renderArrow(matrices, offer, px, y);
                ItemStack sell = offer.getSellItem();
                ir.renderInGui(sell, px + LG_RESULT_X, y);
                ir.renderGuiItemOverlay(font, sell, px + LG_RESULT_X, y);
            }
        } finally {
            mv.pop();
            RenderSystem.applyModelViewMatrix();
            GuiScissor.disable();
            ir.zOffset = savedZ;
        }
        // iOS-26 scroll-edge whisper (fresh composite grab: blurs the trades it sits on, never itself — R4)
        if (GlassProgram.edgeUsable()) {
            SceneCapture.grabNow();
            float topK = s1mp1e$glideBase > 0 || s1mp1e$glideFracPx > 0.5f ? 1f : 0f;
            float botK = (s1mp1e$glideBase + LG_VIS) < size ? 1f : 0f;
            GlassWidgets.scrollEdges(x0, y0, x1, y1, 6f, topK, botK, s1mp1e$glass.fade());
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
    public void s1mp1e$endScrollDrag() { scrolling = false; }

    @Override
    public boolean s1mp1e$probeGliding() { return s1mp1e$sliding; }

    @Override
    public float s1mp1e$probeOffsetPx() {
        return s1mp1e$bar.pos() * Math.max(0, s1mp1e$offers().size() - LG_VIS) * LG_ROW_H;
    }

    @Override
    public float s1mp1e$probeLift() { return s1mp1e$bar.liftValue(); }
}
