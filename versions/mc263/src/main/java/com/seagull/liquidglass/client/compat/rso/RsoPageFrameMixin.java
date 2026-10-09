package com.seagull.liquidglass.client.compat.rso;

import com.seagull.liquidglass.client.compat.RsoGlass;
import me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option.OptionRow;
import me.flashyreese.mods.reeses_sodium_options.client.gui.frame.option.PageFrame;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The option page's content: the glass group cards, drawn under a smoothed scroll offset (RSO moves rows instantly;
 * {@link RsoGlass#contentShift} eases the jump — "滑動要絲滑"), which also wraps the rows (drawn by the super call
 * between these two injects). The translate is popped before the tooltip so the tooltip stays put. The outer
 * {@code TabFrame.applyScissor} clips it. On a page/tab switch the rows cascade in ({@code RsoRowMixin}).
 */
@Mixin(PageFrame.class)
public abstract class RsoPageFrameMixin {
   @Unique private boolean lg$pushed;

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$begin(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      lg$pushed = false;
      if (!RsoGlass.active) return;
      List<OptionRow> rows = ((RsoAbstractFrameAccessor) this).lg$optionRows();
      float dy = RsoGlass.contentShift(this, rows);
      g.pose().pushMatrix();
      if (dy != 0F) g.pose().translate(0F, dy);
      RsoGlass.drawCards(g, rows);
      lg$pushed = true;
   }

   @Inject(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lme/flashyreese/mods/reeses_sodium_options/client/gui/frame/option/OptionTooltipController;render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Ljava/util/List;II)V"))
   private void lg$beforeTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (lg$pushed) { lg$pushed = false; g.pose().popMatrix(); }
   }

   @Inject(method = "extractRenderState", at = @At("RETURN"))
   private void lg$end(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (lg$pushed) { lg$pushed = false; g.pose().popMatrix(); }   // safety if the tooltip call was skipped
   }
}
