package dev.s1mp1e.glass.mixin;

import net.minecraft.client.gui.widget.OptionButtonWidget;
import net.minecraft.client.options.Option;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 1.14.4: {@code OptionButtonWidget.option} has no getter (the settings shell asks whether it is a boolean option). */
@Mixin(OptionButtonWidget.class)
public interface OptionButtonWidgetAccessor {
    @Accessor("option") Option s1mp1e$option();
}
