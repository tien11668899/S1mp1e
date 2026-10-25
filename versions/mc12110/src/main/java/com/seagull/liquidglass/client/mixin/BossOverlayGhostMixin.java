package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.BossGhost;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drive the boss-bar removal fade-out from {@code Gui.renderBossOverlay}, which is called every frame even when no boss is
 * left (unlike {@code BossHealthOverlay.render}, which early-returns on an empty map — so a hook there would miss
 * exactly the frame the last boss is removed). HEAD rotates {@link BossGhost}'s drawn set for the coming frame; TAIL, after
 * the overlay drew its present bosses, detects any that just stopped being drawn and paints their fading ghost. See
 * {@link BossGhost}; {@code BossBarGlassMixin} reports the drawn bosses and their last draw params.
 */
@Mixin(Gui.class)
public class BossOverlayGhostMixin {

   @Inject(method = "renderBossOverlay", at = @At("HEAD"))
   private void lg$bossRotate(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
      BossGhost.rotate();
   }

   @Inject(method = "renderBossOverlay", at = @At("TAIL"))
   private void lg$bossGhosts(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
      BossGhost.detectAndDraw(g);
   }
}
