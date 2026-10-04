package dev.s1mp1e.glass.render;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;

import java.util.ArrayList;

/**
 * Feature (B) — the creative category tabs fused into the inventory's ONE glass sheet
 * (user: "分類跟背包連在一起, 中間不要斷層, 不要是好幾個方塊", and "分類的框框要像 hotbar 滑過去,
 * 中間過渡就像滑鼠懸停在背包上面的框框淡入淡出一樣").
 *
 * <p>The creative body glass is extended by {@link #BAND} GUI px above and below the panel
 * (see {@code GlassContainerHandler#onBackgroundDrawn}), so each tab row is simply the top / bottom band of the same
 * sheet — no separate tiles, no seam. This class draws the moving pills on that sheet: a lifted glass
 * pill on the SELECTED tab that slides like the hotbar selector within a row (two springs, lead
 * &omega;&nbsp;55 / trail &omega;&nbsp;30, &zeta;&nbsp;1) and cross-fades when the selection jumps to
 * the other row or to another tab page; a fainter pill under the HOVERED tab with the container
 * slot-hover glide (same two springs + a 100/150&nbsp;ms fade). Vanilla's tab icons are drawn on top
 * unchanged, and vanilla's own 28&times;28 hit boxes are kept — so what is drawn is what is clicked.
 *
 * <h3>No {@code CreativeTabs} reflection</h3>
 * 1.12.2 lays tabs out in {@code drawTab} at {@code (l, i1)} then blits a 28&times;32 sprite whose source
 * {@code v} encodes the state (0/64 = unselected top/bottom, 32/96 = selected top/bottom). The coremod
 * already suppresses those sprites through {@link dev.s1mp1e.glass.asm.BlitSuppressor}; each suppressed
 * blit hands us {@link #record its (x, y, v)}, so the pill geometry, the selected flag and the row all
 * come straight from vanilla's own draw — no obfuscation-fragile field/method reflection, and a tab that
 * is off the current page (paginated tabs) simply isn't recorded, so its pill cross-fades out naturally.
 *
 * <p>The blits are recorded during {@code drawGuiContainerBackgroundLayer}, which runs just AFTER the
 * {@code BackgroundDrawnEvent} where {@link #frame} draws the pills, so the pills follow the tab
 * positions with a one-frame lag — invisible, because the positions are stable and the pill animates
 * across any change anyway.
 */
public final class GlassCreativeTabs {

    private GlassCreativeTabs() {}

    /** Row depth outside the panel edge, GUI px (vanilla's top tab sprite starts 28 px above the panel). */
    public static final int BAND = 28;
    private static final int TAB_W = 28, TAB_H = 32, PILL_INSET = 2;
    private static final float LIFT_SELECTED = 0.122f;   // 1 - 0xE0/255, the hotbar selected pill's lift
    private static final float LIFT_HOVER    = 0.043f;   // 1 - 0xF4/255, fainter
    private static final float PAD = 8f, FROST = GlassRenderer.FROST_NONE;

    // ---- captured tab rects: {x, y, selected(0/1), top(0/1)} --------------------------------------
    private static ArrayList<int[]> building = new ArrayList<int[]>();
    private static ArrayList<int[]> shown    = new ArrayList<int[]>();

    /** One suppressed 28x32 tab-sprite blit. {@code v} = the sprite's source-v (state), {@code x,y} its top-left. */
    public static void record(int x, int y, int v) {
        if (building.size() >= 64) return;
        boolean selected = (v == 32 || v == 96);
        boolean top = v < 64;
        building.add(new int[] { x, y, selected ? 1 : 0, top ? 1 : 0 });
    }

    // ---- motion -----------------------------------------------------------------------------------
    private static final float LEAD_W = Spring.OMEGA_SNAP;   // 55
    private static final float TRAIL_W = Spring.OMEGA_MED;   // 30
    private static Object owner;
    private static long lastNanos;
    private static Spring selLead, selTrail;
    private static float  selCy;
    private static int    selCol = Integer.MIN_VALUE;   // last selected pill centre-x, as a stability key
    private static boolean selTop;
    private static final Fade selFade = new Fade(1f, 100f);
    private static float ghostCx, ghostCy, ghostHw;
    private static final Fade ghostFade = new Fade(0f, 150f);
    private static Spring hx1, hx2, hy1, hy2;
    private static boolean hoverActive;
    private static final Fade hoverFade = new Fade(0f, 100f);

