package dev.s1mp1e.glass.asm;

/**
 * Drops a container's panel-texture blits WITHOUT losing everything else its
 * background layer draws.
 *
 * <p>Skipping the whole {@code drawGuiContainerBackgroundLayer} was wrong: in
 * 1.8.9 that method paints the panel texture <em>and</em> the things layered on
 * it — {@code GuiInventory} renders the player model there, the furnace its
 * fire and arrow, and so on. Cancelling the call took all of them with it.
 *
 * <p>So instead the hook arms this suppressor with the panel rectangle and lets
 * vanilla run. {@link #consume} is called from the head of every
 * {@code Gui.drawTexturedModalRect}; it returns {@code true} — meaning "skip
 * this blit" — for any full-width strip that starts at the panel's left edge and
 * lies inside the panel band. That covers both {@code GuiChest} halves, the
 * creative item panel, {@code GuiInventory}, the furnace, beacon, anvil, horse
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
    /** Feature (B): while armed, the creative 28x32 tab sprites are dropped and their geometry handed to
     *  {@link dev.s1mp1e.glass.render.GlassCreativeTabs}, which paints the fused-band pills instead. */
    private static boolean creativeTabs;

    private BlitSuppressor() {}

    /** Arm suppression for the panel band [gl, gt] .. [gl+xs, gt+ys]. */
    public static void arm(int guiLeft, int guiTop, int xSize, int ySize) {
        armed = true;
        gl = guiLeft;
        gt = guiTop;
        xs = xSize;
        ys = ySize;
    }

    /** Also drop the creative tab sprites this frame (feature B), recording each for the pill painter. */
    public static void armCreativeTabs() { creativeTabs = true; }

    /** Clear the panel-band latch — always call after the vanilla layer returns. */
    public static void disarm() { armed = false; creativeTabs = false; }

    /** 創造模式的玻璃分類列正在生效（背景層、玻璃可用、分類貼圖已被抑制）。 */
    public static boolean creativeArmed() { return creativeTabs && armed; }

    /** 目前武裝中的面板頂邊（guiTop）。 */
    public static int panelTop() { return gt; }

    /** Drop EVERY blit until {@link #endSuppressAll}. */
    public static void beginSuppressAll() { suppressAll = true; }

    /** Stop the drop-everything mode. */
    public static void endSuppressAll() { suppressAll = false; }

    /**
     * True when the given {@code drawTexturedModalRect(x,y,u,v,w,h)} should be
     * skipped: any full-width strip that starts at the panel's left edge and
     * lies inside the panel band, or anything at all while suppress-all is on.
     */
    public static boolean consume(int x, int y, int u, int v, int w, int h) {
        if (suppressAll) return true;
        if (!armed) return false;
        // Feature (B): the creative tab sprites are a fixed 28x32; drop them and record each (x, y, and
        // the source-v that encodes selected/row) so GlassCreativeTabs can slide the pills on the sheet.
        if (creativeTabs && w == 28 && h == 32) {
            dev.s1mp1e.glass.render.GlassCreativeTabs.record(x, y, v);
            return true;
        }
        if (x == gl && w == xs && y >= gt && y < gt + ys) return true;
        // A kept blit of the container layer (furnace flame / arrow, brewing bubbles, anvil cross ...): draw it from
        // a copy of the container texture whose opaque panel-grey background is transparent, so it sits on the glass
        // without a grey box (ContainerExtras).
        dev.s1mp1e.glass.render.ContainerExtras.rebindKeyed();
        return false;
    }
}
