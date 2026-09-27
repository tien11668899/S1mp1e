package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code ContainerScreen.y} (the inventory's top-left Y in scaled GUI px). Used by
 * {@link EffectsInInventoryGlassMixin} to place the status-effect glass strip: the effect boxes
 * start at {@code this.y}. An accessor on the DECLARING class ({@code ContainerScreen}), not a
 * {@code @Shadow} on the {@code AbstractInventoryScreen} subclass, so the field resolves cleanly —
 * a {@code @Shadow} of the inherited field warns "Cannot find target" at build and can fail to bind.
 * 1.15.2 counterpart of the 1.14.4 sibling accessor.
 */
@Mixin(ContainerScreen.class)
public interface ContainerScreenTopAccessor {
    @Accessor("y")
    int s1mp1e$top();

    /** {@code ContainerScreen.x} — the inventory's top-left X in scaled GUI px. */
    @Accessor("x")
    int s1mp1e$left();

    /** {@code ContainerScreen.containerWidth} — the container panel width in GUI px. */
    @Accessor("containerWidth")
    int s1mp1e$xSize();

    /** {@code ContainerScreen.containerHeight} — the container panel height in GUI px. */
    @Accessor("containerHeight")
    int s1mp1e$ySize();

    /** Set {@code ContainerScreen.x} — re-centres the inventory after undoing the effect-panel shift (#3). */
    @Accessor("x")
    void s1mp1e$setLeft(int x);

    /** {@code ContainerScreen.container} — its {@code slots} drive the creative slot lattice + hover (#4). */
    @Accessor("container")
    net.minecraft.container.Container s1mp1e$container();
}
