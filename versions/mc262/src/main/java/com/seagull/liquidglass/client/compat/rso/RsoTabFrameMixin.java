package com.seagull.liquidglass.client.compat.rso;

import com.seagull.liquidglass.client.compat.RsoGlass;
import java.lang.reflect.Field;
import me.flashyreese.mods.reeses_sodium_options.client.gui.frame.tab.TabFrame;
import me.flashyreese.mods.reeses_sodium_options.client.gui.layout.LayoutBounds;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The tab rail: one glass panel + the sliding pill on the selected page, drawn before the rail's widgets. The rail
 * field's type ({@code TabRail}) is package-private, so it is read reflectively; its state comes through the
 * {@link RsoGlass.Rail} view {@code RsoRailMixin} adds. The panel stops {@link RsoGlass#GAP} short of the page
 * ({@code frameSection}), which RSO starts ~1 px after the rail.
 */
@Mixin(TabFrame.class)
public abstract class RsoTabFrameMixin {
   @Shadow @Final private LayoutBounds frameSection;
   @Unique private static Field lg$railField;
   @Unique private static boolean lg$railFailed;

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$rail(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!RsoGlass.active || lg$railFailed) return;
      try {
         if (lg$railField == null) {
            lg$railField = TabFrame.class.getDeclaredField("tabRail");
            lg$railField.setAccessible(true);
         }
         Object rail = lg$railField.get(this);
         int contentX = this.frameSection != null ? this.frameSection.x() : Integer.MAX_VALUE;
         if (rail instanceof RsoGlass.Rail r) RsoGlass.drawRail(this, g, r, contentX);
      } catch (Throwable t) {
         lg$railFailed = true;
      }
   }
}
