package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GlassGlideHost;
import dev.s1mp1e.client.gui.GlassScrollbar;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.GlassTabs;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.container.Slot;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DefaultedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.List;

/**
 * Creative inventory -> the FINAL fused liquid-glass design (26.2's fused-band tabs ported to 1.15.2, FF-Fabric):
 * <ul>
 *   <li><b>Body + tabs = ONE glass sheet (B)</b> — the item-panel body blit (ordinal 0 of {@code drawBackground}) is
 *       redirected to a glass sheet extended {@link GlassTabs#BAND} px above and below, so both tab rows are the top /
 *       bottom band of the same surface (no seam); the body corner radius is unchanged (the knob is rescaled for the
 *       taller sheet). Each tab is a cell of the band; the selected tab is a lifted glass pill that SLIDES within a row
 *       and CROSS-FADES across rows, a hovered tab a fainter pill with the slot-hover motion. Tab hit boxes
 *       ({@code isClickInTab}) move to the cells.</li>
 *   <li><b>Scrollbar (C)</b> — the item-grid scroller blit (ordinal 1) becomes the shared vertical glass slider.</li>
 *   <li><b>Silky content (D)</b> — the 45-slot grid GLIDES sub-pixel with the eased scrollbar value; vanilla keeps a
 *       row-aligned logical scroll (clicks / hit-test / tooltips correct at rest) while the eased overlay draws the
 *       visible stacks translated by the fractional offset, scissored to the window with one extra row; a click
 *       mid-glide snaps to the target row first.</li>
 * </ul>
 *
 * <p><b>1.15.2 deltas vs 1.20.1:</b> no {@code DrawContext} — the body / tabs / scroller draws are all raw
 * blit ({@code IIIIII}) invokes at pose-identity ({@code drawBackground} runs before {@code render}'s
 * {@code translate(x,y)}); tab icons draw through the item renderer; {@code selectedTab} is an {@code int} index (not an
 * {@code ItemGroup}); the tab hit box is {@code isClickInTab} (there is no {@code getTabX}); the grid glide overlay runs
 * inside {@code render}'s {@code translate(x,y)} pose, so its item draws are slot-relative while the raw
 * {@code glScissor} stays in absolute window coords — the mirror of the 26.2 double-translate trap.
 */
@Mixin(CreativeInventoryScreen.class)
public abstract class CreativeGlassMixin implements GlassGlideHost {

    @Shadow private static int selectedTab;
    @Shadow private float scrollPosition;
    @Shadow private boolean scrolling;
    @Shadow private boolean hasScrollbar() { return false; }
    @Shadow protected abstract void renderTabIcon(ItemGroup group);

