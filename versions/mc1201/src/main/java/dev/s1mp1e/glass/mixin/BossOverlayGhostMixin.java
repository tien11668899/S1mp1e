package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.BossGhost;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drive the boss-bar removal fade-out around {@code InGameHud.render}'s {@code bossBarHud.render(context)} call, which
 * runs every HUD frame regardless of whether any boss is left — unlike {@code BossBarHud.render} itself, which
 * early-returns on an empty map, so a TAIL there would miss exactly the frame the last boss is removed. (1.20.1: the
 * call sits inline in {@code render}; 1.21 moved it into a HUD layer lambda.) BEFORE the call rotates
 * {@link BossGhost}'s drawn set; AFTER it, once the present bosses are drawn, it detects any that just stopped being
 * drawn and paints their fading ghost. {@code BossBarGlassMixin} reports the drawn bosses and their last draw params.
 */
@Mixin(InGameHud.class)
public abstract class BossOverlayGhostMixin {

   @Inject(
      method = "render",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/BossBarHud;render(Lnet/minecraft/client/gui/DrawContext;)V",
               shift = At.Shift.BEFORE)
   )
   private void lg$bossRotate(DrawContext ctx, float tickDelta, CallbackInfo ci) {
      BossGhost.rotate();
   }

   @Inject(
      method = "render",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/BossBarHud;render(Lnet/minecraft/client/gui/DrawContext;)V",
               shift = At.Shift.AFTER)
   )
   private void lg$bossGhosts(DrawContext ctx, float tickDelta, CallbackInfo ci) {
      BossGhost.detectAndDraw(ctx);
   }
}
