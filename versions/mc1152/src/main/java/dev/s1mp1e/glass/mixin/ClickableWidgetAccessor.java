package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.widget.AbstractButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.20.1 {@link AbstractButtonWidget} has {@code setX / setY / setWidth} but no way to change its height (the
 * {@code setDimensionsAndPosition} of later versions does not exist yet). The settings shell lays the screen's own
 * widgets out as rows and needs all four.
 */
@Mixin(AbstractButtonWidget.class)
public interface ClickableWidgetAccessor {
    @Accessor("height") void s1mp1e$setHeight(int height);

    /** 1.15.2: {@code height} is protected and has no getter. */
    @Accessor("height") int s1mp1e$getHeight();
}
