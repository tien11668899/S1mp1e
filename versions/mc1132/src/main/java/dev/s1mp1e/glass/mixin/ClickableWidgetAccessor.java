package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.20.1 {@link ButtonWidget} has {@code setX / setY / setWidth} but no way to change its height (the
 * {@code setDimensionsAndPosition} of later versions does not exist yet). The settings shell lays the screen's own
 * widgets out as rows and needs all four.
 */
@Mixin(ButtonWidget.class)
public interface ClickableWidgetAccessor {
    @Accessor("height") void s1mp1e$setHeight(int height);

    /** 1.13.2: {@code height} is protected and has no getter. */
    @Accessor("height") int s1mp1e$getHeight();

    /** 1.13.2: the hover flag is only written inside the widget's own render. */
    @Accessor("hovered") void s1mp1e$setHovered(boolean hovered);
}
