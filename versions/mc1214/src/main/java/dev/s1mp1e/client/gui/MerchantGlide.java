package dev.s1mp1e.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Feature D for the villager trade list: slides the 7 trade-button frames by the SAME eased sub-pixel offset as the trade
 * items, so frame and content glide as one (26.2's {@code MerchantGlide} + {@code MerchantButtonGlideMixin}). The 1.21.1
 * trade buttons are plain vanilla {@code ButtonWidget}s ({@code MerchantScreen$WidgetButtonPage}, 88×20, no label), drawn
 * by {@code ClickableWidget.render → renderWidget} BEFORE the trade items; {@code MerchantGlassMixin} arms this holder at
 * {@code MerchantScreen.render} HEAD (only while the list is mid-glide) and disarms it at TAIL, and
 * {@code ButtonGlassMixin}'s {@code renderWidget} redirect routes the registered buttons through {@link #render}.
 *
 * <p>During a glide each button is drawn translated up by the fractional offset inside the trade-window scissor, and the
 * BOTTOM button draws one extra frame a row lower — the row entering from below (vanilla has no widget for it). Nothing
 * here changes a widget's hit box: clicks still use the row-aligned logical layout (a mid-glide click snaps first).
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

    /** True when {@code w} is a trade button whose frame must glide this frame. */
    public static boolean handles(ClickableWidget w) {
        return active && buttons.contains(w);
    }

    /** Draws one trade-button frame shifted vertically by {@code dy} GUI px. */
    public interface Painter { void paint(float dy); }

    /** Draw {@code w}'s frame slid by the glide offset (clipped to the trade window), plus the entering row under the
     *  bottom button. {@code p} paints the frame at a vertical offset (glass capsule, or the vanilla sprite through a
     *  context translate). */
    public static void render(ClickableWidget w, DrawContext ctx, Painter p) {
        ctx.enableScissor(sx0, sy0, sx1, sy1);
        try {
            p.paint(-fracPx);
            if (extraRow && w == bottom) p.paint(-fracPx + 20f);
        } finally {
            ctx.disableScissor();
        }
    }
}
