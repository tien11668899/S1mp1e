package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.film.Film;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame hooks for the DEV film director ({@link Film}): HEAD places the camera / cursor for the frame about to be
 * rendered, TAIL captures the finished frame and advances film time. Inert (a cheap early return) unless
 * {@code S1MP1E_FILM} is set.
 */
@Mixin(Minecraft.class)
public abstract class FilmFrameMixin {
    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"))
    private void s1mp1e$filmStart(boolean tick, CallbackInfo ci) {
        try {
            Film.onFrameStart((Minecraft) (Object) this);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "renderFrame(Z)V", at = @At("TAIL"))
    private void s1mp1e$filmEnd(boolean tick, CallbackInfo ci) {
        try {
            Film.onFrameEnd((Minecraft) (Object) this);
        } catch (Throwable ignored) {
        }
    }
}
