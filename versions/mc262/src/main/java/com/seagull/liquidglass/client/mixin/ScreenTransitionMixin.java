package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.ScreenTransition;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every screen switch goes through {@code Gui.setScreen}; at HEAD the outgoing screen is still {@link #screen} and the
 * last finished frame still holds it, which is what {@link ScreenTransition} snapshots to cross-dissolve from.
 */
@Mixin(Gui.class)
public abstract class ScreenTransitionMixin {

   @Shadow private Screen screen;

   @Inject(method = "setScreen", at = @At("HEAD"))
   private void lg$dissolveFrom(Screen next, CallbackInfo ci) {
      ScreenTransition.onSetScreen(this.screen, next);
   }
}
