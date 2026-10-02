package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.SmoothScrollHost;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Advance a pending smooth scroll once per frame, right before the scroll area draws itself (so this frame's entries,
 * scrollbar and hit-testing all use the stepped amount). {@code AbstractWidget.extractRenderState} is the one final entry
 * point every widget goes through; non-scroll widgets are a single failed instanceof.
 */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetScrollStepMixin {

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$stepSmoothScroll(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if ((Object) this instanceof SmoothScrollHost host) host.liquidglass$stepScroll();
   }
}
