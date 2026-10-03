package dev.s1mp1e.client.gui;

/**
 * A container screen whose vanilla scrollbar-drag flag ({@code mouseClicked} / {@code scrollbarClicked} /
 * {@code scrolling}) is never cleared on release (those 1.13.2 screens do not override {@code mouseReleased}). The
 * shared {@code HandledScreenGlassMixin} clears it from {@code HandledScreen.mouseReleased} so the held glass lens drops
 * and the thumb stops tracking the cursor once the button is let go.
 */
public interface ScrollDragOwner {
    void s1mp1e$endScrollDrag();
}
