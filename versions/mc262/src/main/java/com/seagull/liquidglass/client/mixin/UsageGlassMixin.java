package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The command usage hints above the chat input ({@code <targets> <item> [count]}) sat on opaque black bars. Each bar
 * becomes a rounded dark scrim — the same soft rounded look as the chat lines — at vanilla's own opacity.
 */
@Mixin(CommandSuggestions.class)
public abstract class UsageGlassMixin {

   @WrapOperation(method = "extractUsage", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
   private void lg$roundBar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
      GlassSurface.scrim(g, x0, y0, x1, y1, 3.0F, (argb & 0xFF000000) | 0x101014);
   }
}
