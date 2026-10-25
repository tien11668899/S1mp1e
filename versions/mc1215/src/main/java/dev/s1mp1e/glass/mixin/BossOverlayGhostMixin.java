package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.BossGhost;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drive the boss-bar removal fade-out around the boss-bar HUD layer's {@code bossBarHud.render()} call (the layer method
 * {@code method_55808}, which runs every frame regardless of whether any boss is left — unlike {@code BossBarHud.render}
 * itself, which early-returns on an empty map, so a TAIL there would miss exactly the frame the last boss is removed).
 * BEFORE the call rotates {@link BossGhost}'s drawn set; AFTER it, once the layer has drawn its present bosses, it detects
 * any that just stopped being drawn and paints their fading ghost. {@code BossBarGlassMixin} reports the drawn bosses and
 * their last draw params.
 */
@Mixin(InGameHud.class)
public abstract class BossOverlayGhostMixin {

   @Inject(
      method = "method_55808(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/BossBarHud;render(Lnet/minecraft/client/gui/DrawContext;)V",
               shift = At.Shift.BEFORE)
   )
   private void lg$bossRotate(DrawContext ctx, RenderTickCounter tick, CallbackInfo ci) {
      BossGhost.rotate();
   }

   @Inject(
      method = "method_55808(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/BossBarHud;render(Lnet/minecraft/client/gui/DrawContext;)V",
               shift = At.Shift.AFTER)
   )
   private void lg$bossGhosts(DrawContext ctx, RenderTickCounter tick, CallbackInfo ci) {
      BossGhost.detectAndDraw(ctx);
   }
}
