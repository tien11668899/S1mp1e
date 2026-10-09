package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TabListFade;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hold the player-tab-list render open for a ~150 ms fade-out. {@code Hud.extractTabList} renders the list inside
 * {@code if (keyPlayerList.isDown() && …)} and drops it the moment the key is released. This redirects that single
 * {@code isDown()} through {@link TabListFade#gate(boolean)}, which returns true while the key is held and for the fade-out
 * window after release — so the list keeps rendering (and {@code TabListGlassMixin} fades its alpha to 0) instead of popping.
 */
@Mixin(Hud.class)
public class TabListGateMixin {

   @Redirect(
      method = "extractTabList",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z")
   )
   private boolean lg$tabListGate(KeyMapping key) {
      return TabListFade.gate(key.isDown());
   }
}
