package dev.s1mp1e.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.screen.slot.Slot;

/**
 * Duck interface a container screen implements so the shared {@code HandledScreen} glass mixin can hand it the slot-level
 * hooks that make a fixed-slot grid (the creative item grid) glide sub-pixel like the S1mp1e config menu instead of
 * stepping by whole rows (feature D). The 1.21.1 counterpart of 26.2's {@code GlassGlideHost} — the slot loop is inlined
 * in {@code HandledScreen.render} here, so the shared mixin drives these from there.
 *
 * <p>Everything is inert unless the implementing screen reports {@link #s1mp1e$gliding()} — non-creative containers never
 * implement this interface, so the shared mixin is a strict no-op for them.
 */
public interface GlassGlideHost {

    /** True this frame when a sub-pixel glide overlay is active and vanilla's own grid drawing must be suppressed. */
    boolean s1mp1e$gliding();

    /** True if {@code slot} is one of the scrolling grid slots whose vanilla drawing the glide overlay replaces. */
    boolean s1mp1e$isGlideSlot(Slot slot);

    /**
     * Draw the sub-pixel glide overlay (the visible item stacks at their gliding positions). Called once per frame from
     * {@code HandledScreen.render} just before {@code drawForeground} — i.e. inside the {@code (x,y,0)}-translated matrix,
     * right where vanilla drew the slots — so the overlay uses slot-relative coordinates exactly like the slots it
     * replaces. (In 1.21.1 {@code DrawContext.enableScissor} ignores the pose — verified in the decompiled
     * {@code enableScissor}: it pushes the raw {@code ScreenRect} — so the overlay passes ABSOLUTE scissor coords while the
     * item draws stay slot-relative: the mirror of 26.2's double-translate trap.)
     */
    void s1mp1e$drawGlideOverlay(DrawContext context);
}
