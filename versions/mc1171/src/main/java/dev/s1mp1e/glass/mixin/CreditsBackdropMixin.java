package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.screen.CreditsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #26 — the credits roll scrolls its own tiled dirt (private no-arg {@code renderBackground()}, raw Tessellator). Opened
 * from the title (no world) that becomes the blurred title panorama; the end-of-game roll (world loaded) keeps
 * vanilla's look ({@link MenuBackdrop#cover()} returns false with a world). HEAD-cancel the vanilla dirt when the
 * backdrop is drawn.
 */
@Mixin(CreditsScreen.class)
public abstract class CreditsBackdropMixin {

    @Inject(method = "renderBackground()V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$panorama(CallbackInfo ci) {
        if (MenuBackdrop.cover()) ci.cancel();
    }
}
