package com.seagull.liquidglass.client.compat.essential.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The vanilla menu backdrop pieces (protected on {@link Screen}), for Essential screens whose own background became glass. */
@Mixin(Screen.class)
public interface EssScreenBgInvoker {
   @Invoker("extractPanorama")
   void lg$panorama(GuiGraphicsExtractor g, float delta);

   @Invoker("extractBlurredBackground")
   void lg$blur(GuiGraphicsExtractor g);

   @Invoker("extractMenuBackground")
   void lg$menuBackground(GuiGraphicsExtractor g);
}
