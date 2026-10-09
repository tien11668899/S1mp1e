package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import gg.essential.universal.render.UGpuTextureView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Essential 1.5 screens ({@code UScreen}): the rendered texture is composited here — lay the recorded glass under it. */
@Pseudo
@Mixin(targets = "gg.essential.universal.utils.DrawUGpuTextureKt", remap = false)
public abstract class EssDrawTextureMixin {

   @Inject(method = "drawTexture(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lgg/essential/universal/render/UGpuTextureView;)V",
         at = @At("HEAD"), require = 0)
   private static void lg$glassUnder(GuiGraphicsExtractor g, UGpuTextureView texture, CallbackInfo ci) {
      EssentialGlass.beforeComposite(g, texture);
   }
}
