package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.GlassGlideHost;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.GlideProbe;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GlassTabs;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Creative inventory glass — body panel + fused category tabs (B) + vertical glass scrollbar (C) + silky sub-pixel grid
 * glide (D). 1.21.1 (DrawContext) port of the 26.2 creative glass ({@code CreativeGlassMixin} + {@code GlassTabs} +
 * {@code GlassScrollbar} + {@code GlassGlideHost}).
 *
 * <h3>Seams (verified from the decompiled 1.21.1 {@code CreativeInventoryScreen}, yarn 1.21.1+build.3)</h3>
 * {@code drawBackground(DrawContext, float, int, int)} draws, in order: the UNSELECTED tab icons ({@code renderTabIcon}
 * per group != selectedTab), the body PNG ({@code drawTexture(Identifier,IIIIII)} ordinal 0), the search box, the
 * scrollbar knob ({@code drawGuiTexture(Identifier,IIII)} ordinal 0 at {@code x+175, y+18+(int)(95*scrollPosition), 12,
 * 15} when {@code selectedTab.hasScrollbar()}), then the SELECTED tab icon. Tab hit tests ({@code isClickInTab},
 * {@code renderTabTooltipIfHovered}) all go through the private {@code getTabX(ItemGroup)} (relative to {@code x}).
 *
 * <ul>
 *   <li><b>B — fused tabs:</b> the body {@code drawTexture} redirect draws the body glass EXTENDED by
 *       {@link GlassTabs#BAND} above and below (one sheet, both tab rows fused, body corner radius unchanged). Each
 *       {@code renderTabIcon} is {@code @Inject}ed at HEAD and CANCELLED — it records the tab into {@link GlassTabs}
 *       (cell = column, row, selected, hovered) and defers its icon; when the SELECTED tab is reached (the last call,
 *       after the body) it {@link GlassTabs#flush}es the sliding/cross-fading pills + all icons on top of the body.
 *       {@code getTabX} returns the fused cell's hit box so clicks, the hover tooltip and the drawn pill agree.</li>
 *   <li><b>C — scrollbar:</b> the scrollbar {@code drawGuiTexture} redirect runs the shared {@link GlassScrollbar}
 *       (15 px lens thumb, 1:1 drag + rubber band, settle) — geometry trackTop {@code y+18}, travel {@code 97}
 *       (= 112−15), thumbLen 15 — pointed at the ROW-ALIGNED logical ratio (vanilla's own
 *       {@code (int)(scrollPosition*rows+0.5)} row), so at rest the thumb and the grid agree exactly.</li>
 *   <li><b>D — glide:</b> while the eased thumb ratio differs from the logical row the grid is mid-glide:
 *       {@code HandledScreenGlassMixin} suppresses the 45 grid slots + their hover, and {@link #s1mp1e$drawGlideOverlay}
 *       redraws 6 rows (one extra) from {@code itemList} translated by the fractional offset, scissored to the 5-row
 *       window. A click mid-glide snaps to the target row first.</li>
 * </ul>
 *
 * <p>Gates on the glass pipeline; a down pipeline falls back to the vanilla PNG / sprites so nothing vanishes.
 */
@Mixin(CreativeInventoryScreen.class)
public abstract class CreativeGlassMixin implements GlassGlideHost, GlideProbe {

    @Shadow private float scrollPosition;
    @Shadow private boolean scrolling;
    @Shadow private static ItemGroup selectedTab;
    @Shadow private boolean hasScrollbar() { return false; }

    // ---- item grid geometry (slot-relative): 9 cols x 5 rows of 18 px, origin (9,18) ----
    @Unique private static final int LG_GRID_X = 9;
    @Unique private static final int LG_GRID_Y = 18;
    @Unique private static final int LG_COLS = 9;
    @Unique private static final int LG_VIS_ROWS = 5;
    @Unique private static final int LG_PITCH = 18;

    @Unique private Fade s1mp1e$openFade;
    @Unique private boolean s1mp1e$opened;
    @Unique private int s1mp1e$mouseX, s1mp1e$mouseY;
    @Unique private final GlassScrollbar s1mp1e$scroller = new GlassScrollbar();
    @Unique private final dev.s1mp1e.glass.render.ContainerGlass.State s1mp1e$slotLayer =
            new dev.s1mp1e.glass.render.ContainerGlass.State();

    // glide state, recomputed once per frame in the scroller redirect
    @Unique private boolean s1mp1e$sliding, s1mp1e$wasSliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;

    @Unique private HandledScreenAccessor s1mp1e$acc() { return (HandledScreenAccessor) (Object) this; }

    /** Capture the pointer for the fused-tab hover test (renderTabIcon doesn't receive it) and reset the glide flag
     *  (the scroller redirect — which only runs on scrolling tabs — re-arms it). */
    @Inject(method = "drawBackground", at = @At("HEAD"))
    private void s1mp1e$captureMouse(DrawContext ctx, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        s1mp1e$mouseX = mouseX;
        s1mp1e$mouseY = mouseY;
        s1mp1e$wasSliding = s1mp1e$sliding;   // last frame's glide state (the body draws before the scroller decides)
        s1mp1e$sliding = false;
    }

    // ---- B: body sheet (extended by BAND above/below), replaces the item-panel PNG ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$glassBody(DrawContext self, Identifier texture,
                                  int x, int y, int u, int v, int w, int h) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            GlassTabs.reset();
            self.drawTexture(texture, x, y, u, v, w, h);
            return;
        }
        if (s1mp1e$openFade == null) s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);

        self.draw();               // flush the deferred dim so the glass lands on top + the grab captures world+dim
        // 2026-10-04：沿用 HUD 這一幀在 InGameHud.render 開頭拍的「只有世界」背景（26.2 的做法）；在暗色漸層之後重拍會讓整個背包發暗
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();

        if (!s1mp1e$opened) {
            s1mp1e$opened = true;
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
            PanelGhost.cancel();
        }
        float fade = s1mp1e$openFade.value();

        // Fused sheet: extend the body glass by BAND above and below so both tab rows are one glass piece. Keep the
        // body's ABSOLUTE corner radius (the existing panel radius min(w,h)*0.0475) by rescaling the corner fraction
        // for the taller sheet (R2 — existing body corner unchanged).
        int band = GlassTabs.BAND;
        float sheetTop = y - band, sheetBot = y + h + band;
        float bodyRadius = Math.min(w, h) * 0.0475f;
        float frac = GlassCorners.cornerFrac(w, sheetBot - sheetTop, bodyRadius);

        PanelGhost.beginFrame();
        PanelGhost.remember(x, Math.round(sheetTop), w, Math.round(sheetBot - sheetTop));
        GlassRenderer.glass(x, sheetTop, x + w, sheetBot, GlassRenderer.PAD_PANEL, frac, 0f, fade, GlassRenderer.FROST_PANEL);
        // light readability scrim over the whole sheet, matching GlassRenderer.panel's whisper
        if (GlassProgram.roundUsable()) {
            int a = Math.round(fade * 0.16f * 255f) & 0xFF;
            GlassRenderer.roundRect(x, sheetTop, x + w, sheetBot, bodyRadius, (a << 24) | 0x1C1C1E);
        }
        // the container slot layer on the sheet, exactly as every other container (26.2 draws it for creative too):
        // slot lattice + quick-craft drag highlight + the gliding hover pill (vanilla's white slot highlight is
        // suppressed for all container screens). No hover pill while the grid glides (vanilla nulls the hovered slot).
        HandledScreenAccessor acc = s1mp1e$acc();
        boolean glide = s1mp1e$wasSliding;
        dev.s1mp1e.glass.render.ContainerGlass.drawSlotLayer(s1mp1e$slotLayer, x, y, acc.s1mp1e$handler().slots,
                acc.s1mp1e$cursorDragSlots(), acc.s1mp1e$cursorDragging(),
                glide ? -10000 : s1mp1e$mouseX, glide ? -10000 : s1mp1e$mouseY, fade);
    }

    // ---- B: take over every tab icon; flush pills+icons when the selected tab is reached ----
    @Inject(method = "renderTabIcon", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$tabIcon(DrawContext ctx, ItemGroup group, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;   // vanilla draws the tab sprite
        HandledScreenAccessor a = s1mp1e$acc();
        int leftPos = a.s1mp1e$x(), topPos = a.s1mp1e$y();
        int iw = a.s1mp1e$backgroundWidth(), ih = a.s1mp1e$backgroundHeight();
        int col = s1mp1e$cell(group);
        boolean top = group.getRow() == ItemGroup.Row.TOP;
        boolean selected = group == selectedTab;
        boolean hovered = !selected && GlassTabs.inCell(s1mp1e$mouseX, s1mp1e$mouseY, leftPos, topPos, ih, col, iw, top);
        GlassTabs.deferTile(col, top, selected, hovered);
        GlassTabs.deferIcon(group.getIcon(), col, top);
        if (selected) {
            float fade = s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value();
            GlassTabs.flush(ctx, this, leftPos, topPos, iw, ih, fade);
        }
        ci.cancel();
    }

    /**
     * Tab hit boxes follow the fused band's equal cells, so clicks ({@code isClickInTab} from mouseClicked /
     * mouseReleased, which pass coordinates RELATIVE to {@code x,y}), the tab hover tooltip and the drawn pill all agree.
     * Overriding {@code getTabX} (rather than {@code isClickInTab}) keeps vanilla's own relative/absolute conventions.
     */
    @Inject(method = "getTabX", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$tabX(ItemGroup group, CallbackInfoReturnable<Integer> cir) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) {
            cir.setReturnValue(GlassTabs.tabX(s1mp1e$cell(group), s1mp1e$acc().s1mp1e$backgroundWidth()));
        }
    }

    /** Fused cell 0..6 from the vanilla tab-x, robust to how columns/special map (5 left + 2 right-aligned). */
    @Unique
    private int s1mp1e$cell(ItemGroup group) {
        int col = group.getColumn();
        int pw = s1mp1e$acc().s1mp1e$backgroundWidth();
        int vx = group.isSpecial() ? (pw - 27 * (7 - col) + 1) : (27 * col);
        return MathHelper.clamp(Math.round(vx / 27f), 0, GlassTabs.COLUMNS - 1);
    }

    // ---- C: glass scrollbar in place of the vanilla knob sprite + D: per-frame glide state ----
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/DrawContext;"
                            + "drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void s1mp1e$glassScroller(DrawContext ctx, Identifier tex, int bx, int by, int bw, int bh) {
        s1mp1e$sliding = false;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            ctx.drawGuiTexture(tex, bx, by, bw, bh);
            return;
        }
        HandledScreenAccessor a = s1mp1e$acc();
        boolean active = hasScrollbar();
        int rc = s1mp1e$rowCount();
        // vanilla's own logical row: CreativeScreenHandler.getRow = max((int)(pos*overflow + 0.5), 0)
        int row = rc <= 0 ? 0 : MathHelper.clamp((int) (scrollPosition * rc + 0.5f), 0, rc);
        float targetRatio = rc <= 0 ? 0f : (float) row / rc;
        float cx = a.s1mp1e$x() + 175 + 6f;   // knob 12 wide at x+175 -> centre +6
        float trackTop = a.s1mp1e$y() + 18f;
        float fade = s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value();
        ctx.draw();   // land any pending batch under the immediate-GL scrollbar
        GlassScrollbar.run(s1mp1e$scroller, ctx, cx, trackTop, 97f, 15f,
                targetRatio, active, scrolling && active, s1mp1e$mouseY, fade);
        if (active && rc > 0) {
            float easedRows = s1mp1e$scroller.pos() * rc;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$glideBase = MathHelper.clamp((int) Math.floor(easedRows), 0, rc);
                s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * LG_PITCH;
            }
        }
    }

    /** A click while the grid is mid-glide snaps to the target row first (acts on the item drawn under the cursor). */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding) {
            s1mp1e$scroller.snapToTarget();
            s1mp1e$sliding = false;
        }
    }

    /**
     * Switching creative category swaps the whole item grid in one frame. Snapshot the outgoing frame and cross-dissolve
     * it over the new tab (the unchanged panel / tabs / hotbar overlap, so only the item grid visibly cross-fades). HEAD,
     * before the static {@code selectedTab} flips, so the snapshot holds the old tab. Skips the re-select vanilla does
     * in {@code init}.
     */
    @Inject(method = "setSelectedTab", at = @At("HEAD"))
    private void s1mp1e$dissolveTab(ItemGroup group, CallbackInfo ci) {
        if (selectedTab != group) dev.s1mp1e.glass.render.ScreenDissolve.onTabSwitch();
    }

    // ---- GlassGlideHost -------------------------------------------------------------------------

    @Override
    public boolean s1mp1e$gliding() { return s1mp1e$sliding; }

    @Override
    public boolean s1mp1e$probeGliding() { return s1mp1e$sliding; }

    @Override
    public float s1mp1e$probeOffsetPx() { return s1mp1e$scroller.pos() * s1mp1e$rowCount() * LG_PITCH; }

    @Override
    public boolean s1mp1e$isGlideSlot(Slot slot) {
        return slot.x >= LG_GRID_X && slot.x < LG_GRID_X + LG_COLS * LG_PITCH
            && slot.y >= LG_GRID_Y && slot.y < LG_GRID_Y + LG_VIS_ROWS * LG_PITCH;
    }

    @Override
    public void s1mp1e$drawGlideOverlay(DrawContext context) {
        List<ItemStack> items = s1mp1e$items();
        if (items == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        // This overlay runs inside the (x,y,0)-translated matrix (HandledScreen.render). 1.21.1 enableScissor IGNORES the
        // pose, so the scissor is given in ABSOLUTE screen coords while the item draws stay slot-relative.
        int px = s1mp1e$acc().s1mp1e$x(), py = s1mp1e$acc().s1mp1e$y();
        int sx0 = px + LG_GRID_X, sy0 = py + LG_GRID_Y;
        int sx1 = sx0 + LG_COLS * LG_PITCH, sy1 = sy0 + LG_VIS_ROWS * LG_PITCH;
        context.enableScissor(sx0, sy0, sx1, sy1);
        context.getMatrices().push();
        context.getMatrices().translate(0f, -s1mp1e$glideFracPx, 0f);
        try {
            for (int vr = 0; vr <= LG_VIS_ROWS; vr++) {          // 5 visible + ONE extra row (no edge gap)
                int row = s1mp1e$glideBase + vr;
                int y = LG_GRID_Y + vr * LG_PITCH;
                for (int col = 0; col < LG_COLS; col++) {
                    int idx = row * LG_COLS + col;
                    if (idx < 0 || idx >= items.size()) continue;
                    ItemStack st = items.get(idx);
                    if (st.isEmpty()) continue;
                    int x = LG_GRID_X + col * LG_PITCH;
                    context.drawItem(st, x, y);
                    context.drawItemInSlot(mc.textRenderer, st, x, y);
                }
            }
        } finally {
            context.getMatrices().pop();
            context.disableScissor();
        }
        // iOS-26 scroll-edge whisper over the window while it glides: flush the redrawn items, then take a fresh
        // composite grab so the edge band blurs the items it sits on (never itself, never a stale frame — R4).
        if (GlassProgram.edgeUsable()) {
            context.draw();
            SceneCapture.grabNow();
            int rc = s1mp1e$rowCount();
            float topK = (s1mp1e$glideBase > 0 || s1mp1e$glideFracPx > 0.5f) ? 1f : 0f;
            float botK = (s1mp1e$glideBase + LG_VIS_ROWS) < rc + LG_VIS_ROWS ? 1f : 0f;
            float fade = s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value();
            GlassWidgets.scrollEdges(sx0, sy0, sx1, sy1, 6f, topK, botK, fade);
        }
    }

    @Unique
    private int s1mp1e$rowCount() {
        List<ItemStack> items = s1mp1e$items();
        if (items == null) return 0;
        return Math.max(0, MathHelper.ceilDiv(items.size(), LG_COLS) - LG_VIS_ROWS);
    }

    @Unique
    private List<ItemStack> s1mp1e$items() {
        try {
            Object h = ((CreativeInventoryScreen) (Object) this).getScreenHandler();
            if (h instanceof CreativeInventoryScreen.CreativeScreenHandler csh) return csh.itemList;
        } catch (Throwable ignored) {}
        return null;
    }
}
