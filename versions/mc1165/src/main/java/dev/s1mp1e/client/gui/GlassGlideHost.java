package dev.s1mp1e.client.gui;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.slot.Slot;

/**
 * Duck interface a container screen implements so the shared {@code HandledScreen} glass mixin can hand it the slot-level
 * hooks that make a fixed-slot grid (the creative item grid) glide sub-pixel like the S1mp1e config menu instead of
 * stepping by whole rows. The 1.16.5 ({@link MatrixStack}, legacy fixed-function) counterpart of 26.2's
 * {@code dev.s1mp1e.client.gui.GlassGlideHost} — the slot loop is inlined in {@code HandledScreen.render} here, so the
 * shared mixin drives these from there.
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
     * {@code HandledScreen.render} just before {@code drawForeground} — i.e. inside the {@code RenderSystem.translatef(x,y,0)}
     * legacy model-view translate, right where vanilla drew the slots — so the overlay uses slot-relative coordinates
     * exactly like the slots it replaces. (In 1.16.5 {@code RenderSystem.enableScissor} takes window pixels and ignores
     * the model-view, so the overlay passes ABSOLUTE scissor coords while the item draws stay slot-relative — the
     * MatrixStack-family mirror of the 26.2 double-translate trap.)
     */
    void s1mp1e$drawGlideOverlay(MatrixStack matrices);
}
