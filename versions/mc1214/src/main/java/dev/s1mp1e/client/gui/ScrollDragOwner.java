package dev.s1mp1e.client.gui;

/**
 * Duck interface for the list screens whose vanilla scrollbar-drag flag is only cleared by the NEXT click (stonecutter
 * {@code mouseClicked}, loom {@code scrollbarClicked}, merchant {@code scrolling}) — harmless in vanilla, where drag
 * events stop with the button, but the glass scrollbar (C) reads that flag as "held": without a release hook the lens
 * would stay lifted and keep tracking the pointer after the button is let go. {@code HandledScreenGlassMixin} calls
 * {@link #s1mp1e$endScrollDrag()} on {@code mouseReleased}, exactly like the creative screen's own
 * {@code scrolling = false} on release. Behaviour-neutral for vanilla's scroll logic.
 */
public interface ScrollDragOwner {
    void s1mp1e$endScrollDrag();
}
