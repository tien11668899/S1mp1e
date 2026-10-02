package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for {@link HandledScreen}'s protected panel-geometry fields ({@code x/y/backgroundWidth/backgroundHeight}), so
 * subclass mixins (creative tabs, effect strip) read them from the class that declares them — no "cannot find @Shadow
 * target" warning for an inherited field, matching 26.2's {@code AbstractContainerScreenAccessor} pattern.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x") int s1mp1e$x();
    @Accessor("y") int s1mp1e$y();
    @Accessor("backgroundWidth") int s1mp1e$backgroundWidth();
    @Accessor("backgroundHeight") int s1mp1e$backgroundHeight();
    /** {@code HandledScreen.handler} — its {@code slots} drive the creative slot lattice + hover. */
    @Accessor("handler") net.minecraft.screen.ScreenHandler s1mp1e$handler();
    /** Quick-craft (drag-distribute) state, for the list screens' shared container glass. */
    @Accessor("cursorDragSlots") java.util.Set<net.minecraft.screen.slot.Slot> s1mp1e$cursorDragSlots();
    @Accessor("cursorDragging") boolean s1mp1e$cursorDragging();
}
