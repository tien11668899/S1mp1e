package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code ContainerScreen.y} (the inventory's top-left Y in scaled GUI px). Used by
 * {@link EffectsInInventoryGlassMixin} to place the status-effect glass strip: the effect boxes
 * start at {@code this.y}. An accessor (not a {@code @Shadow} on the {@code AbstractInventoryScreen}
 * subclass) so the field resolves cleanly on its declaring class.
 */
@Mixin(ContainerScreen.class)
public interface ContainerScreenTopAccessor {
    @Accessor("y")
    int s1mp1e$top();
}
