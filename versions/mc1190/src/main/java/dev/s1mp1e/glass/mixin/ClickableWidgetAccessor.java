package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.20.1 {@link ClickableWidget} has {@code setX / setY / setWidth} but no way to change its height (the
 * {@code setDimensionsAndPosition} of later versions does not exist yet). The settings shell lays the screen's own
 * widgets out as rows and needs all four.
 */
@Mixin(ClickableWidget.class)
public interface ClickableWidgetAccessor {
    @Accessor("height") void s1mp1e$setHeight(int height);
}
