package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.HealthTrail;
import dev.s1mp1e.client.gui.GuiAlpha;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Health damage trail ({@link HealthTrail}). {@code Hud.extractHearts(g, player, x, y, rowHeight, regenIndex, maxHealth,
 * health, displayHealth, absorption, blinking)} already knows how to draw "the hearts you just lost" as white hearts
 * (its blink): the call's {@code displayHealth} / {@code blinking} are replaced by the trail's range and state, the
 * heart containers are kept from flashing white, and the white ghost hearts are drawn at the trail's fading opacity.
 * Heart types are package-private, so the individual heart draws are bracketed by call-site injects instead of wrapped.
 */
@Mixin(Hud.class)
public abstract class HeartTrailMixin {

   /** extractHearts args: 7 = health, 8 = displayHealth, 10 = blinking. */
   @ModifyArgs(method = "extractPlayerHealth", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/Hud;extractHearts(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;IIIIFIIIZ)V"))
   private void lg$trailArgs(Args args) {
      int health = args.get(7);
      HealthTrail.update(health);
      args.set(8, HealthTrail.displayHealth(health));
      args.set(10, HealthTrail.active(health));
   }

   /** Heart containers (1st extractHeart call): never the white "blinking" frame — the trail says it all. */
   @ModifyArg(method = "extractHearts", at = @At(value = "INVOKE", ordinal = 0,
         target = "Lnet/minecraft/client/gui/Hud;extractHeart(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Hud$HeartType;IIZZZ)V"),
         index = 5)
   private boolean lg$noContainerBlink(boolean blinking) {
      return false;
   }

   /** The white ghost hearts (3rd extractHeart call, drawn only while blinking): at the trail's opacity. */
   @Inject(method = "extractHearts", at = @At(value = "INVOKE", ordinal = 2,
         target = "Lnet/minecraft/client/gui/Hud;extractHeart(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Hud$HeartType;IIZZZ)V"))
   private void lg$ghostBegin(CallbackInfo ci) {
      Integer h = lg$health;
      GuiAlpha.push(h == null ? 1F : HealthTrail.ghostAlpha(h));
   }

   @Inject(method = "extractHearts", at = @At(value = "INVOKE", ordinal = 2, shift = At.Shift.AFTER,
         target = "Lnet/minecraft/client/gui/Hud;extractHeart(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Hud$HeartType;IIZZZ)V"))
   private void lg$ghostEnd(CallbackInfo ci) {
      GuiAlpha.pop();
   }

   @org.spongepowered.asm.mixin.Unique private static Integer lg$health;

   @Inject(method = "extractHearts", at = @At("HEAD"))
   private void lg$rememberHealth(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.world.entity.player.Player player,
                                  int x, int y, int rowHeight, int regen, float maxHealth, int health, int displayHealth,
                                  int absorption, boolean blinking, CallbackInfo ci) {
      lg$health = health;
   }
}
