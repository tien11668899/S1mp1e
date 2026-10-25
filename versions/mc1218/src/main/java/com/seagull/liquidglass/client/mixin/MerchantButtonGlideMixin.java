package com.seagull.liquidglass.client.mixin;

import dev.s1mp1e.client.gui.MerchantGlide;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The merchant's trade buttons are plain {@link AbstractButton}s whose capsule is drawn by the button itself (their
 * content is drawn by the screen), so they can't glide from the trade mixin alone. While {@link MerchantGlide} reports an
 * active trade glide and the open screen is a {@link MerchantScreen} — the only situation in which the merchant's buttons
 * are on screen — each button is translated up by the same fractional offset the item rows use, so capsule and content
 * move as one. Completely inert otherwise: every other button, and the merchant at rest, draws untouched.
 *
 * <p>1.21.6-1.21.8: the capsule is drawn inline in {@code AbstractButton.renderWidget} (26.x: its own
 * {@code extractDefaultSprite}, which this wrapped), and {@code ButtonGlassMixin} already redirects that blit. So the
 * whole {@code renderWidget} is translated (HEAD push, RETURN pop); a trade button has no label of its own, so only the
 * capsule moves, as before.
 */
@Mixin(AbstractButton.class)
public abstract class MerchantButtonGlideMixin {
   @Unique private boolean liquidglass$slid;

   @Inject(method = "renderWidget", at = @At("HEAD"))
   private void liquidglass$slideTradeCapsule(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      Minecraft mc = Minecraft.getInstance();
      liquidglass$slid = MerchantGlide.active() && mc.screen instanceof MerchantScreen;
      if (liquidglass$slid) {
         g.pose().pushMatrix();
         g.pose().translate(0f, -MerchantGlide.fracPx());
      }
   }

   @Inject(method = "renderWidget", at = @At("RETURN"))
   private void liquidglass$slideTradeCapsuleEnd(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (liquidglass$slid) {
         liquidglass$slid = false;
         g.pose().popMatrix();
      }
   }
}
