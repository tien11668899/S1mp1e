package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.SfIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swaps the game's interactive glyph sprites (ticks, crosses, page arrows, the recipe filter, …) for Apple SF Symbols —
 * see {@link SfIcons}. {@code blitSprite(pipeline, id, x, y, w, h)} and the float-alpha overload both delegate to this
 * int-colour overload, so one HEAD hook covers every whole-sprite draw. Unlisted sprites are untouched.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class SfIconMixin {

   @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
         at = @At("HEAD"), cancellable = true)
   private void lg$sfIcon(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color, CallbackInfo ci) {
      if (!"minecraft".equals(sprite.getNamespace())) return;
      if (SfIcons.draw((GuiGraphicsExtractor) (Object) this, sprite.getPath(), x, y, w, h, color)) ci.cancel();
   }
}
