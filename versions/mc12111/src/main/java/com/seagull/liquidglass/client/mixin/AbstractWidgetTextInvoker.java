package com.seagull.liquidglass.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The protected {@code AbstractWidget.alpha} (26.x has a public {@code getAlpha()}). 1.21.11 draws labels through an
 * {@code ActiveTextCollector}, as 26.x does, so there is no {@code renderScrollingString} invoker here (1.21.6-1.21.10).
 */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetTextInvoker {
   @Accessor("alpha")
   float liquidglass$alpha();
}
