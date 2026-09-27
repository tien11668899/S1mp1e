package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the container-origin geometry of {@link HandledScreen} ({@code x}/{@code y}/{@code backgroundWidth}/
 * {@code backgroundHeight}) from code that targets a subclass. Declared on {@code HandledScreen} itself (where the
 * fields live) so the mixin AP resolves them with no inherited-field {@code @Shadow} warning. Used by
 * {@link EffectsInInventoryGlassMixin} to place the status-effect strip; the later container-scroll work reuses the
 * same accessor.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {

    /** {@code HandledScreen.y} ({@code field_2800}) — container origin top edge. */
    @Accessor("y")
    int s1mp1e$y();

    /** {@code HandledScreen.x} ({@code field_2776}) — container origin left edge. */
    @Accessor("x")
    int s1mp1e$x();

    /** Set {@code HandledScreen.x} — used to re-centre the inventory after undoing the effect-panel shift. */
    @Accessor("x")
    void s1mp1e$setX(int x);

    /** {@code HandledScreen.backgroundWidth} ({@code field_2792}). */
    @Accessor("backgroundWidth")
    int s1mp1e$backgroundWidth();

    /** {@code HandledScreen.backgroundHeight} ({@code field_2779}). */
    @Accessor("backgroundHeight")
    int s1mp1e$backgroundHeight();

    /** {@code HandledScreen.handler} ({@code field_2797}) — for {@code ContainerGlass} from subclass mixins. */
    @Accessor("handler")
    net.minecraft.screen.ScreenHandler s1mp1e$handler();

    /** {@code HandledScreen.cursorDragSlots} ({@code field_2793}) — the quick-craft drag set. */
    @Accessor("cursorDragSlots")
    java.util.Set<net.minecraft.screen.slot.Slot> s1mp1e$cursorDragSlots();

    /** {@code HandledScreen.cursorDragging} ({@code field_2794}). */
    @Accessor("cursorDragging")
    boolean s1mp1e$cursorDragging();
}
