package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * Accessor for {@link HandledScreen}'s protected layout fields, so mixins that target SUBCLASSES
 * (creative / effect-rendering inventory) can read them without an inherited {@code @Shadow} (which the mixin AP warns
 * about, since it only validates shadows against the direct target's own members). The fields are declared on
 * {@code HandledScreen} itself, so accessing them here is warning-free. Cast an instance:
 * {@code ((HandledScreenAccessor)(Object) screen).s1mp1e$y()}.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x") int s1mp1e$x();
    @Accessor("y") int s1mp1e$y();
    @Accessor("backgroundWidth") int s1mp1e$backgroundWidth();
    @Accessor("backgroundHeight") int s1mp1e$backgroundHeight();
    @Accessor("handler") ScreenHandler s1mp1e$handler();
    @Accessor("cursorDragging") boolean s1mp1e$cursorDragging();
    @Accessor("cursorDragSlots") Set<Slot> s1mp1e$cursorDragSlots();
}
