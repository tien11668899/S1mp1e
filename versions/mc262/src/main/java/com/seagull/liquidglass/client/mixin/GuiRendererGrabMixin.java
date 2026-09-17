package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassPipeline;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({GuiRenderer.class})
public class GuiRendererGrabMixin {
   @Inject(
      method = {"render()V"},
      at = {@At("HEAD")}
   )
   private void lg$grabCleanBackdrop(CallbackInfo ci) {
      GlassPipeline.grabBackdrop();
   }
}
