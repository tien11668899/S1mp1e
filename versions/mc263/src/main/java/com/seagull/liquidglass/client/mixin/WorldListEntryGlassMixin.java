package com.seagull.liquidglass.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * World-select rows: on hover vanilla lays a bright translucent (~0xA0 whitish) plate over the world icon so the join
 * arrow reads on it — that is the "白色覆蓋" the user disliked. Replace it with a soft dark scrim, which still gives the
 * (white, SF-Symbol) join / marked-join glyph contrast on a bright thumbnail without the white wash. The glyph sprites
 * themselves are swapped to SF Symbols globally by {@code SfIconMixin}.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry")
public abstract class WorldListEntryGlassMixin {
   @Redirect(method = "extractContent", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
   private void lg$iconHoverScrim(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
      g.fill(x0, y0, x1, y1, 0x66000000);   // soft dark, not the vanilla whitish plate
   }
}