    // DEV-ONLY (DevShot): a virtual cursor for the hover test so the hover-pill glide is verifiable in a
    // still shot. -1 = use the real event mouse. Never set outside DevShot, so it is inert in production.
    private static int devHoverX = -1, devHoverY = -1;
    public static void devHover(int x, int y) { devHoverX = x; devHoverY = y; }
    public static void devClearHover() { devHoverX = -1; devHoverY = -1; }

    /**
     * Animate + draw the selected and hover pills for the fused band, then promote this-frame's captured
     * rects. Called from {@code GlassContainerHandler.onBackgroundDrawn} for a {@code GuiContainerCreative}
     * (before the vanilla tab icons), so the pills sit under the icons and above the body sheet.
     */
    public static void frame(Object screen, int mouseX, int mouseY, float fade) {
        // promote last frame's records; start a fresh list for this frame's tab draws
        ArrayList<int[]> tabs = building;
        building = new ArrayList<int[]>();
        shown = tabs;

        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 1f / 60f : Math.min(0.1f, (now - lastNanos) * 1.0e-9f);
        lastNanos = now;

        if (screen != owner) {
            owner = screen;
            selLead = null; hx1 = null; hoverActive = false;
            hoverFade.snap(0f); ghostFade.snap(0f); selFade.snap(1f);
            selCol = Integer.MIN_VALUE;
        }

        int mx = devHoverX >= 0 ? devHoverX : mouseX;
        int my = devHoverX >= 0 ? devHoverY : mouseY;
        int[] sel = null, hov = null;
        for (int i = 0; i < tabs.size(); i++) {
            int[] t = tabs.get(i);
            if (t[2] != 0) sel = t;
            else if (hov == null && mx >= t[0] && mx < t[0] + TAB_W
                     && my >= t[1] && my < t[1] + TAB_H) hov = t;
        }

        // 正方形 pill，置於「分類格（28 寬）× 玻璃帶（BAND = 28 高）」正中、四邊各內縮 PILL_INSET——
        // 26.2／1.21.1 GlassTabs 的規則（hw = 格寬/2 − 內距、hh = BAND/2 − 內距）。2026-10-04 使用者要求：原本用原版
        // 分類貼圖 28×32 的矩形內縮，是 24×28 的直長形，中心也偏了 2 px（貼圖有 4 px 伸進面板）。
        float hw = BAND / 2f - PILL_INSET;   // 12
        float hh = hw;                       // 12 → 24×24

        // ---- selected pill: hotbar slide within a row, cross-fade across rows / pages ----
        if (sel != null) {
            float cx = sel[0] + TAB_W / 2f;
            float cy = bandCy(sel);
            boolean top = sel[3] != 0;
            int col = Math.round(cx);
            if (selLead == null) {
                selLead = new Spring(cx, LEAD_W, 1f);
                selTrail = new Spring(cx, TRAIL_W, 1f);
                selCol = col; selTop = top; selCy = cy; selFade.snap(1f);
            } else if (top != selTop) {
                // row (or page) switch: fade the old pill out where it stood, snap the new one in and fade it up
                ghostCx = (selLead.value() + selTrail.value()) / 2f;
                ghostCy = selCy; ghostHw = hw;
                ghostFade.snap(selFade.value()); ghostFade.to(0f, 150f);
                selLead.snap(cx); selTrail.snap(cx); selCy = cy;
                selFade.snap(0f); selFade.to(1f, 100f);
                selTop = top; selCol = col;
            } else if (col != selCol) {
                selLead.setTarget(cx); selTrail.setTarget(cx); selCol = col; selCy = cy;
            }
        }

        // ---- hover pill: the slot-hover box motion (two springs on x AND y + fade) ----
        if (hov != null) {
            float cx = hov[0] + TAB_W / 2f;
            float cy = bandCy(hov);
            if (hoverActive && hx1 != null) {
                hx1.setTarget(cx); hx2.setTarget(cx); hy1.setTarget(cy); hy2.setTarget(cy);
            } else if (hx1 != null && hoverFade.value() > 0.05f) {
                hx1.setTarget(cx); hx2.setTarget(cx); hy1.setTarget(cy); hy2.setTarget(cy);
            } else {
                hx1 = new Spring(cx, LEAD_W, 1f); hx2 = new Spring(cx, TRAIL_W, 1f);
                hy1 = new Spring(cy, LEAD_W, 1f); hy2 = new Spring(cy, TRAIL_W, 1f);
            }
            hoverActive = true;
            hoverFade.to(1f, 100f);
        } else {
            hoverActive = false;
            hoverFade.to(0f, 150f);
        }

        // sub-stepped integration (1/120 s) — Spring.advance guards + sub-steps for us
        if (selLead != null) { selLead.advance(dt); selTrail.advance(dt); }
        if (hx1 != null) { hx1.advance(dt); hx2.advance(dt); hy1.advance(dt); hy2.advance(dt); }

        // ---- paint (ghost, then hover, then selected — same order as 26.2) ----
        if (ghostFade.isVisible()) {
            pill(ghostCx - ghostHw, ghostCy - hh, ghostCx + ghostHw, ghostCy + hh, LIFT_SELECTED, fade * ghostFade.value());
        }
        if (hx1 != null && hoverFade.isVisible()) {
            float lo = Math.min(hx1.value(), hx2.value()), hi = Math.max(hx1.value(), hx2.value());
            float vlo = Math.min(hy1.value(), hy2.value()), vhi = Math.max(hy1.value(), hy2.value());
            pill(lo - hw, vlo - hh, hi + hw, vhi + hh, LIFT_HOVER, fade * hoverFade.value());
        }
        if (sel != null && selLead != null) {
            float lo = Math.min(selLead.value(), selTrail.value()), hi = Math.max(selLead.value(), selTrail.value());
            pill(lo - hw, selCy - hh, hi + hw, selCy + hh, LIFT_SELECTED, fade * selFade.value());
        }
    }

