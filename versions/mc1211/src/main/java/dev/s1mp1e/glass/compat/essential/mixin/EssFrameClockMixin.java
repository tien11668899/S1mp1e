package dev.s1mp1e.glass.compat.essential.mixin;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the start of every rendered frame, so the Essential glass grabs its backdrop once per FRAME (the screen
 * background) rather than once per Elementa window — a modal stacked over another then refracts the background only,
 * not the modal under it (which would show through).
 */
@Mixin(MinecraftClient.class)
public abstract class EssFrameClockMixin {

   @Inject(method = "render(Z)V", at = @At("HEAD"))
   private void s1mp1e$essFrame(boolean tick, CallbackInfo ci) {
      EssentialGlass.newFrame();
   }
}
