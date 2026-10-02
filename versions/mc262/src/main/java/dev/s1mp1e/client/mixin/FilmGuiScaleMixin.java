package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.s1mp1e.client.film.Film;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Film mode only: lets a shot force a GUI scale past vanilla's window-size cap (9 at 4K), for macro close-ups of HUD
 * elements rendered at native resolution instead of being upscaled in the edit. Inert outside a film run.
 */
@Mixin(Window.class)
public class FilmGuiScaleMixin {
    @Inject(method = "calculateScale", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$filmGuiScale(int requested, boolean forceUnicode, CallbackInfoReturnable<Integer> cir) {
        int forced = Film.guiOverride();
        if (forced > 0) cir.setReturnValue(forced);
    }
}
