package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.ScreenTransition;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cross-dissolve a tab switch inside a tabbed screen (CreateWorldScreen, the friends/stats overlays). Vanilla's
 * {@code setCurrentTab(Tab, boolean)} delegates to the 3-arg {@code setCurrentTab(Tab, boolean, boolean)}, so hooking
 * only the 3-arg method catches every switch once. The snapshot is grabbed BEFORE the content is torn down and
 * rebuilt, so it holds the old tab; the dissolve then fades it over the new tab. See {@link ScreenTransition#onTabSwitch}.
 */
@Mixin(TabManager.class)
public class TabSwitchGlassMixin {
   @Shadow
   private Tab currentTab;

   @Inject(
      method = "setCurrentTab(Lnet/minecraft/client/gui/components/tabs/Tab;ZZ)V",
      at = @At("HEAD")
   )
   private void lg$dissolveTabSwitch(Tab tab, boolean bl, boolean bl2, CallbackInfo ci) {
      // Only on a real change: skip the initial null -> first-tab selection during screen init, and re-selecting the
      // same tab. Otherwise the initial selection would dissolve the (empty) previous frame over the opening screen.
      if (this.currentTab != null && this.currentTab != tab) {
         ScreenTransition.onTabSwitch();
      }
   }
}
