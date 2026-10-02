package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.TabListFade;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hold the player-tab-list render open for a ~150 ms fade-out. {@code InGameHud.render} (1.14.4: inline, the only
 * {@code KeyBinding.isPressed()} of that method — checked in the decompiled class) renders the list inside
 * {@code if (playerListKey.isPressed() && …)} and drops it the moment the key is released. This redirects that single
 * {@code isPressed()} through {@link TabListFade#gate(boolean)}, which returns true while the key is held and for the
 * fade-out window after release — so the list keeps rendering (and {@code TabListGlassMixin} fades its alpha to 0) instead
 * of popping. The gate is polled every frame here, so the fade clock advances even while the key is up.
 */
@Mixin(InGameHud.class)
public abstract class TabListGateMixin {

   @Redirect(
      method = "render",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/options/KeyBinding;isPressed()Z")
   )
   private boolean lg$tabListGate(KeyBinding key) {
      return TabListFade.gate(key.isPressed());
   }
}
