package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ScreenDissolve;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In-screen content switches cross-dissolve instead of cutting — the 1.15.2 counterpart of the newer lines'
 * {@code TabSwitchGlassMixin} (which hooks {@code TabManager.setCurrentTab}).
 *
 * <p>1.15.2 has no tab system ({@code TabManager} / the tabbed Create World screen arrive with 1.19.4). The one screen
 * that swaps its whole content in place here is {@code CreateWorldScreen}: "More World Options…" hides the name /
 * game-mode / difficulty widgets and shows the seed / world-type dialog (and back), all in one frame, through the
 * private {@code setMoreOptionsOpen(boolean)} (javap-verified; the no-arg public overload is the "open it" shortcut
 * that calls this one). Snapshot at HEAD, while the frame buffer still holds the outgoing layout; a call that does not
 * change the state (vanilla calls it from {@code init}) is not a switch.
 */
@Mixin(CreateWorldScreen.class)
public abstract class TabSwitchGlassMixin {

    @Shadow private boolean moreOptionsOpen;

    @Inject(method = "setMoreOptionsOpen(Z)V", at = @At("HEAD"))
    private void s1mp1e$dissolveMoreOptions(boolean moreOptionsOpen, CallbackInfo ci) {
        if (moreOptionsOpen != this.moreOptionsOpen) ScreenDissolve.onTabSwitch();
    }
}
