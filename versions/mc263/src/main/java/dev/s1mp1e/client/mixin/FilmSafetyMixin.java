package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.film.FilmClock;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Film mode safety: if the client crashes while film time is frozen (FilmClock stepping), the integrated server's
 * emergency save and shutdown would wait for time to advance forever and hang the process. Put the clock back on the
 * wall clock at the very start of the crash paths. No-op outside a film run (the clock is already real).
 */
@Mixin(Minecraft.class)
public class FilmSafetyMixin {
    @Inject(method = "delayCrash", at = @At("HEAD"))
    private void s1mp1e$filmClockRealOnDelayedCrash(CrashReport report, CallbackInfo ci) {
        FilmClock.real();
    }

    @Inject(method = "emergencySaveAndCrash", at = @At("HEAD"))
    private void s1mp1e$filmClockRealOnCrash(CrashReport report, CallbackInfo ci) {
        FilmClock.real();
    }
}
