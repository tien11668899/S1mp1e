package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import kotlin.jvm.functions.Function1;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Essential's own copy of the off-screen UI pass ({@code gg.essential.util.AdvancedDrawContext}: overlays, toasts, the
 * multiplayer screen). Besides {@code drawImmediate} it can {@code drawToTexture} first and composite later, so a
 * recording is parked under its texture allocation until that allocation's {@code draw}.
 */
@Pseudo
@Mixin(targets = "gg.essential.util.AdvancedDrawContext")
public abstract class EssUtilDrawContextMixin {

   @Inject(method = "drawImmediate(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lkotlin/jvm/functions/Function1;)V", at = @At("HEAD"))
   private void lg$begin(GuiGraphicsExtractor g, Function1<?, ?> block, CallbackInfo ci) {
      EssentialGlass.begin();
   }

   @Inject(method = "drawImmediate(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lkotlin/jvm/functions/Function1;)V", at = @At("RETURN"))
   private void lg$end(GuiGraphicsExtractor g, Function1<?, ?> block, CallbackInfo ci) {
      EssentialGlass.end();
   }

   @Inject(method = "drawToTexture(Lgg/essential/util/TemporaryTextureAllocator$TextureAllocation;Lkotlin/jvm/functions/Function1;)V",
           at = @At("HEAD"))
   private void lg$beginTex(@Coerce Object alloc, Function1<?, ?> block, CallbackInfo ci) {
      EssentialGlass.begin();
   }

   @Inject(method = "drawToTexture(Lgg/essential/util/TemporaryTextureAllocator$TextureAllocation;Lkotlin/jvm/functions/Function1;)V",
           at = @At("RETURN"))
   private void lg$endTex(@Coerce Object alloc, Function1<?, ?> block, CallbackInfo ci) {
      EssentialGlass.endInto(alloc);
   }

   @Inject(method = "draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lgg/essential/util/TemporaryTextureAllocator$TextureAllocation;)V",
           at = @At("HEAD"))
   private void lg$composite(GuiGraphicsExtractor g, @Coerce Object alloc, CallbackInfo ci) {
      EssentialGlass.beforeComposite(g, alloc);
   }
}
