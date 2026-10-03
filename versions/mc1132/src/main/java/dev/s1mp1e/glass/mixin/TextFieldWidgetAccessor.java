package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.13.2: a text field is its own class (not a button): its size is two private final ints. */
@Mixin(TextFieldWidget.class)
public interface TextFieldWidgetAccessor {
    @Accessor("height") int s1mp1e$height();

    @Accessor("width") int s1mp1e$width();
}
