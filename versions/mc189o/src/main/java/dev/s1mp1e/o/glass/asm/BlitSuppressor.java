package dev.s1mp1e.o.glass.asm;

/**
 * Drops a container's panel-texture blits WITHOUT losing everything else its
 * background layer draws.
 *
 * <p>Skipping the whole {@code drawGuiContainerBackgroundLayer} was wrong: in
 * 1.8.9 that method paints the panel texture <em>and</em> the things layered on
 * it — {@code SurvivalInventoryScreen} renders the player model there, the furnace its
 * fire and arrow, and so on. Cancelling the call took all of them with it.
 *
 * <p>So instead the hook arms this suppressor with the panel rectangle and lets
 * vanilla run. {@link #consume} is called from the head of every
 * {@code GuiElement.drawTexture}; it returns {@code true} — meaning "skip
 * this blit" — for any full-width strip that starts at the panel's left edge and
 * lies inside the panel band. That covers both {@code ChestScreen} halves, the
 * creative item panel, {@code SurvivalInventoryScreen}, the furnace, beacon, anvil, horse
 * and friends, while it keeps the creative tabs (width 28), the scrollbar (width
 * 12), the furnace flame/arrow, the enchant rows (width 108) and the anvil text
 * box. It is <em>not</em> one-shot: a screen may draw several panel strips.
 *
 * <p>{@link #beginSuppressAll}/{@link #endSuppressAll} is a stronger mode used by
 * the slider skin (skin-4) to drop the vanilla knob blits while it repaints the
 * glass knob.
 */
public final class BlitSuppressor {

    private static boolean armed;
    private static int gl, gt, xs, ys;
    private static boolean suppressAll;
    /** Creative chrome mode: drop the tab background sprites (28x32) and the
     *  scrollbar thumb (12x15) while keeping tab icons + everything else. */
    private static boolean creativeChrome;

    private BlitSuppressor() {}

    /** Arm suppression for the panel band [gl, gt] .. [gl+xs, gt+ys]. */
    public static void arm(int guiLeft, int guiTop, int xSize, int ySize) {
        armed = true;
        gl = guiLeft;
        gt = guiTop;
        xs = xSize;
        ys = ySize;
    }

    /** Clear the panel-band latch — always call after the vanilla layer returns. */
    public static void disarm() { armed = false; }

    /** Drop EVERY blit until {@link #endSuppressAll}. */
    public static void beginSuppressAll() { suppressAll = true; }

    /** Stop the drop-everything mode. */
    public static void endSuppressAll() { suppressAll = false; }

    /** Arm creative-chrome suppression (tab sprites + scrollbar thumb). */
    public static void armCreative() { creativeChrome = true; }

    /** Disarm creative-chrome suppression. */
    public static void disarmCreative() { creativeChrome = false; }

    /** 創造模式的玻璃分類列正在生效（背景層、玻璃可用、貼圖已被抑制）。 */
    public static boolean creativeArmed() { return creativeChrome && armed; }

    /** 目前武裝中的面板頂邊（guiTop）。 */
    public static int panelTop() { return gt; }

    /**
     * True when the given {@code drawTexture(x,y,u,v,w,h)} should be
     * skipped: any full-width strip that starts at the panel's left edge and
     * lies inside the panel band, or anything at all while suppress-all is on.
     */
    public static boolean consume(int x, int y, int u, int v, int w, int h) {
        if (suppressAll) return true;
        // Creative chrome: the tab background sprite is 28x32, the scrollbar thumb
        // is 12x15. Tab ICONS go through itemRender (not this blit), so they survive.
        if (creativeChrome && ((w == 28 && h == 32) || (w == 12 && h == 15))) return true;
        if (!armed) return false;
        if (x == gl && w == xs && y >= gt && y < gt + ys) return true;
        // 第 8 組：容器背景層裡「留下來」的 blit（熔爐火焰／箭頭、釀造台泡泡、鐵砧叉叉…）改從一份把不透明灰色底
        // 變透明的材質副本畫，才不會在玻璃上變成灰色方塊（ContainerExtras）。
        dev.s1mp1e.o.glass.render.ContainerExtras.rebindKeyed();
        return false;
    }
}
