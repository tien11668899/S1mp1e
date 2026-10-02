package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassScreens;
import net.minecraft.client.gui.screen.StatsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature (A) — Statistics, the list half. The three stat lists (general / items / mobs) are package-private inner
 * classes of {@link StatsScreen}; each overrides {@code EntryListWidget.renderBackground(MatrixStack)} with ONE call,
 * {@code StatsScreen.this.renderBackground()} (javap: {@code INVOKEVIRTUAL StatsScreen.renderBackground}). That
 * call is the start of every list frame, so it is redirected to {@link GlassScreens#statsPlate}: the full-screen glass
 * plate + grey scrim over the raw world, and the list is remembered so {@code EntryListGlassMixin} drops its tiled dirt
 * and re-lays the plate over the header/footer bands (masking scrolled rows). Falls back to the vanilla background when
 * glass is unusable.
 */
@Mixin(targets = {
        "net.minecraft.client.gui.screen.StatsScreen$CustomStatsListWidget",
        "net.minecraft.client.gui.screen.StatsScreen$ItemStatsListWidget",
        "net.minecraft.client.gui.screen.StatsScreen$EntityStatsListWidget" })
public abstract class StatsListGlassMixin {

    @Redirect(method = "renderBackground()V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/StatsScreen;renderBackground()V"))
    private void s1mp1e$statsPlate(StatsScreen screen) {
        if (!GlassScreens.statsPlate(this)) screen.renderBackground();
    }
}
