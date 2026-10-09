package com.seagull.liquidglass.client.compat.rso;

import dev.s1mp1e.client.gui.GlassWidgets;
import me.flashyreese.mods.reeses_sodium_options.client.gui.frame.AbstractFrame;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.TabHeaderWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * RSO's scissor (scrolling frames, the header's scrolling title) mirrored into {@link GlassWidgets}' stack, so the
 * glass cards / pills / knobs — custom render states that can't read the extractor's private scissor — clip with the
 * content they belong to. Both classes declare their own {@code applyScissor}.
 */
@Mixin({AbstractFrame.class, TabHeaderWidget.class})
public abstract class RsoScissorMixin {
   @Redirect(method = "applyScissor", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;enableScissor(IIII)V"))
   private void lg$scissorOn(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
      GlassWidgets.enableScissor(g, x0, y0, x1, y1);
   }

   @Redirect(method = "applyScissor", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;disableScissor()V"))
   private void lg$scissorOff(GuiGraphicsExtractor g) {
      GlassWidgets.disableScissor(g);
   }
}