    /** Reset the captured set (glass pipeline down: the caller falls back to vanilla tab sprites). */
    public static void reset() {
        building = new ArrayList<int[]>();
    }

    private static void pill(float x0, float y0, float x1, float y1, float lift, float alpha) {
        if (x1 - x0 < 1f || y1 - y0 < 1f || alpha <= 0.004f) return;
        float corner = GlassCorners.hotbarCorner(x1 - x0, y1 - y0);
        GlassRenderer.glass(x0, y0, x1, y1, PAD, corner, lift, alpha, FROST);
    }

    /**
     * 分類在玻璃帶裡的中心 y。記錄的是原版貼圖的 y：上排貼圖從面板頂邊上方 28 開始（= 玻璃帶頂），下排從面板底邊上方 4
     * 開始（貼圖有 4 px 伸進面板），所以玻璃帶中心分別是 y + BAND/2 與 y + 4 + BAND/2。
     */
    private static float bandCy(int[] t) {
        return t[3] != 0 ? t[1] + BAND / 2f : t[1] + 4 + BAND / 2f;
    }

    /**
     * 原版的分類圖示位置（drawTab）：上排 y = 頂邊 − 19（中心 −11）、下排 y = 底邊 + 3（中心 +11）；玻璃帶中心是
     * −14／+14，所以上排往上 3、下排往下 3，圖示就在正方形 pill 正中。只在玻璃分類列生效時移（玻璃不可用時原版
     * 分類貼圖還在，圖示要對齊原版）。分類的點擊範圍不動。
     */
    public static void tabIcon(net.minecraft.client.renderer.RenderItem ri, net.minecraft.item.ItemStack stack, int x, int y) {
        ri.renderItemAndEffectIntoGUI(stack, x, iconY(y));
    }

    public static void tabIconOverlay(net.minecraft.client.renderer.RenderItem ri, net.minecraft.client.gui.FontRenderer fr,
                                      net.minecraft.item.ItemStack stack, int x, int y) {
        ri.renderItemOverlays(fr, stack, x, iconY(y));
    }

    private static int iconY(int y) {
        if (!dev.s1mp1e.glass.asm.BlitSuppressor.creativeArmed()) return y;
        return y < dev.s1mp1e.glass.asm.BlitSuppressor.panelTop() ? y - 3 : y + 3;
    }
}
