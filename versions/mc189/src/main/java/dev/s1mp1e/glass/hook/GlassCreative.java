package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.ui.GlassScrollbar;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.creativetab.CreativeTabs;

import java.lang.reflect.Field;

/**
 * Creative-inventory glass (PORT_SPEC features B + C, 1.8.9 two-row layout).
 *
 * <p><b>B — fused tabs.</b> The inventory body glass is extended by a BAND above
 * and below so BOTH tab rows are ONE glass sheet fused with the body (no seam).
 * The selected tab is a lifted glass pill that SLIDES like the hotbar selection
 * pill within a row (lead omega 55 / trail omega 30, critically damped) and
 * CROSS-FADES when the selection jumps to the other row (old pill out 150 ms, new
 * pill snaps + in 100 ms). Hovering a tab shows a fainter pill with the container
 * slot-hover motion (glide + fade in 100 / out 150 ms). New pieces use the hotbar
 * corner radius via {@link GlassCorners} (R2); the body's own radius is preserved
 * by rescaling the knob for the taller sheet.
 *
 * <p>Vanilla's tab positions and hit boxes are UNCHANGED, and the icons are still
 * drawn by vanilla at those positions — only the tab background sprites and the
 * scrollbar thumb are suppressed (by {@code BlitSuppressor}'s creative mode, armed
 * around the background layer by {@code ContainerHook}). The pills are drawn at
 * the vanilla tab centres, so what is drawn is what is clicked.
 *
 * <p><b>C — vertical glass scrollbar.</b> The vanilla creative scrollbar thumb is
 * suppressed and re-drawn as {@link GlassScrollbar} (15 px white capsule that
 * morphs to a refracting lens when held, settle glide, rubber-band). The vanilla
 * screen keeps the logical scroll (its {@code currentScroll}, updated 1:1 from the
 * mouse and clamped), so the item grid, clicks and tooltips stay row-correct.
 *
 * <p>All of this is drawn from {@code GlassContainerHandler}'s BackgroundDrawnEvent
 * (before items + tooltip), so the tooltip stays on the very top layer (R1). The
 * sheet, pills and lens all refract the fresh world+dim backdrop the container
 * pass grabbed (R4).
 */
public final class GlassCreative {

    /** Row depth outside the panel edge (matches the 28 px tall vanilla tab sprite). */
    private static final int   BAND = 28;
    /** The base container panel's corner knob (GlassRenderer.panel uses 0.19). */
    private static final float PANEL_CORNER = 0.19f;
    private static final int   PILL_INSET = 2;
    /** Selected pill neutral lift (26.2 LIFT_SELECTED 0xE0 -> 1-0xE0/255). */
    private static final float LIFT_SELECTED = 0.122f;
    /** Hover pill neutral lift (26.2 LIFT_HOVER 0xF4 -> fainter). */
    private static final float LIFT_HOVER    = 0.043f;

    // ---- selected pill ----
    private Spring selX1, selX2;
    private float  selCy;
    private boolean haveSel;
    private int    lastSelIdx = -1;
    private boolean lastRow;
    private final Fade selIn  = new Fade(0f, 100f);
    private final Fade selOut = new Fade(0f, 150f);
    private float gx0, gy0, gx1, gy1;   // ghost pill rect during a cross-fade

    // ---- hover pill ----
    private Spring hvX1, hvX2, hvY1, hvY2;
    private boolean hvActive;
    private final Fade hvFade = new Fade(0f, 100f);

    private long lastNanos = 0L;

    // ---- scrollbar ----
    private final GlassScrollbar scrollbar = new GlassScrollbar();

    // ---- reflection into GuiContainerCreative private fields ----
    private static boolean reflectDone;
    private static Field fCurrentScroll, fIsScrolling;

    public void reset() {
        selX1 = selX2 = null; haveSel = false; lastSelIdx = -1;
        selIn.snap(0f); selOut.snap(0f);
        hvX1 = hvX2 = hvY1 = hvY2 = null; hvActive = false; hvFade.snap(0f);
        lastNanos = 0L;
        scrollbar.reset();
        GlassCreativeGlide.reset();
    }

    /**
     * The fused body sheet — the container panel extended by {@link #BAND} above
     * and below, with the corner knob rescaled so the ABSOLUTE corner radius equals
     * the un-extended panel's (R2: the body radius is unchanged).
     */
    public void drawSheet(int gl, int gt, int xs, int ys, float fade) {
        float baseMin = Math.min(xs, ys);
        float sheetH  = ys + 2 * BAND;
        float sheetMin = Math.min(xs, sheetH);
        // radius = min*0.25*knob ; keep radius constant -> knob' = baseMin*PANEL_CORNER/sheetMin
        float knob = PANEL_CORNER * baseMin / sheetMin;
        GlassRenderer.glass(gl, gt - BAND, gl + xs, gt + ys + BAND,
                            GlassRenderer.PAD_PANEL, knob, 0f, fade, GlassRenderer.FROST_PANEL);
    }

