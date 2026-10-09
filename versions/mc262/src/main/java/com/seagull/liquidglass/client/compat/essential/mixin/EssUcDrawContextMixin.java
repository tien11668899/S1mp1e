package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import gg.essential.universal.AdvancedDrawContext;
import gg.essential.universal.utils.TemporaryTextureAllocator;
import kotlin.jvm.functions.Function1;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * UniversalCraft's off-screen UI pass: record the Elementa surfaces painted inside {@code drawImmediate}, and lay their
 * glass into the GUI right before {@code draw} composites the texture (which {@code drawImmediate} calls at its end).
 */
@Pseudo
@Mixin(AdvancedDrawContext.class)
public abstract class EssUcDrawContextMixin {

   @Inject(method = "drawImmediate(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lkotlin/jvm/functions/Function1;)V", at = @At("HEAD"))
   private void lg$begin(GuiGraphicsExtractor g, Function1<?, ?> block, CallbackInfo ci) {
      EssentialGlass.begin();
   }

   @Inject(method = "drawImmediate(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lkotlin/jvm/functions/Function1;)V", at = @At("RETURN"))
   private void lg$end(GuiGraphicsExtractor g, Function1<?, ?> block, CallbackInfo ci) {
      EssentialGlass.end();
   }

   @Inject(method = "draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lgg/essential/universal/utils/TemporaryTextureAllocator$TextureAllocation;)V",
           at = @At("HEAD"))
   private void lg$composite(GuiGraphicsExtractor g, TemporaryTextureAllocator.TextureAllocation alloc, CallbackInfo ci) {
      EssentialGlass.beforeComposite(g, alloc);
   }
}
