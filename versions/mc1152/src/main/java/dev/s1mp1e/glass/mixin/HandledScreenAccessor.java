package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the container-origin geometry of {@link ContainerScreen} ({@code x}/{@code y}/{@code backgroundWidth}/
 * {@code backgroundHeight}) and its drag state from code that targets a subclass. Declared on {@code ContainerScreen}
 * itself (where the fields live) so the mixin AP resolves them with no inherited-field {@code @Shadow} warning. Used by
 * the container-scroll mixins ({@code StonecutterScrollGlassMixin} / {@code LoomScrollGlassMixin} /
 * {@code MerchantScrollGlassMixin}) and {@code CreativeGlassMixin} to place their glass panels + slot layer.
 */
@Mixin(ContainerScreen.class)
public interface HandledScreenAccessor {

    @Accessor("x")
    int s1mp1e$x();

    @Accessor("x")
    void s1mp1e$setX(int x);

    @Accessor("y")
    int s1mp1e$y();

    @Accessor("containerWidth")
    int s1mp1e$backgroundWidth();

    @Accessor("containerHeight")
    int s1mp1e$backgroundHeight();

    @Accessor("container")
    net.minecraft.container.Container s1mp1e$handler();

    @Accessor("cursorDragSlots")
    java.util.Set<net.minecraft.container.Slot> s1mp1e$cursorDragSlots();

    @Accessor("isCursorDragging")
    boolean s1mp1e$cursorDragging();
}
