package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassScreens;
import net.minecraft.client.gui.screen.StatsScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feature (A) — the Statistics screen becomes ONE full-screen liquid-glass plate + grey scrim ({@code 0x0E0E14 @ 0xB4}),
 * the vanilla dim / blurred world and the list's tiled dirt dropped. 1.16.5 port of LiquidGlass26's
 * {@code StatsScreenGlassMixin} ({@code extractMenuBackground} HEAD → plate + scrim, cancel).
 *
 * <h3>1.16.5 vanilla shape (javap-verified, yarn 1.16.5+build.10)</h3>
 * {@code StatsScreen.render} has two paths:
 * <ul>
 *   <li><b>downloading</b> — {@code this.renderBackground(matrices)} (INVOKEVIRTUAL owner {@code StatsScreen}) + the
 *       "downloading statistics" text. Redirected HERE to {@link GlassScreens#statsPlate}.</li>
 *   <li><b>ready</b> — {@code getSelectedStatList().render(...)}: the list's own {@code renderBackground} calls
 *       {@code StatsScreen.this.renderBackground(matrices)} (redirected in {@link StatsListGlassMixin}), then the list
 *       draws its dirt interior + rows + dirt header/footer strips ({@code EntryListGlassMixin} skips the dirt and
 *       re-lays the plate over the header/footer bands for this list), then the title + glass buttons on top.</li>
 * </ul>
 * FRAME-PRIMARY → fresh {@code grabNow()} inside {@link GlassScreens#statsPlate} (R4); gated on the pipeline with the
 * vanilla background as the fallback.
 */
@Mixin(StatsScreen.class)
public abstract class StatsScreenGlassMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/StatsScreen;"
                            + "renderBackground(Lnet/minecraft/client/util/math/MatrixStack;)V"))
    private void s1mp1e$glassDownloading(StatsScreen self, MatrixStack matrices) {
        if (!GlassScreens.statsPlate(null)) self.renderBackground(matrices);
    }
}