    /**
     * Selected + hover pills, then the glass scrollbar. Call AFTER the sheet and
     * lattice, BEFORE the tab icons (which vanilla draws in the background layer).
     */
    public void drawTabsAndScrollbar(GuiContainerCreative screen, int gl, int gt, int xs, int ys,
                                     int mouseX, int mouseY, float fade) {
        long now = System.nanoTime();
        float dt = (lastNanos == 0L) ? (1f / 60f) : Math.min(0.1f, (now - lastNanos) * 1e-9f);
        lastNanos = now;

        int selIdx = safeSelectedIndex(screen);
        CreativeTabs sel = tabAt(selIdx);

        drawSelectedPill(sel, gl, gt, xs, ys, dt, fade);
        drawHoverPill(sel, gl, gt, xs, ys, mouseX, mouseY, dt, fade);
        drawScrollbar(screen, sel, gl, gt, ys, mouseY, fade);
    }

    // -----------------------------------------------------------------------
    // selected pill: slide within a row, cross-fade across rows
    // -----------------------------------------------------------------------
    private void drawSelectedPill(CreativeTabs sel, int gl, int gt, int xs, int ys, float dt, float fade) {
        if (sel == null) return;
        float[] r = tabRect(sel, gl, gt, xs, ys);   // sprite rect [x0,y0,x1,y1]
        float cx = (r[0] + r[2]) * 0.5f;
        float cy = bandCy(sel, gt, ys);             // 玻璃帶正中（不是原版貼圖的中心）
        boolean row = sel.isTabInFirstRow();
        int idx = sel.getTabIndex();

        if (!haveSel || selX1 == null) {
            selX1 = new Spring(cx, Spring.OMEGA_SNAP, Spring.DAMPING);
            selX2 = new Spring(cx, Spring.OMEGA_MED,  Spring.DAMPING);
            selCy = cy;
            selIn.snap(1f); selOut.snap(0f);
            haveSel = true; lastSelIdx = idx; lastRow = row;
        } else if (idx != lastSelIdx) {
            if (row == lastRow) {
                // same row: slide the pill's X to the new cell
                selX1.setTarget(cx); selX2.setTarget(cx);
                selCy = cy;
            } else {
                // row switch: fade the old pill out where it stands, snap+fade the new in
                float px0 = Math.min(selX1.value(), selX2.value()) - halfW(r);
                float px1 = Math.max(selX1.value(), selX2.value()) + halfW(r);
                gx0 = px0; gy0 = selCy - halfH(r); gx1 = px1; gy1 = selCy + halfH(r);
                selOut.snap(1f); selOut.to(0f, 150f);
                selX1.snap(cx); selX2.snap(cx); selCy = cy;
                selIn.snap(0f); selIn.to(1f, 100f);
            }
            lastSelIdx = idx; lastRow = row;
        }

        selX1.advance(dt); selX2.advance(dt);

        // ghost (fading out old pill from the row switch)
        float outA = selOut.value();
        if (outA > 0.01f) {
            pill(gx0, gy0, gx1, gy1, LIFT_SELECTED, fade * outA);
        }
        // current pill
        float lox = Math.min(selX1.value(), selX2.value()) - halfW(r);
        float hix = Math.max(selX1.value(), selX2.value()) + halfW(r);
        pill(lox, selCy - halfH(r), hix, selCy + halfH(r), LIFT_SELECTED, fade * selIn.value());
    }

