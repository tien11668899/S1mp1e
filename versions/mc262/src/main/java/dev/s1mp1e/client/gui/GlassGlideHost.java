package dev.s1mp1e.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.Slot;

/**
 * Duck interface a container screen implements so the shared {@code AbstractContainerScreen} glass mixin can hand it
 * the slot-level hooks that make a fixed-slot grid (the creative item grid) glide sub-pixel like the S1mp1e config
 * menu instead of stepping by whole rows.
 *
 * <p>The creative item grid is 45 real menu {@link Slot}s at fixed positions whose CONTENT vanilla remaps by whole
 * rows on scroll. To glide, the screen keeps the vanilla logical scroll row-aligned (so clicks / hit-testing stay
 * correct) but, while the eased scrollbar offset differs from that logical row, draws the visible item stacks itself
 * translated by the fractional offset and asks the shared mixin to (a) skip vanilla's own drawing of those slots and
 * (b) suppress the mismatched slot highlight / tooltip for the frame. Everything is inert unless the implementing
 * screen reports {@link #liquidglass$gliding()} — non-creative containers never implement this interface, so the
 * shared mixin is a no-op for them.
 */
public interface GlassGlideHost {

    /** True this frame when a sub-pixel glide overlay is active and vanilla's own grid drawing must be suppressed. */
    boolean liquidglass$gliding();

    /** True if {@code slot} is one of the scrolling grid slots whose vanilla drawing the glide overlay replaces. */
    boolean liquidglass$isGlideSlot(Slot slot);

    /**
     * Draw the sub-pixel glide overlay (the visible item stacks at their gliding positions). Called once per frame at
     * the tail of {@code extractSlots} — i.e. inside the {@code leftPos/topPos}-translated matrix, right where vanilla
     * draws the slots — so the overlay uses slot-relative coordinates exactly like the slots it replaces.
     */
    void liquidglass$drawGlideOverlay(GuiGraphicsExtractor g);
}
