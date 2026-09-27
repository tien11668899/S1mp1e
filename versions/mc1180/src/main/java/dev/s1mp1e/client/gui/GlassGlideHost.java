package dev.s1mp1e.client.gui;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.slot.Slot;

/**
 * Duck interface a container screen implements so the shared {@code HandledScreen} glass mixin can hand it the
 * slot-level hooks that make a fixed-slot grid (the creative item grid) glide sub-pixel like the S1mp1e config menu
 * instead of stepping by whole rows. 1.18.2 port of the 26.2 {@code GlassGlideHost} onto this version's
 * {@code HandledScreen.drawSlot} loop.
 *
 * <p>The creative item grid is 45 real menu {@link Slot}s at fixed positions whose CONTENT vanilla remaps by whole rows
 * on scroll ({@code CreativeScreenHandler.scrollItems}). To glide, the screen keeps the vanilla logical scroll
 * row-aligned (so clicks / hit-testing stay correct) but, while the eased scrollbar offset differs from that logical
 * row, draws the visible item stacks itself translated by the fractional offset and asks the shared mixin to skip
 * vanilla's own drawing of those slots. Everything is inert unless the implementing screen reports
 * {@link #s1mp1e$gliding()} — non-creative containers never implement this interface, so the shared mixin is a no-op
 * for them.
 */
public interface GlassGlideHost {

    /** True this frame when a sub-pixel glide overlay is active and vanilla's own grid drawing must be suppressed. */
    boolean s1mp1e$gliding();

    /** True if {@code slot} is one of the scrolling grid slots whose vanilla drawing the glide overlay replaces. */
    boolean s1mp1e$isGlideSlot(Slot slot);

    /**
     * Draw the sub-pixel glide overlay (the visible item stacks at their gliding positions). Called once per frame at
     * the tail of the {@code HandledScreen.render} slot loop — i.e. inside the {@code leftPos/topPos}-translated
     * model-view matrix, right where vanilla draws the slots — so the overlay uses slot-relative coordinates exactly
     * like the slots it replaces.
     */
    void s1mp1e$drawGlideOverlay(MatrixStack matrices);
}