    // -----------------------------------------------------------------------
    // hover pill: slot-hover motion on the hovered (non-selected) tab
    // -----------------------------------------------------------------------
    private void drawHoverPill(CreativeTabs sel, int gl, int gt, int xs, int ys,
                               int mouseX, int mouseY, float dt, float fade) {
        CreativeTabs hov = tabUnderMouse(gl, gt, xs, ys, mouseX, mouseY);
        boolean hovering = hov != null && (sel == null || hov.getTabIndex() != sel.getTabIndex());

        if (hovering) {
            float[] r = tabRect(hov, gl, gt, xs, ys);
            float cx = (r[0] + r[2]) * 0.5f, cy = bandCy(hov, gt, ys);
            if (hvX1 == null || (!hvActive && hvFade.value() <= 0.05f)) {
                hvX1 = new Spring(cx, Spring.OMEGA_SNAP, Spring.DAMPING);
                hvX2 = new Spring(cx, Spring.OMEGA_MED,  Spring.DAMPING);
                hvY1 = new Spring(cy, Spring.OMEGA_SNAP, Spring.DAMPING);
                hvY2 = new Spring(cy, Spring.OMEGA_MED,  Spring.DAMPING);
            } else {
                hvX1.setTarget(cx); hvX2.setTarget(cx);
                hvY1.setTarget(cy); hvY2.setTarget(cy);
            }
            hvActive = true;
            hvFade.retarget(1f, 100f);
        } else {
            hvActive = false;
            hvFade.retarget(0f, 150f);
            if (hvFade.value() <= 0.004f || hvX1 == null) return;
        }

        hvX1.advance(dt); hvX2.advance(dt); hvY1.advance(dt); hvY2.advance(dt);
        // 和選中 pill 同樣的正方形（BAND/2 − 內距 = 12 → 24×24）
        float hw = BAND * 0.5f - PILL_INSET, hh = hw;
        float lox = Math.min(hvX1.value(), hvX2.value()) - hw;
        float hix = Math.max(hvX1.value(), hvX2.value()) + hw;
        float loy = Math.min(hvY1.value(), hvY2.value()) - hh;
        float hiy = Math.max(hvY1.value(), hvY2.value()) + hh;
        pill(lox, loy, hix, hiy, LIFT_HOVER, fade * hvFade.value() * 0.7f);
    }

