package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.gui.GlassWidgets;
import net.minecraft.client.render.TextRenderer;

import java.util.List;

/**
 * allglass #15 — the F3 debug overlay: one rounded 0x60 scrim plate per group of consecutive lines (a blank line splits
 * the groups), text on top — instead of vanilla's grey strip per line.
 *
 * <p><b>Ornithe note (vs Forge):</b> on Forge 1.8.9 the F3 lists are drawn by {@code GuiIngameForge.renderHUDText}; on
 * Ornithe the game is vanilla, so {@code DebugOverlay.drawGameInfo()} / {@code drawSystemInfo(Window)} draw the lists
 * themselves — a {@code GuiElement.fill(...)} per non-empty line then the text. We do NOT copy Forge's per-line
 * drawRect-redirect approach (overlapping translucent strips leave a darker seam where two lines meet); instead the
 * {@code DebugOverlayMixin} cancels each draw method at HEAD and calls {@link #draw} with that method's list, so we lay
 * out the groups with vanilla's exact maths ({@code top = 2 + fontHeight*i}, skipping empty lines but still advancing
 * {@code i}), paint ONE rounded plate per run of consecutive non-empty lines, then draw each line's text on top (no
 * shadow — global rule, and vanilla's F3 has none either).
 */
public final class DebugCardHook {

    private DebugCardHook() {}

    /** 0x60 black scrim (spec #15). */
    private static final int SCRIM = 0x60000000;
    private static final float RADIUS = 3.0f;
    /** Vanilla F3 text colour. */
    private static final int TEXT = 0xE0E0E0;

    /**
     * Paint the glass plates and text for one debug list and report whether we handled it (always true unless the
     * renderer is unavailable, in which case the caller lets vanilla draw).
     *
     * @param font      the debug overlay's text renderer
     * @param list      the lines (may contain null / empty entries that create vertical gaps)
     * @param rightSide true for the right (system) list — anchored to the screen's right edge
     * @param screenW   scaled screen width (only used for the right list)
     */
    public static boolean draw(TextRenderer font, List<String> list, boolean rightSide, int screenW) {
        if (font == null || list == null) return false;
        try {
            int fh = font.fontHeight;

            // Pass 1 — the plates (so the text sits on top). A run of consecutive non-empty lines = one plate.
            int gx0 = 0, gy0 = 0, gx1 = 0, gy1 = 0;
            boolean open = false;
            for (int i = 0; i < list.size(); i++) {
                String msg = list.get(i);
                boolean empty = msg == null || msg.isEmpty();
                if (empty) {
                    if (open) { GlassWidgets.fillRound(gx0, gy0, gx1, gy1, SCRIM, RADIUS); open = false; }
                    continue;
                }
                int w = font.getWidth(msg);
                int top = 2 + fh * i;
                int x0, x1;
                if (rightSide) { int l = screenW - 2 - w; x0 = l - 1; x1 = l + w + 1; }
                else { x0 = 1; x1 = 2 + w + 1; }
                int y0 = top - 1, y1 = top + fh - 1;
                if (!open) { gx0 = x0; gy0 = y0; gx1 = x1; gy1 = y1; open = true; }
                else { gx0 = Math.min(gx0, x0); gx1 = Math.max(gx1, x1); gy1 = y1; }
            }
            if (open) GlassWidgets.fillRound(gx0, gy0, gx1, gy1, SCRIM, RADIUS);

            // Pass 2 — the text, exactly where vanilla would put it.
            for (int i = 0; i < list.size(); i++) {
                String msg = list.get(i);
                if (msg == null || msg.isEmpty()) continue;
                int top = 2 + fh * i;
                int x = rightSide ? screenW - 2 - font.getWidth(msg) : 2;
                font.draw(msg, x, top, TEXT);
            }
            return true;
        } catch (Throwable t) {
            return false;   // let vanilla draw its own grey strips
        }
    }
}
