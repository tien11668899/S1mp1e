package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.s1mp1e.client.gui.MerchantGlide;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Slides the faint glass trade-button capsules together with their item content during a villager-trade sub-pixel
 * scroll. The trade buttons are ordinary {@link AbstractButton}s drawn by the screen's widget pass (before the trade
 * content is drawn), so they can't glide from the trade mixin alone. While {@link MerchantGlide} reports an active
 * trade glide and the open screen is a {@link MerchantScreen} — the only situation in which the merchant's buttons are
 * on screen — each button's background is translated up by the same fractional offset the item rows use, so capsule
 * and content move as one. Completely inert otherwise: every other button, and the merchant at rest, draws untouched.
 */
@Mixin(AbstractButton.class)
public abstract class MerchantButtonGlideMixin {

   @WrapMethod(method = "extractDefaultSprite")
   private void liquidglass$slideTradeCapsule(GuiGraphicsExtractor g, Operation<Void> original) {
      Minecraft mc = Minecraft.getInstance();
      if (MerchantGlide.active() && mc.gui != null && mc.gui.screen() instanceof MerchantScreen) {
         g.pose().pushMatrix();
         g.pose().translate(0f, -MerchantGlide.fracPx());
         try {
            original.call(g);
         } finally {
            g.pose().popMatrix();
         }
      } else {
         original.call(g);
      }
   }
}