    // -----------------------------------------------------------------------
    // glass scrollbar (C) — only for grid tabs that hide the player inventory
    // -----------------------------------------------------------------------
    private void drawScrollbar(GuiContainerCreative screen, CreativeTabs sel,
                               int gl, int gt, int ys, int mouseY, float fade) {
        boolean gridTab = sel != null && sel.shouldHidePlayerInventory();
        java.util.List<net.minecraft.item.ItemStack> items =
                gridTab ? GlassCreativeGlide.items(screen) : null;
        int rc = GlassCreativeGlide.rowCount(items);

        if (!gridTab) {
            // Not a scrollable grid tab (e.g. the survival-inventory tab): clear any
            // stale glide so the drawSlot overlay stands down.
            GlassCreativeGlide.update(screen, scrollbar, gl, gt, 0, 0, false, null);
            return;
        }

        ensureReflect();
        float rawScroll = 0f;
        boolean dragging = false;
        try {
            if (fCurrentScroll != null) rawScroll = fCurrentScroll.getFloat(screen);
            if (fIsScrolling  != null) dragging = fIsScrolling.getBoolean(screen);
        } catch (Throwable ignored) {}
        dragging = dragging || devDragging;
        rawScroll = clamp01(rawScroll);

        // Vanilla's own logical top row (scrollTo rounds to this): clicks/tooltips
        // hit-test against it.
        int logicalRow = rc <= 0 ? 0 : Math.round(rawScroll * rc);
        if (logicalRow < 0) logicalRow = 0;
        if (logicalRow > rc) logicalRow = rc;
        // Feed the bar the ROW-ALIGNED ratio when not dragging (so the eased content
        // settles onto an integer row and hands back to vanilla), but the raw pointer
        // ratio while dragging (so thumb + content follow the drag 1:1 and sub-pixel).
        float feedRatio = (dragging || rc <= 0) ? rawScroll : (float) logicalRow / rc;

        // Vanilla thumb column: guiLeft+175..+187, track guiTop+18 .. +130, thumb 15.
        float cx = gl + 175 + 6f;
        float trackTop = gt + 18f;
        float thumbLen = 15f;
        float travel = 112f - thumbLen;   // 97
        boolean active = true;            // grid tabs always show the groove
        scrollbar.run(cx, trackTop, travel, thumbLen, feedRatio, active, dragging, mouseY, fade);

        // Publish the eased position so the coremod drawSlot head-splice can glide
        // the item grid sub-pixel (feature D).
        GlassCreativeGlide.update(screen, scrollbar, gl, gt, rc, logicalRow, rc > 0, items);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /**
     * 只給 DevShot 用：代替「按住滑桿」。原版每幀在畫背景之前用 Mouse.isButtonDown(0) 重設 isScrolling，
     * 腳本沒辦法真的按住滑鼠，所以用這個旗標讓滑桿畫成按住狀態（捲動位置由腳本直接改 currentScroll）。
     */
    public static boolean devDragging;

    // ---- pill drawing (hotbar corner via GlassCorners, sharp like the hotbar selector) ----
    private static void pill(float x0, float y0, float x1, float y1, float lift, float opacity) {
        if (opacity <= 0.003f) return;
        float w = x1 - x0, h = y1 - y0;
        if (w <= 0f || h <= 0f) return;
        float knob = GlassCorners.cornerKnob(w, h);
        GlassRenderer.glass(x0, y0, x1, y1, 6f, knob, lift, opacity, GlassRenderer.FROST_NONE);
    }

    // ---- vanilla tab geometry (func_147051_a's l/i1, sprite 28x32) ----
    private static float[] tabRect(CreativeTabs tab, int gl, int gt, int xs, int ys) {
        int col = tab.getTabColumn();
        int l = gl + 28 * col;
        if (col == 5)      l = gl + xs - 28;
        else if (col > 0)  l += col;               // -> gl + 29*col
        int i1 = tab.isTabInFirstRow() ? (gt - 28) : (gt + ys - 4);
        return new float[] { l, i1, l + 28, i1 + 32 };
    }

    // 分類 pill：正方形，置於「分類格（28 寬）× 玻璃帶（BAND = 28 高）」正中、四邊各內縮 PILL_INSET——
    // 和 26.2／1.21.1 的 GlassTabs 同一條規則（hw = 格寬/2 − 內距、hh = BAND/2 − 內距，格寬 ≈ BAND 所以是正方形）。
    // 2026-10-04 使用者要求：原本用原版分類貼圖的 28×32 矩形內縮，pill 是 24 寬 × 28 高的直長形，而且中心比玻璃帶
    // 偏了 2 px（貼圖有 4 px 伸進面板）。圖示由 tabIcon() 一起移到玻璃帶正中。
    private static float halfW(float[] r) { return BAND * 0.5f - PILL_INSET; }
    private static float halfH(float[] r) { return BAND * 0.5f - PILL_INSET; }

    /** 分類在玻璃帶裡的中心 y：上排是面板頂邊上方 BAND/2，下排是面板底邊下方 BAND/2。 */
    private static float bandCy(CreativeTabs tab, int gt, int ys) {
        return tab.isTabInFirstRow() ? gt - BAND * 0.5f : gt + ys + BAND * 0.5f;
    }

    /**
     * 原版的分類圖示位置（func_147051_a）：上排 y = 頂邊 − 19（中心 −11）、下排 y = 底邊 + 3（中心 +11）；
     * 玻璃帶中心是 −14／+14，所以上排往上 3、下排往下 3，圖示就在 pill 正中。只在玻璃分類列生效時移
     * （玻璃不可用時原版分類貼圖還在，圖示要對齊原版）。分類的點擊範圍不動。
     */
    public static void tabIcon(net.minecraft.client.renderer.entity.RenderItem ri,
                               net.minecraft.item.ItemStack stack, int x, int y) {
        ri.renderItemAndEffectIntoGUI(stack, x, iconY(y));
    }

    public static void tabIconOverlay(net.minecraft.client.renderer.entity.RenderItem ri,
                                      net.minecraft.client.gui.FontRenderer fr,
                                      net.minecraft.item.ItemStack stack, int x, int y) {
        ri.renderItemOverlays(fr, stack, x, iconY(y));
    }

    private static int iconY(int y) {
        if (!dev.s1mp1e.glass.asm.BlitSuppressor.creativeArmed()) return y;
        return y < dev.s1mp1e.glass.asm.BlitSuppressor.panelTop() ? y - 3 : y + 3;
    }

    /** The tab whose vanilla hit box contains the cursor (matches what is clicked). */
    private static CreativeTabs tabUnderMouse(int gl, int gt, int xs, int ys, int mouseX, int mouseY) {
        int relX = mouseX - gl, relY = mouseY - gt;
        for (int i = 0; i < CreativeTabs.creativeTabArray.length; i++) {
            CreativeTabs tab = CreativeTabs.creativeTabArray[i];
            if (tab == null) continue;
            int col = tab.getTabColumn();
            int j = 28 * col;
            if (col == 5)     j = xs - 28 + 2;
            else if (col > 0) j += col;
            int k = tab.isTabInFirstRow() ? -32 : ys;
            if (relX >= j && relX <= j + 28 && relY >= k && relY <= k + 32) return tab;
        }
        return null;
    }

    private static CreativeTabs tabAt(int idx) {
        if (idx < 0 || idx >= CreativeTabs.creativeTabArray.length) return null;
        return CreativeTabs.creativeTabArray[idx];
    }

    private static int safeSelectedIndex(GuiContainerCreative screen) {
        try { return screen.getSelectedTabIndex(); } catch (Throwable t) { return -1; }
    }

    private static void ensureReflect() {
        if (reflectDone) return;
        reflectDone = true;
        fCurrentScroll = findField("field_147067_x", "currentScroll");
        fIsScrolling   = findField("field_147066_y", "isScrolling");
    }

    private static Field findField(String srg, String mcp) {
        String[] names = { srg, mcp };
        for (int i = 0; i < names.length; i++) {
            try {
                Field f = GuiContainerCreative.class.getDeclaredField(names[i]);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
