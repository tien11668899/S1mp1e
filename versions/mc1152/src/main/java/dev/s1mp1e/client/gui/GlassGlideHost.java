package dev.s1mp1e.client.gui;

import net.minecraft.container.Slot;

/**
 * Duck interface implemented by a container screen's glass mixin so the shared container-glass mixin
 * ({@code HandledScreenGlassMixin}) can drive feature (D) — silky sub-pixel content glide — without knowing the
 * concrete screen type. The 1.15.2 (FF-Fabric, immediate-mode) counterpart of 1.20.1's
 * {@code dev.s1mp1e.client.gui.GlassGlideHost}.
 *
 * <p>Only {@code CreativeInventoryScreen} implements it in this version (its 45-slot fixed grid). While it reports
 * {@link #s1mp1e$gliding()} the shared mixin suppresses vanilla's own drawing of the grid slots
 * ({@link #s1mp1e$isGlideSlot}), nulls the hovered grid slot, and calls {@link #s1mp1e$drawGlideOverlay()} right before
 * {@code drawForeground} — inside the same {@code translate(x,y)} pose vanilla drew the slots in, so the overlay's
 * slot-relative item draws land where the grid does (the scissor, in absolute window coords, is the mirror of that
 * translate; see {@code CreativeGlassMixin}).
 *
 * <p>Unlike 1.20.1 there is no {@code DrawContext}: the overlay draws immediately through the item renderer, so the
 * method takes no argument.
 */
public interface GlassGlideHost {

    /** True this frame while the eased content offset differs from the row-aligned logical scroll (mid-glide). */
    boolean s1mp1e$gliding();

    /** True when {@code slot} belongs to the scrolling grid this host glides (so vanilla's own draw is suppressed). */
    boolean s1mp1e$isGlideSlot(Slot slot);

    /** Draw the eased grid content translated by the fractional offset, scissored to the grid window (+1 extra row). */
    void s1mp1e$drawGlideOverlay();
}
