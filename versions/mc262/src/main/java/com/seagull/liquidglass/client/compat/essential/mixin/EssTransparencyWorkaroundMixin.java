package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import gg.essential.universal.render.URenderPipeline;
import gg.essential.universal.vertex.UBuiltBuffer;
import kotlin.jvm.functions.Function1;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * After an Essential window draws, {@code InternalEssentialGUI.onDrawScreen} paints a window-sized black block through a
 * {@code TRANSPARENCY_WORKAROUND_PIPELINE} that forces the whole off-screen texture opaque. Where the window's own
 * background has been replaced by a glass plate laid UNDER that texture ({@link EssentialGlass}), that would cover the
 * glass and the backdrop with opaque black — so in exactly that case skip the block (closing its buffer); every other
 * Essential screen keeps the workaround as shipped.
 */
@Pseudo
@Mixin(targets = "gg.essential.gui.InternalEssentialGUI")
public abstract class EssTransparencyWorkaroundMixin {

   @Redirect(method = "onDrawScreen(Lgg/essential/universal/UMatrixStack;IIF)V",
             at = @At(value = "INVOKE",
                      target = "Lgg/essential/universal/vertex/UBuiltBuffer;drawAndClose$default(Lgg/essential/universal/vertex/UBuiltBuffer;Lgg/essential/universal/render/URenderPipeline;Lkotlin/jvm/functions/Function1;ILjava/lang/Object;)V",
                      ordinal = 0))
   private void lg$skipOpaqueWorkaround(UBuiltBuffer buffer, URenderPipeline pipeline, Function1<?, ?> block, int mask, Object marker) {
      if (EssentialGlass.windowGlassActive()) {
         try {
            buffer.close();
         } catch (Exception ignored) { }
         return;
      }
      // the original call passes no configuration block (Kotlin default) — same as an empty one
      buffer.drawAndClose(pipeline, block != null ? (Function1) block : b -> kotlin.Unit.INSTANCE);
   }
}
