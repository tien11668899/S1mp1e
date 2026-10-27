package dev.s1mp1e.glass.compat.essential.mixin;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Essential 1.5 overlay layers (main-menu side buttons, modals): the layer texture is blitted here — glass first. */
@Pseudo
@Mixin(targets = "gg.essential.gui.overlay.LayerRenderer", remap = false)
public abstract class EssLayerRendererMixin {

   @Inject(method = "render", at = @At(value = "INVOKE",
         target = "Lgg/essential/util/McElementaExtractor;blit(IIIIFFFFLgg/essential/universal/render/UGpuTextureView;Lgg/essential/universal/render/UGpuSampler;ZZLjava/awt/Color;)V"),
         require = 0)
   private void lg$glassUnder(@Coerce Object ctx, @Coerce Object state, CallbackInfo ci) {
      EssentialGlass.beforeComposite(state);
   }
}
