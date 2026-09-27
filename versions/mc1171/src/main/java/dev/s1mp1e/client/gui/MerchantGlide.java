package dev.s1mp1e.client.gui;

import net.minecraft.client.gui.widget.ClickableWidget;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Feature D for the villager trade list: slides the 7 trade-button frames by the SAME eased sub-pixel offset as the trade
 * items, so frame and content glide as one (26.2's {@code MerchantGlide} + {@code MerchantButtonGlideMixin}). The 1.17.1
 * trade buttons are plain {@code ButtonWidget}s ({@code MerchantScreen$WidgetButtonPage}, 89×20, no label) drawn by
 * {@code Screen.render} (inside {@code HandledScreen.render}) BEFORE the trade items. {@code MerchantScrollGlassMixin}
 * arms this holder at {@code MerchantScreen.render} HEAD (only while the list is mid-glide) and disarms it at TAIL, and
 * {@code ButtonGlassMixin} routes the registered buttons through {@link #render}.
 *
 * <p>During a glide each button frame is painted translated up by the fractional offset inside the trade-window scissor,
 * and the BOTTOM button paints one extra frame a row lower — the row entering from below (vanilla has no widget for
 * it). Nothing here changes a widget's hit box: clicks still use the row-aligned logical layout (a mid-glide click snaps
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

    /** Arm for this frame: the trade buttons, the eased fractional offset and the absolute trade-window scissor. */
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

    /** True while the trade list is mid-glide this frame (armed at MerchantScreen.render HEAD, disarmed at TAIL). */
    public static boolean isActive() { return active; }

    /** True when {@code w} is a trade button whose frame must glide this frame. */
    public static boolean handles(ClickableWidget w) {
        return active && buttons.contains(w);
    }

    /** Paints one trade-button frame shifted vertically by {@code dy} GUI px. */
    public interface Painter { void paint(float dy); }

    /** Paint {@code w}'s frame slid by the glide offset (clipped to the trade window), plus the entering row under the
     *  bottom button. Buttons draw at pose identity (Screen.render), so the scissor takes absolute GUI coordinates. */
    public static void render(ClickableWidget w, Painter p) {
        GuiScissor.enable(sx0, sy0, sx1, sy1);
        try {
            p.paint(-fracPx);
            if (extraRow && w == bottom) p.paint(-fracPx + 20f);
        } finally {
            GuiScissor.disable();
        }
    }
}
