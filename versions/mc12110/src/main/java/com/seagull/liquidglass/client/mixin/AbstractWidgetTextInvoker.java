package com.seagull.liquidglass.client.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The protected static {@code AbstractWidget.renderScrollingString(g, font, text, minX, minY, maxX, maxY, colour)}: a
 * label centred in a box, scrolling when it doesn't fit. 1.21.x only (26.x draws labels through an
 * {@code ActiveTextCollector} instead). Also the protected {@code alpha} (26.x has a public {@code getAlpha()}).
 */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetTextInvoker {
   @Invoker("renderScrollingString")
   static void liquidglass$renderScrollingString(GuiGraphics g, Font font, Component text, int minX, int minY, int maxX,
                                                  int maxY, int color) {
      throw new AssertionError();
   }

   @Accessor("alpha")
   float liquidglass$alpha();
}
