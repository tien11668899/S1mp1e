package dev.s1mp1e.client.gui;

import net.minecraft.client.gui.widget.ClickableWidget;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Feature D for the villager trade list: state holder that lets the 7 trade-button frames glide by the SAME eased
 * sub-pixel offset as the trade items, so frame and content glide as one (26.2's {@code MerchantGlide} +
 * {@code MerchantButtonGlideMixin}). The 1.16.5 trade buttons are plain {@code ButtonWidget}s
 * ({@code MerchantScreen$WidgetButtonPage}, 89x20, no label) drawn by {@code Screen.render} (inside
 * {@code HandledScreen.render}) BEFORE the trade items. {@code MerchantScrollGlassMixin} arms this holder at
 * {@code MerchantScreen.render} HEAD (only while the list is mid-glide) and disarms it at TAIL.
 *
 * <p>Two painters consume it: the glass path ({@code ButtonGlassMixin} — the trade buttons are 26.2's faint glass
 * capsules) paints each capsule through {@link #render} (slid by {@code -fracPx}, clipped to the trade window, plus
 * the ENTERING row under the bottom button so no gap opens at the window edge), and the vanilla fallback
 * ({@code MerchantTradeButtonMixin}, glass off) translates the vanilla sprite by {@link #fracPx()} inside the same
 * scissor.
 *
 * <p>Nothing here changes a widget's hit box: clicks still use the row-aligned logical layout (a mid-glide click snaps
 * to the target first).
 */
public final class MerchantGlide {

    private MerchantGlide() {}

    private static boolean active;
    private static float fracPx;
    private static int sx0, sy0, sx1, sy1;
    private static boolean extraRow;
    private static Object bottom;
    private static final Set<Object> buttons = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

    /** Arm for this frame: the trade buttons, the bottom one (the entering row paints under it), the eased fractional
     *  offset and the absolute trade-window scissor. */
    public static void begin(Iterable<? extends ClickableWidget> tradeButtons, Object bottomButton, float frac,
                             int x0, int y0, int x1, int y1, boolean drawExtraRow) {
        buttons.clear();
        for (ClickableWidget w : tradeButtons) buttons.add(w);
        bottom = bottomButton;
        fracPx = frac;
        sx0 = x0; sy0 = y0; sx1 = x1; sy1 = y1;
        extraRow = drawExtraRow;
        active = !buttons.isEmpty();
    }

    /** Disarm (every frame at MerchantScreen.render TAIL, so nothing leaks into another screen). */
    public static void end() {
        active = false;
        buttons.clear();
        bottom = null;
    }

    public static boolean isActive() { return active; }

    /** True when {@code w} is a trade button whose frame must glide this frame. */
    public static boolean handles(ClickableWidget w) { return active && buttons.contains(w); }

    public static float fracPx() { return fracPx; }
    public static int scissorX0() { return sx0; }
    public static int scissorY0() { return sy0; }
    public static int scissorX1() { return sx1; }
    public static int scissorY1() { return sy1; }

    /** Paints one trade-button frame shifted vertically by {@code dy} GUI px. */
    public interface Painter { void paint(float dy); }

    /** Paint {@code w}'s frame slid by the glide offset (clipped to the trade window), plus the entering row under the
     *  bottom button. The buttons draw at pose identity (Screen.render), so the window-px scissor takes absolute GUI
     *  coordinates. */
    public static void render(ClickableWidget w, Painter p) {
        GlassWidgets.beginScissor(sx0, sy0, sx1, sy1);
        try {
            p.paint(-fracPx);
            if (extraRow && w == bottom) p.paint(-fracPx + 20f);
        } finally {
            GlassWidgets.endScissor();
        }
    }
}
