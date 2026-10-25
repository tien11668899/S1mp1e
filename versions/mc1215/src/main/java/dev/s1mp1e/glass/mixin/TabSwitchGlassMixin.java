package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenDissolve;
import net.minecraft.client.gui.tab.Tab;
import net.minecraft.client.gui.tab.TabManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cross-dissolve a tab switch inside a tabbed screen (CreateWorldScreen's Game / World / More tabs). Vanilla tears the
 * old tab's widgets down and builds the new one's in the same call, so the content swaps in one frame. The snapshot is
 * grabbed at HEAD, before that, so it holds the old tab; {@link ScreenDissolve} then fades it over the new tab while the
 * unchanged tab bar / title / footer overlap pixel for pixel.
 *
 * <p>1.21.1 {@code TabManager} has the single 2-arg {@code setCurrentTab(Tab, boolean)} (26.2 added a 3-arg overload
 * that the 2-arg one delegates to) — verified with javap, so this one hook sees every switch exactly once.
 */
@Mixin(TabManager.class)
public abstract class TabSwitchGlassMixin {

    @Shadow private Tab currentTab;

    @Inject(method = "setCurrentTab", at = @At("HEAD"))
    private void s1mp1e$dissolveTabSwitch(Tab tab, boolean clickSound, CallbackInfo ci) {
        // Only on a real change: skip the initial null -> first-tab selection during screen init (it would dissolve the
        // previous frame over the opening screen) and a re-select of the current tab.
        if (this.currentTab != null && this.currentTab != tab) {
            ScreenDissolve.onTabSwitch();
        }
    }
}
