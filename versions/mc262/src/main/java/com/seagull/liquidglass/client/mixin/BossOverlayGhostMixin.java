package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.BossGhost;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drive the boss-bar removal fade-out from {@code Hud.extractBossOverlay}, which is called every frame even when no boss is
 * left (unlike {@code BossHealthOverlay.extractRenderState}, which early-returns on an empty map — so a hook there would miss
 * exactly the frame the last boss is removed). HEAD rotates {@link BossGhost}'s drawn set for the coming frame; TAIL, after
 * the overlay drew its present bosses, detects any that just stopped being drawn and paints their fading ghost. See
 * {@link BossGhost}; {@code BossBarGlassMixin} reports the drawn bosses and their last draw params.
 */
@Mixin(Hud.class)
public class BossOverlayGhostMixin {

   @Inject(method = "extractBossOverlay", at = @At("HEAD"))
   private void lg$bossRotate(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
      BossGhost.rotate();
   }

   @Inject(method = "extractBossOverlay", at = @At("TAIL"))
   private void lg$bossGhosts(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
      BossGhost.detectAndDraw(g);
   }
}
