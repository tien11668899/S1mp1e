package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code HandledScreen.y} (the container origin's top edge, {@code field_2800}) from code that targets a
 * subclass. Declared on {@code HandledScreen} itself (where the field lives) so the mixin AP resolves it with no
 * inherited-field {@code @Shadow} warning. Used by {@link EffectsInInventoryGlassMixin} to place the effect strip.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("y")
    int s1mp1e$y();

    /** {@code HandledScreen.x} ({@code field_2776}) — container origin left edge. */
    @Accessor("x")
    int s1mp1e$x();

    /** {@code HandledScreen.backgroundHeight} ({@code field_2779}). */
    @Accessor("backgroundHeight")
    int s1mp1e$backgroundHeight();

    /** {@code HandledScreen.backgroundWidth} ({@code field_2792}). */
    @Accessor("backgroundWidth")
    int s1mp1e$backgroundWidth();

    /** {@code HandledScreen.handler} — its {@code slots} drive the creative slot lattice + hover. */
    @Accessor("handler")
    net.minecraft.screen.ScreenHandler s1mp1e$handler();
    /** Quick-craft (drag-distribute) state, for the list screens' shared container glass. */
    @Accessor("cursorDragSlots") java.util.Set<net.minecraft.screen.slot.Slot> s1mp1e$cursorDragSlots();
    @Accessor("cursorDragging") boolean s1mp1e$cursorDragging();
}
