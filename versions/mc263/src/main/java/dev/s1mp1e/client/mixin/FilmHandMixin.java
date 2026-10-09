package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.film.Film;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Film mode only: skips the first-person hand / held item when the current shot sets {@code "hand": false}. */
@Mixin(GameRenderer.class)
public class FilmHandMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$filmHideHand(CallbackInfo ci) {
        if (Film.hideHand()) ci.cancel();
    }
}