    // Panel geometry is inherited from ContainerScreen; read it via the accessor (no inherited-@Shadow warning).
    @Unique private int s1mp1e$px() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$left(); }
    @Unique private int s1mp1e$py() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$top(); }
    @Unique private int s1mp1e$pw() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$xSize(); }
    @Unique private int s1mp1e$ph() { return ((ContainerScreenTopAccessor) (Object) this).s1mp1e$ySize(); }

    // ---- item grid geometry (slot-relative): 9 cols x 5 rows of 18 px, origin (9,18) ----
    @Unique private static final int LG_GRID_X = 9;
    @Unique private static final int LG_GRID_Y = 18;
    @Unique private static final int LG_COLS = 9;
    @Unique private static final int LG_VIS_ROWS = 5;
    @Unique private static final int LG_PITCH = 18;

    @Unique private Fade s1mp1e$openFade;
    @Unique private boolean s1mp1e$opened;
    @Unique private GlassScrollbar s1mp1e$scrollbar;

    // glide state, recomputed once per frame (reset in the body redirect, set in the scroller redirect)
    @Unique private boolean s1mp1e$sliding;
    @Unique private int s1mp1e$glideBase;
    @Unique private float s1mp1e$glideFracPx;
    @Unique private int s1mp1e$glideRowCount;

    // #4 — creative slot lattice + gliding hover pill (survival has these via HandledScreenGlassMixin, which returns
    // early for creative). Same look/rig as that mixin: static neighbour-mask lattice + a two-axis hover spring.
    @Unique private Spring s1mp1e$hx1, s1mp1e$hx2, s1mp1e$hy1, s1mp1e$hy2;
    @Unique private final Fade s1mp1e$hoverFade = new Fade(0f, 100f);
    @Unique private boolean s1mp1e$hoverActive;
    @Unique private long s1mp1e$hoverNanos;

    // blit is inherited from DrawableHelper (public). NOT @Shadow'd (inherited-method @Shadow fails at apply); the
    // no-glass fallbacks call it through the redirect's `self` param, whose static type publicly inherits blit.

    // ---- (B) body + fused tabs -------------------------------------------------------------------

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 0,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;blit(IIIIII)V"))
    private void s1mp1e$body(CreativeInventoryScreen self, int x, int y, int u, int v, int w, int h) {
        s1mp1e$sliding = false;   // reset once per frame (the body blit always runs); the scroller may set it true
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            GlassTabs.reset();
            self.blit(x, y, u, v, w, h);
            return;
        }
        if (s1mp1e$openFade == null) s1mp1e$openFade = new Fade(0f, PanelGhost.FADE_MS);
        // Frame-primary panel: grab a FRESH backdrop the instant before the glass draw (R4). Creative has no pre-dim
        // grabNow to fold onto (unlike the survival inventory's InventoryGlassMixin), so it must FORCE the copy every
        // frame: a deduped grab() could, at >333 fps, fold onto the PREVIOUS frame's tooltip forceGrab (which captured
        // the full GUI incl. glass) and the panel would refract itself -> flicker (the 1.16.5 root cause; mc1165 uses
        // grabNow() here for the same reason).
        SceneCapture.grabNow();
        if (!s1mp1e$opened) {
            s1mp1e$opened = true;
            s1mp1e$openFade.snap(0f);
            s1mp1e$openFade.to(1f);
            PanelGhost.cancel();
        }
        float fade = s1mp1e$openFade.value();
        PanelGhost.beginFrame();
        PanelGhost.remember(x, y, w, h);

        // ONE fused sheet: body + a BAND above and below. Corner rescaled so the ABSOLUTE radius equals the un-extended
        // body's (R2/R3 — the body radius must not change): keep min(halfSide)*0.5*knob constant on the taller sheet.
        int sheetTop = y - GlassTabs.BAND, sheetBot = y + h + GlassTabs.BAND;
        float sheetH = sheetBot - sheetTop;
        float corner = 0.19f * Math.min(w, h) / Math.min((float) w, sheetH);
        GlassRenderer.glass(x, sheetTop, x + w, sheetBot, GlassRenderer.PAD_PANEL, corner, 0f, fade, GlassRenderer.FROST_PANEL);
    }

    /** Every tab -> a deferred cell of the fused band; the selected tab (extracted last) flushes pills + all icons on top. */
    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;renderTabIcon(Lnet/minecraft/item/ItemGroup;)V"))
    private void s1mp1e$tab(CreativeInventoryScreen self, ItemGroup group, float delta, int mouseX, int mouseY) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            this.renderTabIcon(group);
            return;
        }
        int px = s1mp1e$px(), py = s1mp1e$py(), pw = s1mp1e$pw(), ph = s1mp1e$ph();
        boolean selected = group.getIndex() == selectedTab;
        boolean top = group.isTopRow();
        int col = s1mp1e$cell(group);
        float c = GlassTabs.cellW(pw);
        float cellX0 = px + col * c;
        int by0 = top ? py - GlassTabs.BAND : py + ph;
        boolean hovered = !selected && mouseX >= cellX0 && mouseX < cellX0 + c
                && mouseY >= by0 && mouseY < by0 + GlassTabs.BAND;
        GlassTabs.deferTile(col, top, selected, hovered);
        GlassTabs.deferIcon(group.getIcon(), col, top);
        if (selected) {
            GlassTabs.flush(this, px, py, pw, ph,
                    Math.round(s1mp1e$openFade == null ? 255f : s1mp1e$openFade.value() * 255f));
            // #4 — the creative slot lattice + gliding hover pill (survival gets these from HandledScreenGlassMixin,
            // which returns early for creative). No hover pill while the item grid glides.
            List<Slot> slots = ((ContainerScreenTopAccessor) (Object) this).s1mp1e$container().slots;
            float fade = s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value();
            s1mp1e$drawLattice(slots, px, py, fade);
            boolean glide = s1mp1e$sliding;
            s1mp1e$drawHover(slots, px, py, glide ? -10000 : mouseX, glide ? -10000 : mouseY, System.nanoTime());
        }
    }

    /** Slot-separator lattice: one glass cell per slot with a 4-bit neighbour mask (E/W/S/N), like the survival grid. */
    @Unique
    private static void s1mp1e$drawLattice(List<Slot> slots, int gl, int gt, float fade) {
        HashSet<Long> pos = new HashSet<Long>();
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            pos.add((((long) s.xPosition) << 32) | (s.yPosition & 0xffffffffL));
        }
        if (!GlassRenderer.beginBatch(GlassProgram.LINE)) return;
        try {
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                int sx = s.xPosition, sy = s.yPosition, mask = 0;
                if (pos.contains((((long) (sx + 18)) << 32) | (sy & 0xffffffffL))) mask |= 1; // E
                if (pos.contains((((long) (sx - 18)) << 32) | (sy & 0xffffffffL))) mask |= 2; // W
                if (pos.contains((((long) sx) << 32) | ((sy + 18) & 0xffffffffL)))  mask |= 4; // S
                if (pos.contains((((long) sx) << 32) | ((sy - 18) & 0xffffffffL)))  mask |= 8; // N
                GlassRenderer.batchQuad(gl + sx - 1, gt + sy - 1, gl + sx + 17, gt + sy + 17,
                                        0f, 1f, 1f, fade, (mask * 17) / 255f);
            }
        } finally {
            GlassRenderer.endBatch();
        }
    }

    /** Gliding hover pill on the two-axis spring rig (lead 55 / trail 30, critically damped), the survival look. */
    @Unique
    private void s1mp1e$drawHover(List<Slot> slots, int gl, int gt, int mouseX, int mouseY, long now) {
        Slot hov = null;
        int px = mouseX - gl, py = mouseY - gt;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (px >= s.xPosition - 1 && px < s.xPosition + 17 && py >= s.yPosition - 1 && py < s.yPosition + 17
                    && s.doDrawHoveringEffect()) hov = s;
        }
        boolean hovering = hov != null;
        float dt = (s1mp1e$hoverNanos == 0L) ? (1f / 60f) : Math.min(0.1f, (now - s1mp1e$hoverNanos) * 1.0e-9f);
        s1mp1e$hoverNanos = now;
        if (hovering) {
            float cx = gl + hov.xPosition + 8f, cy = gt + hov.yPosition + 8f;
            if (s1mp1e$hx1 == null || (!s1mp1e$hoverActive && s1mp1e$hoverFade.value() <= 0.05f)) {
                s1mp1e$hx1 = new Spring(cx, Spring.OMEGA_SNAP, Spring.DAMPING);
                s1mp1e$hx2 = new Spring(cx, Spring.OMEGA_MED,  Spring.DAMPING);
                s1mp1e$hy1 = new Spring(cy, Spring.OMEGA_SNAP, Spring.DAMPING);
                s1mp1e$hy2 = new Spring(cy, Spring.OMEGA_MED,  Spring.DAMPING);
            } else {
                s1mp1e$hx1.setTarget(cx); s1mp1e$hx2.setTarget(cx);
                s1mp1e$hy1.setTarget(cy); s1mp1e$hy2.setTarget(cy);
            }
            s1mp1e$hoverActive = true;
            s1mp1e$hoverFade.to(1f);
        } else {
            s1mp1e$hoverActive = false;
            s1mp1e$hoverFade.to(0f);
            if (s1mp1e$hoverFade.value() <= 0.004f || s1mp1e$hx1 == null) return;
        }
        s1mp1e$hx1.advance(dt); s1mp1e$hx2.advance(dt);
        s1mp1e$hy1.advance(dt); s1mp1e$hy2.advance(dt);
        float lox = Math.min(s1mp1e$hx1.value(), s1mp1e$hx2.value()), hix = Math.max(s1mp1e$hx1.value(), s1mp1e$hx2.value());
        float loy = Math.min(s1mp1e$hy1.value(), s1mp1e$hy2.value()), hiy = Math.max(s1mp1e$hy1.value(), s1mp1e$hy2.value());
        GlassRenderer.glass(lox - 10f, loy - 10f, hix + 10f, hiy + 10f,
                            6f, 1.0f, 0.12f, s1mp1e$hoverFade.value(), GlassRenderer.FROST_NONE);
    }

    /**
     * Tab hit boxes follow the fused band's equal cells, so clicks / tooltips match what is drawn. Vanilla calls
     * {@code isClickInTab} with PANEL-RELATIVE coords ({@code mouseX - x}, {@code mouseY - y}) — mouseReleased then does
     * the {@code setSelectedTab} — so the cell rect here is in the same relative space (panel left/top = 0,0).
     */
    @Inject(method = "isClickInTab", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$clickTab(ItemGroup group, double mx, double my, CallbackInfoReturnable<Boolean> cir) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        int pw = s1mp1e$pw(), ph = s1mp1e$ph();
        int col = s1mp1e$cell(group);
        boolean top = group.isTopRow();
        float c = GlassTabs.cellW(pw);
        float x0 = col * c, x1 = x0 + c;              // relative to panel left
        float y0 = top ? -GlassTabs.BAND : ph;        // relative to panel top
        float y1 = y0 + GlassTabs.BAND;
        cir.setReturnValue(mx >= x0 && mx < x1 && my >= y0 && my < y1);
    }

    /** Fused cell 0..6 from the vanilla tab-x (5 left-aligned cols 0..4 + 1 right-aligned special col -> cell 6). */
    @Unique
    private int s1mp1e$cell(ItemGroup group) {
        int pw = s1mp1e$pw();
        int col = group.getColumn();   // 0..5 (index % 6)
        float tabXRel = group.isSpecial() ? (pw - 28f * (6 - col)) : (28f * col + (col > 0 ? col : 0));
        float c = GlassTabs.cellW(pw);
        int cell = Math.round((tabXRel + 14f) / c - 0.5f);
        return cell < 0 ? 0 : (cell > GlassTabs.COLUMNS - 1 ? GlassTabs.COLUMNS - 1 : cell);
    }

    // ---- (C) glass scrollbar + (D) glide state --------------------------------------------------

    @Redirect(method = "drawBackground",
            at = @At(value = "INVOKE", ordinal = 1,
                     target = "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen;blit(IIIIII)V"))
    private void s1mp1e$scroller(CreativeInventoryScreen self, int sx, int sy, int u, int v, int w, int h,
                                 float delta, int mouseX, int mouseY) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            self.blit(sx, sy, u, v, w, h);
            return;
        }
        if (s1mp1e$scrollbar == null) s1mp1e$scrollbar = new GlassScrollbar();
        boolean active = this.hasScrollbar();
        int rc = s1mp1e$rowCount();
        int row = rc <= 0 ? 0 : s1mp1e$clamp(Math.round(scrollPosition * rc), 0, rc);
        float targetRatio = rc <= 0 ? 0f : (float) row / rc;
        float fade = s1mp1e$openFade == null ? 1f : s1mp1e$openFade.value();
        // creative track: thumb 12x15, top at py+18, thumb-top travel 97 (vanilla mouseDragged divides by 112-15).
        GlassScrollbar.run(s1mp1e$scrollbar, sx + w / 2f, s1mp1e$py() + 18f, 97f, 15f,
                targetRatio, active, scrolling && active, mouseY, fade);
        if (active && rc > 0) {
            float easedRows = s1mp1e$scrollbar.pos() * rc;
            s1mp1e$glideRowCount = rc;
            if (Math.abs(easedRows - row) > 0.02f) {
                s1mp1e$sliding = true;
                s1mp1e$glideBase = s1mp1e$clamp((int) Math.floor(easedRows), 0, rc);
                s1mp1e$glideFracPx = (easedRows - s1mp1e$glideBase) * LG_PITCH;
            }
        }
    }

    /** A click while the grid is mid-glide snaps to the target row first (acts on the item drawn under the cursor). */
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void s1mp1e$snapOnClick(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (s1mp1e$sliding && s1mp1e$scrollbar != null) {
            s1mp1e$scrollbar.snapToTarget();
            s1mp1e$sliding = false;
        }
    }

    // ---- GlassGlideHost -------------------------------------------------------------------------

    @Override
    public boolean s1mp1e$gliding() { return s1mp1e$sliding; }

    @Override
    public boolean s1mp1e$isGlideSlot(Slot slot) {
        return slot.xPosition >= LG_GRID_X && slot.xPosition < LG_GRID_X + LG_COLS * LG_PITCH
            && slot.yPosition >= LG_GRID_Y && slot.yPosition < LG_GRID_Y + LG_VIS_ROWS * LG_PITCH;
    }

    @Override
    public void s1mp1e$drawGlideOverlay() {
        DefaultedList<ItemStack> items = s1mp1e$items();
        if (items == null) return;
        int px = s1mp1e$px(), py = s1mp1e$py();
        // This overlay runs inside render()'s translate(x,y) pose, so item draws stay slot-relative; the raw glScissor is
        // in absolute window coords (unaffected by the modelview translate) — the mirror of 26.2's double-translate trap.
        GlassWidgets.beginScissor(px + LG_GRID_X, py + LG_GRID_Y,
                px + LG_GRID_X + LG_COLS * LG_PITCH, py + LG_GRID_Y + LG_VIS_ROWS * LG_PITCH);
        RenderSystem.pushMatrix();
        RenderSystem.translatef(0f, -s1mp1e$glideFracPx, 0f);
        GlassWidgets.resetColorCache();
        DiffuseLighting.enableGuiDepthLighting();
        RenderSystem.enableRescaleNormal();
        MinecraftClient mc = MinecraftClient.getInstance();
        ItemRenderer ir = mc.getItemRenderer();
        TextRenderer font = mc.textRenderer;
        try {
            for (int vr = 0; vr <= LG_VIS_ROWS; vr++) {
                int rowIdx = s1mp1e$glideBase + vr;
                int y = LG_GRID_Y + vr * LG_PITCH;
                for (int col = 0; col < LG_COLS; col++) {
                    int idx = rowIdx * LG_COLS + col;
                    if (idx < 0 || idx >= items.size()) continue;
                    ItemStack st = items.get(idx);
                    if (st == null || st.isEmpty()) continue;
                    int ix = LG_GRID_X + col * LG_PITCH;
                    ir.renderGuiItem(st, ix, y);
                    ir.renderGuiItemOverlay(font, st, ix, y);
                }
            }
        } finally {
            RenderSystem.disableRescaleNormal();
            GlassWidgets.resetColorCache();
            RenderSystem.enableAlphaTest();
            RenderSystem.enableBlend();
            RenderSystem.popMatrix();
            GlassWidgets.endScissor();
        }
    }

    @Unique
    private int s1mp1e$rowCount() {
        DefaultedList<ItemStack> items = s1mp1e$items();
        if (items == null) return 0;
        int rows = (items.size() + LG_COLS - 1) / LG_COLS;   // ceilDiv
        return Math.max(0, rows - LG_VIS_ROWS);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private DefaultedList<ItemStack> s1mp1e$items() {
        try {
            Object c = ((CreativeInventoryScreen) (Object) this).getContainer();
            if (c instanceof CreativeInventoryScreen.CreativeContainer) {
                return ((CreativeInventoryScreen.CreativeContainer) c).itemList;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Unique
    private static int s1mp1e$clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
