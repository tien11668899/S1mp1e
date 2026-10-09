package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.gui.GlassWidgets;
import dev.s1mp1e.o.glass.render.GlassCorners;
import net.minecraft.client.gui.widget.ListWidget;

/**
 * allglass #4 — the list's selected row becomes a liquid-glass capsule (hotbar corner radius, lift .81) instead of
 * vanilla's grey outline + black inset. The {@code ListWidgetMixin} wraps the {@code isEntrySelected(j)} call inside
 * {@code ListWidget.renderList} (MCP {@code drawSelectionBox}): it hands us the REAL selected flag, we paint the
 * capsule for the genuinely-selected row, and the wrapper ALWAYS returns {@code false}, so vanilla's selection quad is
 * never built — which sidesteps the stale-{@code BufferBuilder} corruption that biting into a filled buffer would
 * cause (spec trap #2). The capsule is drawn here, just before {@code renderEntry(j)}, so the row's icon and text land
 * on top of it.
 *
 * <p>Geometry matches vanilla {@code renderList}: the caller passes {@code y = minY + 4 - scrollAmount} and the row top
 * is {@code k = y + index*entryHeight + headerHeight}; the quad spans {@code k-2 .. k+l+2} where {@code l =
 * entryHeight - 4}, across {@code minX + width/2 ± getRowWidth()/2}. All fields are reachable via the access widener
 * (minX/minY/entryHeight/headerHeight/scrollAmount) or public (getRowWidth).
 */
public final class SelectionGlassHook {

    private SelectionGlassHook() {}

    /** Paint the glass capsule for row {@code index} when {@code sel} is true; the caller suppresses the vanilla quad. */
    public static void paint(ListWidget slot, int index, boolean sel) {
        if (!sel || slot == null) return;
        try {
            int entryHeight = slot.entryHeight;
            int headerHeight = slot.headerHeight;
            int listWidth = slot.getRowWidth();
            float amount = slot.scrollAmount;

            int insideTop = slot.minY + 4 - (int) amount;         // vanilla: l = minY + 4 - scrollAmount
            int k = insideTop + index * entryHeight + headerHeight; // row top
            int l = entryHeight - 4;                                // row inner height
            int i1 = slot.minX + (slot.width / 2 - listWidth / 2);
            int j1 = slot.minX + slot.width / 2 + listWidth / 2;

            float x0 = i1, y0 = k - 2, x1 = j1, y1 = k + l + 2;
            GlassWidgets.capsule(x0, y0, x1, y1,
                    GlassCorners.cornerKnob(x1 - x0, y1 - y0), 0.81f, 1.0f, true);
        } catch (Throwable ignored) {
            // leave the row un-highlighted rather than crash the list; the vanilla quad stays suppressed either way
        }
    }
}
